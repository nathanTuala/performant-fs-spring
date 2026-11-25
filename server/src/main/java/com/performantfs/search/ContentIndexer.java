package com.performantfs.search;

import com.performantfs.blobs.BlobStore;
import com.performantfs.embedding.Embedder;
import com.performantfs.embedding.Passages;
import com.performantfs.search.TextExtractor.Result;
import com.performantfs.uploads.FileStored;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Works through the blob_text queue in the background, one file at a time, so a burst of
 * uploads never competes with requests for more than one core.
 *
 * <p>A row is claimed by stamping it in a single short statement ({@code SKIP LOCKED}, so other
 * instances take other rows), then extracted with no transaction or lock open: an upload of the
 * same bytes, or garbage collection, never waits on a slow PDF. A worker that dies leaves a stale
 * stamp, and the row is retried later; a file that keeps killing workers is given up on.
 *
 * <p>Text is also split into passages and embedded for semantic search; the text and its
 * passages are saved in one transaction, so a file never has one without the other.
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
    private final Embedder embedder;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Timer extractTimer;
    private final Timer embedTimer;
    private final TextExtractor extractor = new TextExtractor(TextExtractor.Limits.DEFAULT);
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("content-indexer").factory());
    /** True while a drain is queued but not started: wakes in between fold into it. */
    private final AtomicBoolean queued = new AtomicBoolean();

    ContentIndexer(JdbcClient db, BlobStore blobs, Embedder embedder, TransactionTemplate tx, MeterRegistry meters) {
        this.db = db;
        this.blobs = blobs;
        this.embedder = embedder;
        this.tx = tx;
        this.meters = meters;
        this.extractTimer = Timer.builder("pfs.indexer.extract").description("Reading the text out of a file").register(meters);
        this.embedTimer = Timer.builder("pfs.indexer.embed").description("Embedding a file's passages").register(meters);
        // Read when metrics are exported (every 10 s with the otel profile); a partial index serves it.
        Gauge.builder("pfs.indexer.queue", this, ContentIndexer::queued)
                .description("Files waiting to be indexed")
                .register(meters);
    }

    private double queued() {
        return db.sql("SELECT count(*) FROM blob_text WHERE status = 'pending'").query(Long.class).single();
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
                Timer.Sample sample = Timer.start(meters);
                result = extractor.extract(in, claim.size());
                sample.stop(extractTimer);
            } catch (IOException e) {
                result = new Result.Failed("could not read the blob: " + e.getMessage());
            }
        }
        List<String> passages = List.of();
        float[][] vectors = new float[0][];
        if (result instanceof Result.Text t) {
            try {
                passages = Passages.DEFAULT.split(t.text());
                List<String> toEmbed = passages;
                vectors = embedTimer.record(() -> embedder.embedPassages(toEmbed));
            } catch (RuntimeException e) {
                log.warn("could not embed {}", claim.hash(), e);
                result = new Result.Failed("could not embed the text");
                passages = List.of();
            }
        }
        try {
            save(claim.hash(), result, passages, vectors);
        } catch (DataAccessException e) {
            log.warn("could not store the text of {}", claim.hash(), e);
            save(claim.hash(), new Result.Failed("could not store the text"), List.of(), new float[0][]);
        }
    }

    /**
     * Records the outcome and replaces the file's passages, in one transaction. If garbage
     * collection deleted the blob meanwhile, there's no row left to update and nothing is written.
     */
    private void save(String hash, Result result, List<String> passages, float[][] vectors) {
        String status = switch (result) {
            case Result.Text t -> "indexed";
            case Result.Unsupported u -> "unsupported";
            case Result.Failed f -> {
                log.info("not indexing {}: {}", hash, f.reason());
                yield "failed";
            }
        };
        Counter.builder("pfs.indexer.files").description("Files indexed, by outcome").tag("status", status)
                .register(meters).increment();
        log.debug("indexed {} status={} passages={}", hash, status, passages.size());
        tx.executeWithoutResult(s -> {
            int updated = db.sql("""
                            UPDATE blob_text SET status = :status, content = :content, claimed_at = NULL, updated_at = now()
                            WHERE hash = :hash AND status = 'pending'""")
                    .param("status", status)
                    .param("content", result instanceof Result.Text t ? t.text() : null)
                    .param("hash", hash)
                    .update();
            if (updated == 0) return;
            db.sql("DELETE FROM blob_chunks WHERE hash = ?").param(hash).update();
            for (int i = 0; i < passages.size(); i++) {
                db.sql("INSERT INTO blob_chunks (hash, seq, text, embedding) VALUES (?, ?, ?, CAST(? AS vector))")
                        .params(hash, i, passages.get(i), vectorLiteral(vectors[i]))
                        .update();
            }
        });
    }

    /** pgvector's text form, "[0.1,-0.2,…]": no driver extension needed. */
    static String vectorLiteral(float[] v) {
        var sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
