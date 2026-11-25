package com.performantfs.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How much is stored, read whenever metrics are exported. Each read sums the blobs table, which
 * is fine at this scale; at millions of blobs these would come from running totals instead.
 */
@Component
class StorageMetrics {

    StorageMetrics(JdbcClient db, MeterRegistry meters) {
        Gauge.builder("pfs.storage.bytes", db, d -> d.sql("SELECT coalesce(sum(size), 0) FROM blobs WHERE refcount > 0")
                        .query(Long.class).single())
                .description("Bytes on disk, after deduplication").baseUnit("bytes")
                .register(meters);
        Gauge.builder("pfs.storage.blobs", db, d -> d.sql("SELECT count(*) FROM blobs WHERE refcount > 0").query(Long.class).single())
                .description("Distinct stored files (blobs)")
                .register(meters);
    }
}
