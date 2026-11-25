package com.performantfs.nodes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** One row of {@code nodes}: a file or a folder. */
public record NodeRow(
        UUID id,
        UUID ownerId,
        UUID parentId,
        boolean isFolder,
        String name,
        long size,
        String mime,
        String blobHash,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        UUID trashId) {

    public static final String COLUMNS =
            "id, owner_id, parent_id, is_folder, name, size, mime, blob_hash, created_at, updated_at, deleted_at, trash_id";

    /** {@link #COLUMNS} qualified with a table alias, for joins. */
    public static String columns(String alias) {
        return alias + "." + COLUMNS.replace(", ", ", " + alias + ".");
    }

    public static final RowMapper<NodeRow> MAPPER = (rs, n) -> new NodeRow(
            uuid(rs, "id"),
            uuid(rs, "owner_id"),
            uuid(rs, "parent_id"),
            rs.getBoolean("is_folder"),
            rs.getString("name"),
            rs.getLong("size"),
            rs.getString("mime"),
            rs.getString("blob_hash"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "deleted_at"),
            uuid(rs, "trash_id"));

    public boolean isLive() {
        return deletedAt == null;
    }

    public NodeDto toDto() {
        return new NodeDto(id, parentId, isFolder ? "folder" : "file", name, size, mime,
                blobHash == null ? null : blobHash.strip(), createdAt, updatedAt, deletedAt);
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
