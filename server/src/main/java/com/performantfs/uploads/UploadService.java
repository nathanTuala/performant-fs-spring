package com.performantfs.uploads;

import com.performantfs.blobs.BlobStore;
import com.performantfs.blobs.LengthMismatchException;
import com.performantfs.config.AppProperties;
import com.performantfs.files.MimeTypes;
import com.performantfs.http.ApiException;
import com.performantfs.http.ErrorCode;
import com.performantfs.nodes.ConflictPolicy;
import com.performantfs.nodes.Names;
import com.performantfs.nodes.NewNode;
import com.performantfs.nodes.NodeDto;
import com.performantfs.nodes.NodeRepository;
import com.performantfs.nodes.NodeRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns uploaded bytes into files. Bytes always stream to disk outside any transaction; only the
 * short commit step that records them runs in one.
 */
@Service
public class UploadService {

    /** How the bytes of a new file count against the owner's quota. */
    public enum QuotaMode {
        /** Space was reserved when the upload session opened; turn the reservation into usage. */
        RESERVED,
        /** Nothing was reserved (single-request path); charge now, failing if it doesn't fit. */
        DIRECT
    }

    public record CommitInput(
            UUID ownerId, UUID parentId, String name, String mime, long size, String hash, UUID tempId, QuotaMode quota) {}

    private final JdbcClient db;
    private final NodeRepository nodes;
    private final BlobStore blobs;
    private final AppProperties props;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    UploadService(JdbcClient db, NodeRepository nodes, BlobStore blobs, AppProperties props, TransactionTemplate tx,
                  ApplicationEventPublisher events) {
        this.db = db;
        this.nodes = nodes;
        this.blobs = blobs;
        this.props = props;
        this.tx = tx;
        this.events = events;
    }

    /** A small file in one request: hashed while it streams to disk, so it is never read twice. */
    public NodeDto uploadSmall(UUID ownerId, UUID parentId, String rawName, String claimedMime, InputStream body, long contentLength) {
        String name = Names.normalize(rawName);
        if (contentLength < 0) throw ApiException.badRequest("Content-Length is required");
        if (contentLength > props.fastPathMax().toBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Use the chunked upload API above " + props.fastPathMax().toBytes() + " bytes");
        }
        nodes.getLiveFolder(ownerId, parentId, false);
        assertQuotaFits(ownerId, contentLength); // fail fast; re-checked atomically at commit

        UUID tempId = UUID.randomUUID();
        try {
            blobs.createTemp(tempId, contentLength);
            String hash = blobs.writeTemp(tempId, 0, contentLength, body);
            CommitInput input = new CommitInput(
                    ownerId, parentId, name, MimeTypes.resolve(name, claimedMime), contentLength, hash, tempId, QuotaMode.DIRECT);
            return tx.execute(status -> commitFile(input)).toDto();
        } catch (LengthMismatchException e) {
            deleteQuietly(tempId);
            throw new ApiException(ErrorCode.CHUNK_INVALID, e.getMessage());
        } catch (IOException e) {
            deleteQuietly(tempId);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            deleteQuietly(tempId);
            throw e;
        }
    }

    /**
     * Turns a verified temp file into a file node. Must run inside the caller's transaction:
     * <ol>
     *   <li>take the shared tree lock, so a concurrent trash can't sweep the parent away;
     *   <li>upsert the blob row (+1 ref); its row lock serialises us against garbage collection
     *       and against a concurrent upload of identical bytes;
     *   <li>insert the node, renaming on a sibling clash (this re-validates the parent);
     *   <li>charge the quota;
     *   <li>move the temp file into place, or drop it if those bytes are already stored.
     * </ol>
     * The disk step runs last, so a failure in 1–4 leaves no file behind.
     */
    public NodeRow commitFile(CommitInput in) {
        nodes.lockTree(in.ownerId(), true);
        db.sql("""
                        INSERT INTO blobs (hash, size, refcount) VALUES (?, ?, 1)
                        ON CONFLICT (hash) DO UPDATE SET refcount = blobs.refcount + 1""")
                .params(in.hash(), in.size())
                .update();
        // Queue the bytes for content indexing; already-known bytes keep their row and its text.
        db.sql("INSERT INTO blob_text (hash) VALUES (?) ON CONFLICT DO NOTHING").param(in.hash()).update();
        nodes.getLiveFolder(in.ownerId(), in.parentId(), false);
        NodeRow node = nodes.insert(
                new NewNode(in.ownerId(), in.parentId(), false, in.name(), in.size(), in.mime(), in.hash()),
                ConflictPolicy.RENAME);
        if (in.quota() == QuotaMode.RESERVED) {
            db.sql("UPDATE users SET reserved_bytes = reserved_bytes - ?, used_bytes = used_bytes + ? WHERE id = ?")
                    .params(in.size(), in.size(), in.ownerId())
                    .update();
        } else {
            int updated = db.sql("""
                            UPDATE users SET used_bytes = used_bytes + ?
                            WHERE id = ? AND used_bytes + reserved_bytes + ? <= quota_bytes""")
                    .params(in.size(), in.ownerId(), in.size())
                    .update();
            if (updated == 0) throw new ApiException(ErrorCode.QUOTA_EXCEEDED, "Not enough storage left");
        }
        BlobStore.Promotion promotion;
        try {
            promotion = blobs.promoteTemp(in.tempId(), in.hash());
        } catch (IOException e) {
            throw new UncheckedIOException(e); // rolls the transaction back
        }
        events.publishEvent(new FileStored(in.hash(), node.id(), in.ownerId(), in.size(),
                promotion == BlobStore.Promotion.DEDUPLICATED, in.quota() == QuotaMode.RESERVED));
        return node;
    }

    private void assertQuotaFits(UUID ownerId, long size) {
        boolean fits = db.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND used_bytes + reserved_bytes + ? <= quota_bytes)")
                .params(ownerId, size)
                .query(Boolean.class)
                .single();
        if (!fits) throw new ApiException(ErrorCode.QUOTA_EXCEEDED, "Not enough storage left");
    }

    private void deleteQuietly(UUID tempId) {
        try {
            blobs.deleteTemp(tempId);
        } catch (IOException ignored) {
            // the orphan-temp sweep will get it
        }
    }
}
