package com.performantfs.search;

import com.performantfs.http.ApiException;
import com.performantfs.nodes.Crumb;
import com.performantfs.nodes.NodeRepository;
import com.performantfs.nodes.NodeRow;
import com.performantfs.nodes.NodeWithPath;
import com.performantfs.nodes.Page;
import com.performantfs.search.SearchQuery.Comparison;
import com.performantfs.search.SearchQuery.DateFilter;
import com.performantfs.search.SearchQuery.SizeFilter;
import com.performantfs.search.SearchQuery.TypeFilter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a parsed search for one user. Free text matches whole words or word prefixes through the
 * tsvector GIN index, or names fuzzily through the trigram index. Results are ranked by both,
 * then by recency, and keyset-paginated on that order.
 */
@Repository
public class SearchRepository {

    /**
     * Minimum word_similarity for a fuzzy name match. The default (0.6) misses common typos:
     * "quartrly" against "Q3 quarterly report.xlsx" scores 0.58, and a one-letter substitution in
     * a short word ("reedme" for "readme.md") scores 0.4. Unrelated words score near 0.
     */
    static final double FUZZY_THRESHOLD = 0.4;

    private static final Map<TypeFilter, String> TYPE_SQL = Map.of(
            TypeFilter.FOLDER, "n.is_folder",
            TypeFilter.FILE, "NOT n.is_folder",
            TypeFilter.IMAGE, "n.mime LIKE 'image/%'",
            TypeFilter.VIDEO, "n.mime LIKE 'video/%'",
            TypeFilter.AUDIO, "n.mime LIKE 'audio/%'",
            TypeFilter.PDF, "n.mime = 'application/pdf'",
            TypeFilter.TEXT, "(n.mime LIKE 'text/%' OR n.mime IN ('application/json', 'application/xml'))",
            TypeFilter.DOC, "(n.mime IN ('application/msword', 'application/vnd.ms-excel', 'application/vnd.ms-powerpoint')"
                    + " OR n.mime LIKE 'application/vnd.openxmlformats-officedocument.%')",
            TypeFilter.ARCHIVE, "n.mime IN ('application/zip', 'application/gzip', 'application/x-tar', 'application/x-7z-compressed')");

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Where a page ended: score as numeric text (exact round trip), updated_at as Postgres text, id. */
    record Cursor(String s, String u, UUID id) {}

    private final JdbcClient db;
    private final NodeRepository nodes;
    private final TransactionTemplate tx;

    SearchRepository(JdbcClient db, NodeRepository nodes, TransactionTemplate tx) {
        this.db = db;
        this.nodes = nodes;
        this.tx = tx;
    }

    /**
     * "quart rep" → "quart:* &amp; rep:*". Each word also matches words starting with it, which is
     * what a person typing expects mid-word. Splitting on anything that isn't a letter or digit
     * mirrors how names are tokenised (q3_report.pdf → q3, report, pdf) and leaves nothing that
     * to_tsquery could read as an operator.
     */
    static String prefixQuery(String text) {
        return NOT_WORD.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .map(w -> w + ":*")
                .collect(Collectors.joining(" & "));
    }

    public Page<NodeWithPath> search(UUID ownerId, SearchQuery q, int limit, String rawCursor) {
        Map<String, Object> params = new HashMap<>();
        params.put("owner", ownerId);
        List<String> where = new ArrayList<>(List.of("n.owner_id = :owner", "n.deleted_at IS NULL", "n.parent_id IS NOT NULL"));
        String score = "0";

        if (!q.text().isEmpty()) {
            params.put("text", q.text().toLowerCase(Locale.ROOT));
            String prefixes = prefixQuery(q.text());
            if (!prefixes.isEmpty()) {
                params.put("tsq", prefixes);
                where.add("(n.search @@ to_tsquery('simple', :tsq) OR :text <% lower(n.name))");
                score = "ts_rank(n.search, to_tsquery('simple', :tsq)) + word_similarity(:text, lower(n.name))";
            } else { // nothing word-like (e.g. "/"): only the fuzzy match can apply
                where.add(":text <% lower(n.name)");
                score = "word_similarity(:text, lower(n.name))";
            }
        }
        for (int i = 0; i < q.phrases().size(); i++) {
            params.put("phrase" + i, "%" + escapeLike(q.phrases().get(i).toLowerCase(Locale.ROOT)) + "%");
            where.add("lower(n.name) LIKE :phrase" + i);
        }
        if (!q.types().isEmpty()) {
            where.add(q.types().stream().map(TYPE_SQL::get).collect(Collectors.joining(" OR ", "(", ")")));
        }
        for (int i = 0; i < q.sizes().size(); i++) {
            SizeFilter f = q.sizes().get(i);
            params.put("size" + i, f.bytes());
            where.add("NOT n.is_folder AND n.size " + f.op().sql + " :size" + i);
        }
        for (int i = 0; i < q.modified().size(); i++) {
            DateFilter f = q.modified().get(i);
            params.put("day" + i, f.day());
            where.add(dateCondition(f.op(), ":day" + i));
        }

        String scope = "";
        if (q.inFolder() != null) {
            // Every folder with that name, and every folder below them. Only folders are walked.
            params.put("folder", q.inFolder());
            scope = """
                    WITH RECURSIVE scope AS (
                      SELECT id FROM nodes
                      WHERE owner_id = :owner AND is_folder AND deleted_at IS NULL AND lower(name) = lower(:folder)
                      UNION
                      SELECT c.id FROM nodes c JOIN scope s ON c.parent_id = s.id WHERE c.is_folder AND c.deleted_at IS NULL
                    )
                    """;
            where.add("n.parent_id IN (SELECT id FROM scope)");
        }

        String after = "";
        if (rawCursor != null && !rawCursor.isEmpty()) {
            Cursor c = decode(rawCursor);
            params.put("cs", c.s());
            params.put("cu", c.u());
            params.put("cid", c.id());
            after = "WHERE (m.score, m.updated_at, m.id) < (CAST(:cs AS numeric), CAST(:cu AS timestamptz), :cid)";
        }
        params.put("limit", limit + 1);

        String sql = """
                %sSELECT * FROM (
                  SELECT %s, (%s)::numeric(12, 6) AS score, n.updated_at::text AS updated_key
                  FROM nodes n
                  WHERE %s
                ) m
                %s
                ORDER BY m.score DESC, m.updated_at DESC, m.id DESC
                LIMIT :limit""".formatted(scope, NodeRow.columns("n"), score, String.join(" AND ", where), after);

        record Hit(NodeRow node, String score, String updatedKey) {}
        List<Hit> hits = tx.execute(status -> {
            // Scoped to this transaction, so it never leaks to another request on the same connection.
            db.sql("SELECT set_config('pg_trgm.word_similarity_threshold', :t, true)")
                    .param("t", String.valueOf(FUZZY_THRESHOLD)).query(String.class).single();
            return db.sql(sql).params(params)
                    .query((rs, n) -> new Hit(NodeRow.MAPPER.mapRow(rs, n), rs.getString("score"), rs.getString("updated_key")))
                    .list();
        });

        boolean hasMore = hits.size() > limit;
        List<Hit> page = hits.subList(0, Math.min(limit, hits.size()));
        Map<UUID, List<Crumb>> paths = nodes.paths(page.stream().map(h -> h.node().id()).toList());
        String next = null;
        if (hasMore && !page.isEmpty()) {
            Hit last = page.getLast();
            next = encode(new Cursor(last.score(), last.updatedKey(), last.node().id()));
        }
        return new Page<>(page.stream().map(h -> new NodeWithPath(h.node().toDto(), paths.get(h.node().id()))).toList(), next);
    }

    /** Day-granular: modified:&lt;2026-01-01 means before that day starts. */
    private static String dateCondition(Comparison op, String day) {
        String start = "CAST(" + day + " AS date)";
        String next = "(" + start + " + 1)";
        return switch (op) {
            case LT -> "n.updated_at < " + start;
            case LE -> "n.updated_at < " + next;
            case GT -> "n.updated_at >= " + next;
            case GE -> "n.updated_at >= " + start;
            case EQ -> "n.updated_at >= " + start + " AND n.updated_at < " + next;
        };
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String encode(Cursor c) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(c));
    }

    private static Cursor decode(String raw) {
        try {
            Cursor c = JSON.readValue(Base64.getUrlDecoder().decode(raw), Cursor.class);
            if (c.s() != null && c.s().matches("-?\\d+(\\.\\d+)?") && c.u() != null && c.id() != null) return c;
        } catch (IllegalArgumentException | JacksonException e) {
            // fall through
        }
        throw ApiException.badRequest("Invalid cursor");
    }
}
