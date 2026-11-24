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

    // ------------------------------------------------------------------ structure

    /** True when {@code ancestorId} is {@code nodeId} itself or one of its ancestors. */
    public boolean isAncestorOrSelf(UUID ancestorId, UUID nodeId) {
        return db.sql("""
                        WITH RECURSIVE up AS (
                          SELECT id, parent_id FROM nodes WHERE id = ?
                          UNION ALL
                          SELECT n.id, n.parent_id FROM nodes n JOIN up ON n.id = up.parent_id
                        )
                        SELECT EXISTS (SELECT 1 FROM up WHERE id = ?)""")
                .params(nodeId, ancestorId)
                .query(Boolean.class)
                .single();
    }

    /** Moves and/or renames one node. Throws DuplicateKeyException on a sibling-name clash. */
    public NodeRow moveTo(UUID id, UUID parentId, String name) {
        return db.sql("UPDATE nodes SET parent_id = ?, name = ?, updated_at = now() WHERE id = ? RETURNING " + NodeRow.COLUMNS)
                .params(parentId, name, id)
                .query(NodeRow.MAPPER)
                .single();
    }

    /** Soft-deletes a node and every live descendant in one statement, tagged with one trash id. */
    public void trashSubtree(UUID id, UUID trashId) {
        db.sql("""
                        WITH RECURSIVE sub AS (
                          SELECT id FROM nodes WHERE id = ?
                          UNION ALL
                          SELECT n.id FROM nodes n JOIN sub ON n.parent_id = sub.id WHERE n.deleted_at IS NULL
                        )
                        UPDATE nodes SET deleted_at = now(), trash_id = ? WHERE id IN (SELECT id FROM sub)""")
                .params(id, trashId)
                .update();
    }

    /** Brings one trashed node back under {@code parentId}. Throws DuplicateKeyException on a clash. */
    public NodeRow untrashTo(UUID id, UUID parentId, String name) {
        return db.sql("UPDATE nodes SET parent_id = ?, name = ?, deleted_at = NULL, trash_id = NULL WHERE id = ? RETURNING "
                        + NodeRow.COLUMNS)
                .params(parentId, name, id)
                .query(NodeRow.MAPPER)
                .single();
    }

    /** Restores everything else that was trashed together with a node. */
    public void untrashGroup(UUID trashId) {
        db.sql("UPDATE nodes SET deleted_at = NULL, trash_id = NULL WHERE trash_id = ?").param(trashId).update();
    }

    /** Items the user deleted directly, newest first (not the descendants that went with them). */
    public List<NodeDto> listTrash(UUID ownerId) {
        return db.sql("""
                        SELECT %s FROM nodes n JOIN nodes p ON p.id = n.parent_id
                        WHERE n.owner_id = ? AND n.deleted_at IS NOT NULL
                          AND (p.deleted_at IS NULL OR p.trash_id IS DISTINCT FROM n.trash_id)
                        ORDER BY n.deleted_at DESC
                        LIMIT 1000""".formatted(NodeRow.columns("n")))
                .param(ownerId)
                .query(NodeRow.MAPPER)
                .list()
                .stream()
                .map(NodeRow::toDto)
                .toList();
    }

    /**
     * Permanently deletes trashed subtrees in one statement: cancels uploads still aimed at them,
     * deletes the rows, decrements blob refcounts and releases the bytes and reservations from the
     * owner's quota. Blobs that reach zero are left for garbage collection.
     *
     * @param trashRootIds the trashed items to purge, or null for the user's whole trash
     * @return how many nodes were deleted
     */
    public int purge(UUID ownerId, List<UUID> trashRootIds) {
        String roots = trashRootIds == null
                ? "SELECT id FROM nodes WHERE owner_id = :owner AND deleted_at IS NOT NULL"
                : "SELECT id FROM nodes WHERE owner_id = :owner AND deleted_at IS NOT NULL AND id = ANY(CAST(:ids AS uuid[]))";
        var stmt = db.sql("""
                        WITH RECURSIVE sub AS (
                          %s
                          UNION
                          SELECT n.id FROM nodes n JOIN sub ON n.parent_id = sub.id
                        ),
                        cancelled AS (
                          UPDATE upload_sessions SET status = 'cancelled', updated_at = now()
                          WHERE parent_id IN (SELECT id FROM sub) AND status IN ('open', 'completing')
                          RETURNING size
                        ),
                        del AS (
                          DELETE FROM nodes WHERE id IN (SELECT id FROM sub) RETURNING blob_hash, size
                        ),
                        dec AS (
                          UPDATE blobs b SET refcount = b.refcount - c.n
                          FROM (SELECT blob_hash, count(*)::int AS n FROM del WHERE blob_hash IS NOT NULL GROUP BY blob_hash) c
                          WHERE b.hash = c.blob_hash
                        ),
                        quota AS (
                          UPDATE users SET
                            used_bytes = used_bytes - (SELECT coalesce(sum(size), 0) FROM del),
                            reserved_bytes = greatest(reserved_bytes - (SELECT coalesce(sum(size), 0) FROM cancelled), 0)
                          WHERE id = :owner
                        )
                        SELECT count(*)::int FROM del""".formatted(roots))
                .param("owner", ownerId);
        if (trashRootIds != null) stmt = stmt.param("ids", trashRootIds.stream().map(UUID::toString).toArray(String[]::new));
        return stmt.query(Integer.class).single();
    }
}
