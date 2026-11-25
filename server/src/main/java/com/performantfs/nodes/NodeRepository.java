package com.performantfs.nodes;

import com.performantfs.http.ApiException;
import com.performantfs.http.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the folder tree. Every lookup is scoped to the owner. */
@Repository
public class NodeRepository {

    private final JdbcClient db;

    NodeRepository(JdbcClient db) {
        this.db = db;
    }

    /** A node the user owns, live or trashed; 404 otherwise (never 403, so ids can't be probed). */
    public NodeRow get(UUID ownerId, UUID id, boolean forUpdate) {
        return db.sql("SELECT " + NodeRow.COLUMNS + " FROM nodes WHERE id = ? AND owner_id = ?" + (forUpdate ? " FOR UPDATE" : ""))
                .params(id, ownerId)
                .query(NodeRow.MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Item"));
    }

    public NodeRow getLive(UUID ownerId, UUID id, boolean forUpdate) {
        NodeRow node = get(ownerId, id, forUpdate);
        if (!node.isLive()) throw ApiException.notFound("Item");
        return node;
    }

    public NodeRow getLiveFolder(UUID ownerId, UUID id, boolean forUpdate) {
        NodeRow node = getLive(ownerId, id, forUpdate);
        if (!node.isFolder()) throw ApiException.badRequest("Target is not a folder");
        return node;
    }

    /** Ancestors of {@code id} from the root down, excluding the node itself. One indexed hop per level. */
    public List<Crumb> path(UUID id) {
        return db.sql("""
                        WITH RECURSIVE anc AS (
                          SELECT id, parent_id, name, 0 AS depth FROM nodes WHERE id = ?
                          UNION ALL
                          SELECT n.id, n.parent_id, n.name, a.depth + 1 FROM nodes n JOIN anc a ON n.id = a.parent_id
                        )
                        SELECT id, name FROM anc WHERE depth > 0 ORDER BY depth DESC""")
                .param(id)
                .query((rs, n) -> new Crumb(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    /**
     * Folders-first keyset pagination. Each segment (folders, then files) is a single range scan
     * of a (parent_id, is_folder, key, id) index in either direction, so page 1,000 costs the
     * same as page 1. Fetches one extra row to know whether another page exists.
     */
    public Page<NodeDto> listChildren(UUID parentId, Sort sort, int limit, String rawCursor) {
        ListingCursor cursor = rawCursor == null || rawCursor.isEmpty() ? null : ListingCursor.decode(rawCursor);
        String expr = sort.key().expression;
        String dir = sort.descending() ? "DESC" : "ASC";
        String cmp = sort.descending() ? "<" : ">";
        int want = limit + 1;

        record Hit(NodeRow node, String sortKey) {}
        List<Hit> rows = new ArrayList<>();
        for (boolean isFolder : new boolean[] {true, false}) {
            if (cursor != null && !cursor.f() && isFolder) continue; // folder segment already done
            boolean after = cursor != null && cursor.f() == isFolder;
            String keyset = after ? "AND (%s, id) %s (?::%s, ?::uuid)".formatted(expr, cmp, sort.key().sqlType) : "";
            var stmt = db.sql("""
                            SELECT %s, (%s)::text AS sort_key FROM nodes
                            WHERE parent_id = ? AND is_folder = ? AND deleted_at IS NULL %s
                            ORDER BY %s %s, id %s
                            LIMIT ?""".formatted(NodeRow.COLUMNS, expr, keyset, expr, dir, dir))
                    .param(parentId)
                    .param(isFolder);
            if (after) stmt = stmt.param(cursor.k()).param(cursor.id());
            rows.addAll(stmt.param(want - rows.size())
                    .query((rs, n) -> new Hit(NodeRow.MAPPER.mapRow(rs, n), rs.getString("sort_key")))
                    .list());
            if (rows.size() >= want) break;
        }

        boolean hasMore = rows.size() > limit;
        List<Hit> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (hasMore && !page.isEmpty()) {
            Hit last = page.getLast();
            next = new ListingCursor(last.node().isFolder(), last.sortKey(), last.node().id()).encode();
        }
        return new Page<>(page.stream().map(h -> h.node().toDto()).toList(), next);
    }

    // ------------------------------------------------------------------ writes

    private static final int MAX_RENAME_ROUNDS = 5;
    private static final int CANDIDATES_PER_ROUND = 50;

    /**
     * Runs {@code attempt(name)}; when it reports a sibling-name clash (empty), either fails with
     * 409 name_conflict or retries with the first free "name (n).ext". The unique index is the
     * source of truth, so this is safe against concurrent writers.
     */
    public <T> T withUniqueName(UUID parentId, String name, ConflictPolicy policy, Function<String, Optional<T>> attempt) {
        Optional<T> first = attempt.apply(name);
        if (first.isPresent()) return first.get();
        if (policy == ConflictPolicy.ERROR) {
            throw new ApiException(ErrorCode.NAME_CONFLICT, "An item named \"" + name + "\" already exists here",
                    Map.of("name", name));
        }
        int next = 1;
        for (int round = 0; round < MAX_RENAME_ROUNDS; round++) {
            int from = next;
            List<String> candidates = IntStream.range(from, from + CANDIDATES_PER_ROUND).mapToObj(i -> Names.numbered(name, i)).toList();
            next += CANDIDATES_PER_ROUND;
            Set<String> taken = new HashSet<>(db.sql("""
                            SELECT lower(name) FROM nodes
                            WHERE parent_id = ? AND deleted_at IS NULL AND lower(name) = ANY(?)""")
                    .param(parentId)
                    .param(candidates.stream().map(c -> c.toLowerCase(Locale.ROOT)).toArray(String[]::new))
                    .query(String.class)
                    .list());
            for (String candidate : candidates) {
                if (taken.contains(candidate.toLowerCase(Locale.ROOT))) continue;
                Optional<T> result = attempt.apply(candidate);
                if (result.isPresent()) return result.get(); // otherwise lost a race; try the next
            }
        }
        throw new ApiException(ErrorCode.NAME_CONFLICT, "Could not find a free name for \"" + name + "\"", Map.of("name", name));
    }

    /** Inserts a node, resolving a sibling-name clash per {@code policy}. */
    public NodeRow insert(NewNode node, ConflictPolicy policy) {
        return withUniqueName(node.parentId(), node.name(), policy, candidate -> db.sql("""
                        INSERT INTO nodes (owner_id, parent_id, is_folder, name, size, mime, blob_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (parent_id, lower(name)) WHERE deleted_at IS NULL DO NOTHING
                        RETURNING %s""".formatted(NodeRow.COLUMNS))
                .params(node.ownerId(), node.parentId(), node.isFolder(), candidate, node.size(), node.mime(), node.blobHash())
                .query(NodeRow.MAPPER)
                .optional());
    }

    /**
     * Per-user tree lock, held until the transaction ends. Structural changes (move, trash,
     * restore, purge) take it exclusively: two moves that each pass their own cycle check could
     * otherwise both commit. Inserts take it shared, so they run in parallel with each other but
     * never land in a folder that a concurrent trash is sweeping up.
     */
    public void lockTree(UUID ownerId, boolean shared) {
        String fn = shared ? "pg_advisory_xact_lock_shared" : "pg_advisory_xact_lock";
        db.sql("SELECT " + fn + "(hashtextextended(?, 42))").param(ownerId.toString()).query(Object.class).single();
    }
}
