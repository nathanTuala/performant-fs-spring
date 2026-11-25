package com.performantfs.observability;

import static com.performantfs.support.Bytes.sha256;
import static com.performantfs.support.Bytes.utf8;
import static com.performantfs.support.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.Chunks;
import com.performantfs.support.IntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

@ExtendWith(OutputCaptureExtension.class)
class ActivityMetricsIT extends IntegrationTest {

    @Autowired MeterRegistry meters;
    @Autowired JdbcClient db;

    private double count(String name, String... tags) {
        Counter c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void countsUploadsByPathAndDeduplication(CapturedOutput output) {
        var alice = newUser();
        byte[] bytes = utf8("counted " + UUID.randomUUID());
        double fresh = count("pfs.uploads", "path", "single", "deduplicated", "false");
        double dup = count("pfs.uploads", "path", "single", "deduplicated", "true");
        double chunked = count("pfs.uploads", "path", "chunked", "deduplicated", "false");

        String id = alice.uploadSmall("private-name.txt", bytes).path("id").asString();
        alice.uploadSmall("copy.txt", bytes);
        Chunks.upload(alice, "big.bin", com.performantfs.support.Bytes.random(1_500_000), alice.rootId());

        assertThat(count("pfs.uploads", "path", "single", "deduplicated", "false")).isEqualTo(fresh + 1);
        assertThat(count("pfs.uploads", "path", "single", "deduplicated", "true")).isEqualTo(dup + 1);
        assertThat(count("pfs.uploads", "path", "chunked", "deduplicated", "false")).isEqualTo(chunked + 1);
        assertThat(output.getOut()).contains("file stored node=" + id + " user=" + alice.id() + " bytes=" + bytes.length)
                .doesNotContain("private-name");
    }

    @Test
    void countsIndexingOutcomesAndReportsTheQueue() {
        var alice = newUser();
        double indexed = count("pfs.indexer.files", "status", "indexed");
        byte[] text = utf8("indexed and counted " + UUID.randomUUID());
        alice.uploadSmall("n.txt", text);
        eventually(() -> count("pfs.indexer.files", "status", "indexed"), n -> n > indexed);
        assertThat(meters.find("pfs.indexer.extract").timer().count()).isPositive();
        assertThat(meters.find("pfs.indexer.queue").gauge().value()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reportsStoredBytes() {
        newUser().uploadSmall("s.txt", utf8("stored " + UUID.randomUUID()));
        assertThat(meters.find("pfs.storage.bytes").gauge().value()).isPositive();
        assertThat(meters.find("pfs.storage.blobs").gauge().value()).isPositive();
    }

    @Test
    void countsFailedSignInsWithoutLoggingTheEmail(CapturedOutput output) {
        double failed = count("pfs.auth.signins", "outcome", "failed");
        String email = "who-" + UUID.randomUUID() + "@example.com";
        anonymous().post("/api/auth/login", Map.of("email", email, "password", "wrong password"));
        assertThat(count("pfs.auth.signins", "outcome", "failed")).isEqualTo(failed + 1);
        assertThat(output.getOut()).contains("sign-in failed").doesNotContain(email);
    }

    @Test
    void logsTreeChangesAfterTheyHappen(CapturedOutput output) {
        var alice = newUser();
        UUID a = alice.folder("a");
        UUID b = alice.folder("b");
        alice.api().post("/api/nodes/move", Map.of("ids", java.util.List.of(a), "parentId", b));
        alice.api().delete("/api/nodes/" + b);
        assertThat(output.getOut())
                .contains("moved 1 item(s) into " + b + " user=" + alice.id())
                .contains("trashed " + b + " user=" + alice.id());
    }
}
