package com.performantfs.uploads;

import com.performantfs.blobs.BlobStore;
import com.performantfs.blobs.LengthMismatchException;
import com.performantfs.config.AppProperties;
import com.performantfs.files.MimeTypes;
import com.performantfs.http.ApiException;
import com.performantfs.http.ErrorCode;
import com.performantfs.nodes.Names;
import com.performantfs.nodes.NodeDto;
import com.performantfs.nodes.NodeRepository;
import com.performantfs.nodes.NodeRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The resumable upload protocol: open a session (reserving quota), send chunks in any order and in
 * parallel, ask which chunks arrived, complete (hash, dedup, commit), or cancel.
 */
@Service
public class ChunkedUploadService {

    record SessionRow(UUID id, UUID ownerId, UUID parentId, String name, String mime, long size, int chunkSize,
                      int chunkCount, String received, int receivedCount, String status, UUID nodeId) {

        long chunkLength(int index) {
            return Math.min(chunkSize, size - (long) index * chunkSize);
        }

        List<Integer> receivedChunks(boolean wanted) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < chunkCount; i++) if ((received.charAt(i) == '1') == wanted) out.add(i);
            return out;
        }
    }

    private static final String SESSION_COLUMNS = """
            id, owner_id, parent_id, name, mime, size, chunk_size, chunk_count, received::text AS received,
            received_count, status, node_id""";

    private final JdbcClient db;
    private final NodeRepository nodes;
    private final BlobStore blobs;
    private final UploadService uploads;
    private final AppProperties props;
    private final TransactionTemplate tx;

    ChunkedUploadService(JdbcClient db, NodeRepository nodes, BlobStore blobs, UploadService uploads,
                         AppProperties props, TransactionTemplate tx) {
        this.db = db;
        this.nodes = nodes;
        this.blobs = blobs;
        this.uploads = uploads;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ open and inspect

    public UploadSession init(UUID ownerId, UUID parentId, String rawName, long size, String claimedMime) {
        String name = Names.normalize(rawName);
        if (size > props.maxFileSize().toBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Files are limited to " + props.maxFileSize().toBytes() + " bytes");
        }
        int chunkSize = (int) props.chunkSize().toBytes();
        int chunkCount = (int) ((size + chunkSize - 1) / chunkSize);
        SessionRow session = tx.execute(status -> {
            nodes.getLiveFolder(ownerId, parentId, false);
            reserveQuota(ownerId, size);
            SessionRow row = db.sql("""
                            INSERT INTO upload_sessions
                              (owner_id, parent_id, name, mime, size, chunk_size, chunk_count, received, expires_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, repeat('0', ?)::varbit, now() + make_interval(secs => ?))
                            RETURNING %s""".formatted(SESSION_COLUMNS))
                    .params(ownerId, parentId, name, MimeTypes.resolve(name, claimedMime), size, chunkSize, chunkCount,
                            chunkCount, props.uploadTtl().toSeconds())
                    .query(this::mapSession)
                    .single();
            try {
                // Inside the transaction: if the disk refuses, the session and reservation roll back too.
                blobs.createTemp(row.id(), size);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return row;
        });
        return toDto(session, null);
    }

    public UploadSession status(UUID ownerId, UUID uploadId) {
        SessionRow row = session(ownerId, uploadId);
        return toDto(row, nodeFor(row));
    }

    // ------------------------------------------------------------------ chunks

    /**
     * Streams one chunk to its offset. The chunk's bit is set only after its length (and digest,
     * when given) check out. If anything fails the bit is cleared: the bytes on disk may now be
     * wrong even if an earlier send of the same chunk was good.
     */
    public Map<String, Integer> putChunk(UUID ownerId, UUID uploadId, int index, InputStream body,
                                         long contentLength, String digestHeader) {
        SessionRow session = session(ownerId, uploadId);
        if (!session.status().equals("open")) throw new ApiException(ErrorCode.UPLOAD_CLOSED, "Upload is " + session.status());
        if (index < 0 || index >= session.chunkCount()) {
            throw new ApiException(ErrorCode.CHUNK_INVALID, "Chunk index must be between 0 and " + (session.chunkCount() - 1));
        }
        long expected = session.chunkLength(index);
        // Refuse a wrong length before reading a byte, so the connection stays usable for the error.
        if (contentLength != expected) {
            throw new ApiException(ErrorCode.CHUNK_INVALID, "Chunk " + index + " must be exactly " + expected + " bytes (Content-Length)");
        }
        String digest = ContentDigest.sha256Hex(digestHeader);

        try {
            String hash = blobs.writeTemp(session.id(), (long) index * session.chunkSize(), expected, body);
            if (digest != null && !digest.equals(hash)) {
                throw new ApiException(ErrorCode.DIGEST_MISMATCH, "Chunk " + index + " arrived corrupted; send it again");
            }
        } catch (LengthMismatchException e) {
            setChunkBit(session.id(), index, false);
            throw new ApiException(ErrorCode.CHUNK_INVALID, e.getMessage());
        } catch (IOException e) {
            setChunkBit(session.id(), index, false);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            setChunkBit(session.id(), index, false);
            throw e;
        }

        int receivedCount = setChunkBit(session.id(), index, true)
                .orElseThrow(() -> new ApiException(ErrorCode.UPLOAD_CLOSED, "Upload was closed while the chunk was being written"));
        return Map.of("receivedCount", receivedCount, "chunkCount", session.chunkCount());
    }

    /** Sets or clears one chunk's bit; empty when the session is no longer open. */
    private Optional<Integer> setChunkBit(UUID uploadId, int index, boolean received) {
        // Both SET expressions read the old row, so repeating the same transition is a no-op.
        return db.sql("""
                        UPDATE upload_sessions SET
                          received = set_bit(received, ?, ?),
                          received_count = received_count + ? - get_bit(received, ?),
                          updated_at = now(),
                          expires_at = now() + make_interval(secs => ?)
                        WHERE id = ? AND status = 'open'
                        RETURNING received_count""")
                .params(index, received ? 1 : 0, received ? 1 : 0, index, props.uploadTtl().toSeconds(), uploadId)
                .query(Integer.class)
                .optional();
    }

    // ------------------------------------------------------------------ finish

    /**
     * Hashes the assembled file and commits it. Idempotent: completing a finished upload returns
     * the same node, so a client that lost the response can simply retry.
     */
    public UploadSession complete(UUID ownerId, UUID uploadId) {
        Optional<SessionRow> claimed = db.sql("""
                        UPDATE upload_sessions SET status = 'completing', updated_at = now()
                        WHERE id = ? AND owner_id = ? AND status = 'open' AND received_count = chunk_count
                        RETURNING %s""".formatted(SESSION_COLUMNS))
                .params(uploadId, ownerId)
                .query(this::mapSession)
                .optional();
        if (claimed.isEmpty()) {
            SessionRow current = session(ownerId, uploadId);
            switch (current.status()) {
                case "done", "completing" -> {
                    return toDto(current, nodeFor(current));
                }
                case "cancelled" -> throw new ApiException(ErrorCode.UPLOAD_CLOSED, "Upload was cancelled");
                default -> throw new ApiException(ErrorCode.UPLOAD_INCOMPLETE,
                        (current.chunkCount() - current.receivedCount()) + " chunk(s) still missing",
                        Map.of("missing", current.receivedChunks(false)));
            }
        }

        SessionRow session = claimed.get();
        try {
            String hash = blobs.hashTemp(session.id());
            NodeRow node = tx.execute(status -> {
                NodeRow created = uploads.commitFile(new UploadService.CommitInput(
                        ownerId, session.parentId(), session.name(),
                        session.mime() == null ? "application/octet-stream" : session.mime(),
                        session.size(), hash, session.id(), UploadService.QuotaMode.RESERVED));
                db.sql("UPDATE upload_sessions SET status = 'done', node_id = ?, updated_at = now() WHERE id = ?")
                        .params(created.id(), session.id())
                        .update();
                return created;
            });
            return toDto(new SessionRow(session.id(), session.ownerId(), session.parentId(), session.name(), session.mime(),
                    session.size(), session.chunkSize(), session.chunkCount(), session.received(), session.receivedCount(),
                    "done", node.id()), node.toDto());
        } catch (ApiException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                cancel(ownerId, uploadId, "completing"); // the folder was deleted mid-upload: nothing to retry
            } else {
                reopen(session.id());
            }
            throw e;
        } catch (IOException e) {
            reopen(session.id());
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            reopen(session.id());
            throw e;
        }
    }

    public void cancel(UUID ownerId, UUID uploadId) {
        cancel(ownerId, uploadId, "open");
    }

    private void cancel(UUID ownerId, UUID uploadId, String from) {
        boolean cancelled = Boolean.TRUE.equals(tx.execute(status -> {
            Optional<Long> size = db.sql("""
                            UPDATE upload_sessions SET status = 'cancelled', updated_at = now()
                            WHERE id = ? AND owner_id = ? AND status = ? RETURNING size""")
                    .params(uploadId, ownerId, from)
                    .query(Long.class)
                    .optional();
            size.ifPresent(s -> releaseReservation(ownerId, s));
            return size.isPresent();
        }));
        if (!cancelled) {
            session(ownerId, uploadId); // 404 if it isn't theirs
            return; // already finished or cancelled: cancelling again is a no-op
        }
        try {
            blobs.deleteTemp(uploadId);
        } catch (IOException ignored) {
            // the orphan-temp sweep will get it
        }
    }

    // ------------------------------------------------------------------ helpers

    private void reopen(UUID uploadId) {
        db.sql("UPDATE upload_sessions SET status = 'open' WHERE id = ? AND status = 'completing'").param(uploadId).update();
    }

    private void reserveQuota(UUID ownerId, long size) {
        int updated = db.sql("""
                        UPDATE users SET reserved_bytes = reserved_bytes + ?
                        WHERE id = ? AND used_bytes + reserved_bytes + ? <= quota_bytes""")
                .params(size, ownerId, size)
                .update();
        if (updated == 0) throw new ApiException(ErrorCode.QUOTA_EXCEEDED, "Not enough storage left");
    }

    void releaseReservation(UUID ownerId, long size) {
        db.sql("UPDATE users SET reserved_bytes = greatest(reserved_bytes - ?, 0) WHERE id = ?").params(size, ownerId).update();
    }

    private SessionRow session(UUID ownerId, UUID uploadId) {
        return db.sql("SELECT " + SESSION_COLUMNS + " FROM upload_sessions WHERE id = ? AND owner_id = ?")
                .params(uploadId, ownerId)
                .query(this::mapSession)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Upload"));
    }

    private NodeDto nodeFor(SessionRow row) {
        if (row.nodeId() == null) return null;
        return db.sql("SELECT " + NodeRow.COLUMNS + " FROM nodes WHERE id = ?")
                .param(row.nodeId())
                .query(NodeRow.MAPPER)
                .optional()
                .map(NodeRow::toDto)
                .orElse(null);
    }

    private SessionRow mapSession(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SessionRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getObject("parent_id", UUID.class), rs.getString("name"), rs.getString("mime"), rs.getLong("size"),
                rs.getInt("chunk_size"), rs.getInt("chunk_count"), rs.getString("received"), rs.getInt("received_count"),
                rs.getString("status"), rs.getObject("node_id", UUID.class));
    }

    private static UploadSession toDto(SessionRow row, NodeDto node) {
        return new UploadSession(row.id(), row.chunkSize(), row.chunkCount(), row.receivedChunks(true), row.status(), node);
    }
}
