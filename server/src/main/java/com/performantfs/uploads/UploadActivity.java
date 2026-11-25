package com.performantfs.uploads;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Logs and counts stored files once their upload has committed. Ids and sizes only: file names
 * are the user's business.
 */
@Component
class UploadActivity {

    private static final Logger log = LoggerFactory.getLogger(UploadActivity.class);

    private final MeterRegistry meters;

    UploadActivity(MeterRegistry meters) {
        this.meters = meters;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onFileStored(FileStored e) {
        String path = e.chunked() ? "chunked" : "single";
        String dedup = String.valueOf(e.deduplicated());
        Counter.builder("pfs.uploads")
                .description("Files stored")
                .tag("path", path).tag("deduplicated", dedup)
                .register(meters).increment();
        DistributionSummary.builder("pfs.upload.size")
                .description("Size of stored files").baseUnit("bytes")
                .tag("path", path)
                .register(meters).record(e.size());
        log.info("file stored node={} user={} bytes={} path={} deduplicated={}", e.nodeId(), e.ownerId(), e.size(), path, dedup);
    }
}
