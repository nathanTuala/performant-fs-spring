package com.performantfs.observability;

import static com.performantfs.support.Bytes.sha256;
import static com.performantfs.support.Bytes.utf8;
import static com.performantfs.support.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import com.performantfs.support.RecordedObservations;
import com.performantfs.support.RecordedObservations.Recorded;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(RecordedObservations.class)
class SearchObservabilityIT extends IntegrationTest {

    @Autowired RecordedObservations.Recorder recorder;
    @Autowired MeterRegistry meters;
    @Autowired JdbcClient db;

    @Test
    void eachSearchStageIsASpanWithWhatItReturned() {
        var alice = newUser();
        byte[] notes = utf8("Notes on photosynthesis: plants turn sunlight, water and carbon dioxide into sugar and oxygen.");
        String id = alice.uploadSmall("biology.txt", notes).path("id").asString();
        eventually(() -> db.sql("SELECT status FROM blob_text WHERE hash = ?").param(sha256(notes)).query(String.class).single(),
                s -> !s.equals("pending"));
        recorder.clear();

        alice.api().get("/api/search?q=" + URLEncoder.encode("how do plants make food", StandardCharsets.UTF_8));

        Recorded keyword = recorder.named("pfs.search.keyword").getLast();
        assertThat(keyword.low()).containsEntry("page", "first").containsEntry("words", "true").containsEntry("filters", "false");
        assertThat(keyword.high()).containsEntry("results", "0").containsEntry("query", "how do plants make food");

        Recorded semantic = recorder.named("pfs.search.semantic").getLast();
        assertThat(semantic.high()).containsEntry("results", "1").containsEntry("result.ids", id);
        assertThat(recorder.named("pfs.search.vector").getLast().high().get("distances")).matches(id + "=0\\.\\d{3}");
        assertThat(recorder.named("pfs.search.embed")).isNotEmpty();
    }

    @Test
    void eachStageIsAlsoATimerTaggedOnlyWithBoundedValues() {
        var alice = newUser();
        alice.api().get("/api/search?q=anything");
        var timer = meters.find("pfs.search.keyword").tag("page", "first").tag("words", "true").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isPositive();
        // High-cardinality values (ids, text) must never become metric tags.
        assertThat(timer.getId().getTags()).noneMatch(t -> t.getKey().equals("query") || t.getKey().equals("result.ids"));
    }
}
