package com.performantfs.maintenance;

import com.performantfs.blobs.BlobStore;
import com.performantfs.config.AppProperties;
import com.performantfs.nodes.NodeService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background housekeeping. Every step is idempotent and safe to run alongside requests (and
 * alongside another instance of itself), so a crash mid-run just leaves work for the next run.
 */
@Component
public class Maintenance {

    public record Report(int expiredUploads, int orphanTemps, int blobsDeleted, int trashPurged) {
        boolean didAnything() {
            return expiredUploads + orphanTemps + blobsDeleted + trashPurged > 0;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
    private static final int GC_BATCH = 500;
    private static final Duration ORPHAN_TEMP_AGE = Duration.ofHours(1);

    private final JdbcClient db;
    private final BlobStore blobs;
    private final NodeService nodes;
    private final AppProperties props;
    private final TransactionTemplate tx;

    Maintenance(JdbcClient db, BlobStore blobs, NodeService nodes, AppProperties props, TransactionTemplate tx) {
        this.db = db;
        this.blobs = blobs;
        this.nodes = nodes;
        this.props = props;
        this.tx = tx;
    }

    /** Fixed delay (not rate), so runs never overlap. */
    @Scheduled(fixedDelayString = "${pfs.maintenance-interval:1m}", initialDelayString = "${pfs.maintenance-interval:1m}")
    void scheduled() {
        try {
            Report report = runOnce();
            if (report.didAnything()) log.info("maintenance: {}", report);
        } catch (RuntimeException e) {
            log.error("maintenance failed", e);
        }
    }

    public Report runOnce() {
        int expired = expireUploads();
        int orphans = removeOrphanTemps();
        int purged = purgeOldTrash();
        int collected = collectBlobs();
        db.sql("DELETE FROM sessions WHERE expires_at < now()").update();
        db.sql("DELETE FROM upload_sessions WHERE status IN ('done', 'cancelled') AND updated_at < now() - interval '7 days'").update();
        return new Report(expired, orphans, collected, purged);
    }

    /** Cancels idle or stuck upload sessions, releases their reservations and deletes their bytes. */
    int expireUploads() {
        record Expired(UUID id, UUID ownerId, long size) {}
        List<Expired> expired = tx.execute(status -> {
            List<Expired> rows = db.sql("""
                            UPDATE upload_sessions SET status = 'cancelled', updated_at = now()
                            WHERE (status = 'open' AND expires_at < now())
                               OR (status = 'completing' AND updated_at < now() - interval '1 hour')
                            RETURNING id, owner_id, size""")
                    .query((rs, n) -> new Expired(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)))
                    .list();
            Map<UUID, Long> byOwner = new HashMap<>();
            for (Expired e : rows) byOwner.merge(e.ownerId(), e.size(), Long::sum);
            byOwner.forEach((owner, bytes) -> db.sql(
                            "UPDATE users SET reserved_bytes = greatest(reserved_bytes - ?, 0) WHERE id = ?")
                    .params(bytes, owner)
                    .update());
            return rows;
        });
        for (Expired e : expired) deleteTemp(e.id());
        return expired.size();
    }

    /** Temp files with no live session: cancelled uploads, crashed single-request writes, and so on. */
    int removeOrphanTemps() {
        Instant cutoff = Instant.now().minus(ORPHAN_TEMP_AGE);
        List<BlobStore.Temp> old;
        try {
            old = blobs.listTemps().stream().filter(t -> t.modified().isBefore(cutoff)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (old.isEmpty()) return 0;
        Set<UUID> live = new HashSet<>(db.sql(
                        "SELECT id FROM upload_sessions WHERE id = ANY(CAST(? AS uuid[])) AND status IN ('open', 'completing')")
                .param(old.stream().map(t -> t.id().toString()).toArray(String[]::new))
                .query(UUID.class)
                .list());
        int removed = 0;
        for (BlobStore.Temp t : old) {
            if (live.contains(t.id())) continue;
            deleteTemp(t.id());
            removed++;
        }
        return removed;
    }

    int purgeOldTrash() {
        record Due(UUID ownerId, List<UUID> ids) {}
        List<Due> due = db.sql("""
                        SELECT owner_id, array_agg(id) FROM nodes
                        WHERE deleted_at < now() - make_interval(secs => ?)
                        GROUP BY owner_id LIMIT 100""")
                .param(props.trashRetention().toSeconds())
                .query((rs, n) -> new Due(rs.getObject(1, UUID.class), List.of((UUID[]) rs.getArray(2).getArray())))
                .list();
        return due.stream().mapToInt(d -> nodes.purge(d.ownerId(), d.ids())).sum();
    }

    /**
     * Deletes blobs nobody references. The file is unlinked while the row lock is still held: an
     * upload of the same bytes blocks on that lock, then finds no row and no file, and stores its
     * own copy. Unlinking after commit could delete a file a new upload has just come to rely on.
     */
    int collectBlobs() {
        int total = 0;
        while (true) {
            Integer deleted = tx.execute(status -> {
                List<String> hashes = db.sql("SELECT hash FROM blobs WHERE refcount = 0 LIMIT " + GC_BATCH + " FOR UPDATE SKIP LOCKED")
                        .query(String.class)
                        .list();
                for (String hash : hashes) {
                    try {
                        blobs.deleteBlob(hash.strip());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e); // keep the rows; the next run retries
                    }
                }
                if (!hashes.isEmpty()) {
                    db.sql("DELETE FROM blobs WHERE hash = ANY(CAST(? AS char(64)[]))").param(hashes.toArray(String[]::new)).update();
                }
                return hashes.size();
            });
            total += deleted;
            if (deleted < GC_BATCH) return total;
        }
    }

    private void deleteTemp(UUID id) {
        try {
            blobs.deleteTemp(id);
        } catch (IOException e) {
            log.warn("could not delete temp file {}", id, e);
        }
    }
}
