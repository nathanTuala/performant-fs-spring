package com.performantfs.usage;

import com.performantfs.auth.AuthUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UsageController {

    /**
     * @param fileBytes       bytes in the user's files, not counting Trash; what the UI shows as "used"
     * @param trashBytes      bytes in Trash: still stored, and still counted against the quota
     * @param usedBytes       bytes counted against the quota (files plus Trash)
     * @param dedupSavedBytes logical bytes across all users minus bytes actually stored
     */
    record Usage(long fileBytes, long trashBytes, long usedBytes, long reservedBytes, long quotaBytes,
                 int fileCount, int folderCount, long dedupSavedBytes) {}

    private final JdbcClient db;

    UsageController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping("/api/stats")
    Usage usage(@AuthenticationPrincipal AuthUser user) {
        // One pass over the user's nodes yields every count and total.
        return db.sql("""
                        SELECT s.file_bytes, s.trash_bytes, u.used_bytes, u.reserved_bytes, u.quota_bytes,
                               s.files, s.folders - 1 AS folders,  -- the root doesn't count
                               (SELECT coalesce(sum(size::numeric * greatest(refcount - 1, 0)), 0) FROM blobs)::bigint AS saved
                        FROM users u, LATERAL (
                          SELECT
                            count(*) FILTER (WHERE NOT is_folder AND deleted_at IS NULL)::int AS files,
                            count(*) FILTER (WHERE is_folder AND deleted_at IS NULL)::int AS folders,
                            coalesce(sum(size) FILTER (WHERE NOT is_folder AND deleted_at IS NULL), 0)::bigint AS file_bytes,
                            coalesce(sum(size) FILTER (WHERE NOT is_folder AND deleted_at IS NOT NULL), 0)::bigint AS trash_bytes
                          FROM nodes WHERE owner_id = u.id
                        ) s
                        WHERE u.id = ?""")
                .param(user.id())
                .query((rs, n) -> new Usage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getInt(6), rs.getInt(7), rs.getLong(8)))
                .single();
    }
}
