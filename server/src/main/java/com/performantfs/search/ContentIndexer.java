package com.performantfs.search;

import com.performantfs.blobs.BlobStore;
import com.performantfs.search.TextExtractor.Result;
import com.performantfs.uploads.FileStored;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Works through the blob_text queue in the background, one file at a time, so a burst of
 * uploads never competes with requests for more than one core.
 *
 * <p>A row is claimed by stamping it in a single short statement ({@code SKIP LOCKED}, so other
 * instances take other rows), then extracted with no transaction or lock open: an upload of the
 * same bytes, or garbage collection, never waits on a slow PDF. A worker that dies leaves a stale
 * stamp, and the row is retried later; a file that keeps killing workers is given up on.
 */
@Component
public class ContentIndexer {

    private static final Logger log = LoggerFactory.getLogger(ContentIndexer.class);
    private static final int BATCH = 10;
    static final int MAX_ATTEMPTS = 3;
    /** Longer than any extraction can take, given the extractor's time limit. */
    private static final String STALE_CLAIM = "10 minutes";

    private final JdbcClient db;
    private final BlobStore blobs;
    private final TextExtractor extractor = new TextExtractor(TextExtractor.Limits.DEFAULT);
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("content-indexer").factory());
    /** True while a drain is queued but not started: wakes in between fold into it. */
    private final AtomicBoolean queued = new AtomicBoolean();

    ContentIndexer(JdbcClient db, BlobStore blobs) {
        this.db = db;
        this.blobs = blobs;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onFileStored(FileStored event) {
        wake();
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        wake();
    }

    @Scheduled(fixedDelayString = "${pfs.index-interval:30s}", initialDelayString = "${pfs.index-interval:30s}")
    void poll() {
        wake();
    }

    /** Makes sure a drain runs soon. Cheap and safe to call from anywhere. */
    public void wake() {
        if (!queued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                queued.set(false);
                drain();
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            queued.set(false); // shutting down
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        worker.shutdownNow();
        worker.awaitTermination(5, TimeUnit.SECONDS);
    }

    private void drain() {
        try {
            while (indexBatch() == BATCH && !Thread.currentThread().isInterrupted()) {
                // keep going until the queue is empty
            }
        } catch (RuntimeException e) {
            log.error("content indexing failed", e);
        }
    }

    record Claim(String hash, long size, int attempts) {}

    /** Claims and indexes up to one batch; returns how many rows it claimed. */
    int indexBatch() {
        List<Claim> claims = db.sql("""
                        UPDATE blob_text t SET claimed_at = now(), attempts = t.attempts + 1
                        FROM blobs b
                        WHERE b.hash = t.hash AND t.hash IN (
                          SELECT hash FROM blob_text
                          WHERE status = 'pending' AND (claimed_at IS NULL OR claimed_at < now() - CAST(:stale AS interval))
                          ORDER BY updated_at
                          LIMIT :batch
                          FOR UPDATE SKIP LOCKED)
                        RETURNING t.hash, b.size, t.attempts""")
                .param("stale", STALE_CLAIM)
                .param("batch", BATCH)
                .query((rs, n) -> new Claim(rs.getString(1), rs.getLong(2), rs.getInt(3)))
                .list();
        for (Claim claim : claims) index(claim);
        return claims.size();
    }

    private void index(Claim claim) {
        Result result;
        if (claim.attempts() > MAX_ATTEMPTS) {
            result = new Result.Failed("gave up after " + MAX_ATTEMPTS + " attempts");
        } else {
            try (InputStream in = blobs.openBlob(claim.hash(), 0)) {
                result = extractor.extract(in, claim.size());
            } catch (IOException e) {
                result = new Result.Failed("could not read the blob: " + e.getMessage());
            }
        }
        try {
            save(claim.hash(), result);
        } catch (DataAccessException e) {
            log.warn("could not store the text of {}", claim.hash(), e);
            save(claim.hash(), new Result.Failed("could not store the text"));
        }
    }

    /** Records the outcome. If garbage collection deleted the blob meanwhile, there's no row left to update. */
    private void save(String hash, Result result) {
        String status = switch (result) {
            case Result.Text t -> "indexed";
            case Result.Unsupported u -> "unsupported";
            case Result.Failed f -> {
                log.info("not indexing {}: {}", hash, f.reason());
                yield "failed";
            }
        };
        db.sql("""
                        UPDATE blob_text SET status = :status, content = :content, claimed_at = NULL, updated_at = now()
                        WHERE hash = :hash AND status = 'pending'""")
                .param("status", status)
                .param("content", result instanceof Result.Text t ? t.text() : null)
                .param("hash", hash)
                .update();
    }
}
