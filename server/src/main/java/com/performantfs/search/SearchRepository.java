package com.performantfs.search;

import com.performantfs.http.ApiException;
import com.performantfs.nodes.Crumb;
import com.performantfs.nodes.NodeRepository;
import com.performantfs.nodes.NodeRow;
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
 * Runs a parsed search for one user. Free text matches names (whole words or word prefixes through
 * the tsvector GIN index, or fuzzily through the trigram index) or the text inside files (blob_text,
 * filled by {@link ContentIndexer}). Name matches rank first, then contents matches; then recency.
 * Results are keyset-paginated on that order.
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

    /**
     * "halting prob" → "halting:* &lt;-&gt; prob:*": the words in this order, next to each other,
     * the last (like every word) possibly still being typed.
     */
    static String phraseQuery(String phrase) {
        return NOT_WORD.splitAsStream(phrase.toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .map(w -> w + ":*")
                .collect(Collectors.joining(" <-> "));
    }

    public Page<SearchHit> search(UUID ownerId, SearchQuery q, int limit, String rawCursor) {
        Map<String, Object> params = new HashMap<>();
        params.put("owner", ownerId);
        List<String> where = new ArrayList<>(List.of("n.owner_id = :owner", "n.deleted_at IS NULL", "n.parent_id IS NOT NULL"));
        List<String> ctes = new ArrayList<>();
        String from = "nodes n";
        String score = "0";
        String contentHit = "false";

        // What the words and phrases must match: the name, or (failing that) the file's contents.
        List<String> nameMatch = new ArrayList<>();
        String nameScore = "0";
        List<String> contentQuery = new ArrayList<>();
        boolean contentPossible = true;
        if (!q.text().isEmpty()) {
            params.put("text", q.text().toLowerCase(Locale.ROOT));
            String prefixes = prefixQuery(q.text());
            if (!prefixes.isEmpty()) {
                params.put("tsq", prefixes);
                nameMatch.add("(n.search @@ to_tsquery('simple', :tsq) OR :text <% lower(n.name))");
                nameScore = "ts_rank(n.search, to_tsquery('simple', :tsq)) + word_similarity(:text, lower(n.name))";
                contentQuery.add("to_tsquery('simple', :tsq)");
            } else { // nothing word-like (e.g. "/"): only the fuzzy name match can apply
                nameMatch.add(":text <% lower(n.name)");
                nameScore = "word_similarity(:text, lower(n.name))";
                contentPossible = false;
            }
        }
        for (int i = 0; i < q.phrases().size(); i++) {
            String phrase = q.phrases().get(i);
            params.put("phrase" + i, "%" + escapeLike(phrase.toLowerCase(Locale.ROOT)) + "%");
            nameMatch.add("lower(n.name) LIKE :phrase" + i);
            // In contents a phrase is words in that order; one with no words can't match there.
            String words = phraseQuery(phrase);
            params.put("phraseq" + i, words);
            contentQuery.add("to_tsquery('simple', :phraseq" + i + ")");
            if (words.isEmpty()) contentPossible = false;
        }

        if (!nameMatch.isEmpty() && contentPossible) {
            String names = String.join(" AND ", nameMatch);
            String contents = "t.search @@ (" + String.join(" && ", contentQuery) + ")";
            // Two index lookups (names through nodes' GIN index, contents through blob_text's),
            // unioned, rather than one OR across the join, which no index can answer.
            ctes.add("""
                    hits AS (
                      SELECT n.id FROM nodes n
                      WHERE n.owner_id = :owner AND n.deleted_at IS NULL AND %s
                      UNION
                      SELECT n.id FROM blob_text t JOIN nodes n ON n.blob_hash = t.hash
                      WHERE %s AND n.owner_id = :owner AND n.deleted_at IS NULL
                    )""".formatted(names, contents));
            from = "nodes n JOIN hits h ON h.id = n.id LEFT JOIN blob_text t ON t.hash = n.blob_hash";
            // A name match always ranks above a contents-only match: rank(…, 32) is below 1.
            score = "CASE WHEN %s THEN 1 + %s ELSE 0 END + CASE WHEN %s THEN ts_rank(t.search, %s, 32) ELSE 0 END"
                    .formatted(names, nameScore, contents, String.join(" && ", contentQuery));
            contentHit = "coalesce(" + contents + ", false)";
        } else if (!nameMatch.isEmpty()) {
            where.addAll(nameMatch);
            score = nameScore;
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

        boolean recursive = false;
        if (q.inFolder() != null) {
            // Every folder with that name, and every folder below them. Only folders are walked.
            params.put("folder", q.inFolder());
            recursive = true;
            ctes.addFirst("""
                    scope AS (
                      SELECT id FROM nodes
                      WHERE owner_id = :owner AND is_folder AND deleted_at IS NULL AND lower(name) = lower(:folder)
                      UNION
                      SELECT c.id FROM nodes c JOIN scope s ON c.parent_id = s.id WHERE c.is_folder AND c.deleted_at IS NULL
                    )""");
            where.add("n.parent_id IN (SELECT id FROM scope)");
        }
        String with = ctes.isEmpty() ? "" : (recursive ? "WITH RECURSIVE " : "WITH ") + String.join(",\n", ctes) + "\n";

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
                  SELECT %s, (%s)::numeric(12, 6) AS score, n.updated_at::text AS updated_key, %s AS content_hit
                  FROM %s
                  WHERE %s
                ) m
                %s
                ORDER BY m.score DESC, m.updated_at DESC, m.id DESC
                LIMIT :limit""".formatted(with, NodeRow.columns("n"), score, contentHit, from, String.join(" AND ", where), after);

        record Hit(NodeRow node, String score, String updatedKey, boolean contentHit) {}
        List<Hit> hits = tx.execute(status -> {
            // Scoped to this transaction, so it never leaks to another request on the same connection.
            db.sql("SELECT set_config('pg_trgm.word_similarity_threshold', :t, true)")
                    .param("t", String.valueOf(FUZZY_THRESHOLD)).query(String.class).single();
            return db.sql(sql).params(params)
                    .query((rs, n) -> new Hit(NodeRow.MAPPER.mapRow(rs, n), rs.getString("score"), rs.getString("updated_key"), rs.getBoolean("content_hit")))
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
        List<String> matchedHashes = page.stream().filter(Hit::contentHit).map(h -> h.node().blobHash()).distinct().toList();
        Map<String, Snippet> snippets = matchedHashes.isEmpty()
                ? Map.of()
                : snippets(matchedHashes, String.join(" && ", contentQuery), snippetWords(q), params);
        return new Page<>(page.stream()
                .map(h -> new SearchHit(h.node().toDto(), paths.get(h.node().id()),
                        h.contentHit() ? snippets.get(h.node().blobHash()) : null))
                .toList(), next);
    }

    /** Characters of context kept on each side of the first match. */
    private static final int SNIPPET_CONTEXT = 150;

    /**
     * One excerpt per file, for the page being returned only. ts_headline re-parses the text it
     * is given, so it is handed a small window around the first matching word rather than the
     * whole document (up to {@link TextExtractor.Limits#maxChars()} characters).
     */
    private Map<String, Snippet> snippets(List<String> hashes, String contentQuery, List<String> words,
                                          Map<String, Object> queryParams) {
        Map<String, Object> params = new HashMap<>(queryParams);
        params.put("hashes", hashes.toArray(String[]::new));
        // \m is "start of a word"; the words are letters and digits only, so nothing in them is special.
        params.put("first", "\\m(" + String.join("|", words) + ")");
        params.put("context", SNIPPET_CONTEXT);
        params.put("opts", "MaxFragments=1, MaxWords=30, MinWords=12, StartSel=" + Snippet.START + ", StopSel=" + Snippet.STOP);
        String sql = """
                SELECT hash, ts_headline('simple', substr(content, w.start, 2 * :context), %s, :opts) AS marked,
                       w.start > 1 AS cut_start, w.start + 2 * :context <= length(content) AS cut_end
                FROM blob_text t,
                     LATERAL (SELECT greatest(1, regexp_instr(t.content, :first, 1, 1, 0, 'i') - :context) AS start) w
                WHERE t.hash = ANY(CAST(:hashes AS char(64)[]))""".formatted(contentQuery);
        Map<String, Snippet> out = new HashMap<>();
        db.sql(sql).params(params).query((rs, n) -> {
            out.put(rs.getString("hash"), Snippet.parse(rs.getString("marked"), rs.getBoolean("cut_start"), rs.getBoolean("cut_end")));
            return null;
        }).list();
        return out;
    }

    /** The words a snippet should be centred on: the query's words, then the words of its phrases. */
    private static List<String> snippetWords(SearchQuery q) {
        List<String> words = new ArrayList<>();
        java.util.stream.Stream.concat(java.util.stream.Stream.of(q.text()), q.phrases().stream())
                .flatMap(NOT_WORD::splitAsStream)
                .filter(w -> !w.isEmpty())
                .map(w -> w.toLowerCase(Locale.ROOT))
                .forEach(words::add);
        return words;
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
