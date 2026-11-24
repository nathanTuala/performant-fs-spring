package com.performantfs.nodes;

import com.performantfs.http.ApiException;
import java.util.ArrayList;
import java.util.List;
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
}
