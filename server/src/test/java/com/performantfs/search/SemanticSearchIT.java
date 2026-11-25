package com.performantfs.search;

import static com.performantfs.support.Bytes.sha256;
import static com.performantfs.support.Bytes.utf8;
import static com.performantfs.support.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import com.performantfs.support.Pdfs;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticSearchIT extends IntegrationTest {

    @Autowired JdbcClient db;

    private TestUser alice;
    private UUID school;

    @BeforeAll
    void seed() {
        alice = newUser();
        school = alice.folder("School");
        upload(alice, "hw5.pdf", Pdfs.withPages(
                "Problem 3. Prove that the halting problem is undecidable: no Turing machine can decide "
                        + "whether an arbitrary program eventually stops on a given input."), school);
        upload(alice, "groceries.md", utf8("Shopping list: oat milk, sourdough bread, a wedge of parmesan and cheddar."), alice.rootId());
        upload(alice, "trip.txt", utf8("Itinerary: flight to Lisbon on Friday, hotel near the river, return Monday morning."), alice.rootId());
    }

    private void upload(TestUser user, String name, byte[] bytes, UUID parent) {
        user.uploadSmall(name, bytes, parent);
        eventually(() -> db.sql("SELECT status FROM blob_text WHERE hash = ?").param(sha256(bytes)).query(String.class).single(),
                status -> !status.equals("pending"));
    }

    private JsonNode search(TestUser user, String q) {
        var res = user.api().get("/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        assertThat(res.status()).as(res.text()).isEqualTo(200);
        return res.json();
    }

    private static List<String> names(JsonNode list) {
        return list.valueStream().map(n -> n.path("name").asString()).toList();
    }

    @Test
    void findsFilesByMeaningWhenNoWordsMatch() {
        JsonNode res = search(alice, "computability theory assignment");
        assertThat(names(res.path("items"))).isEmpty();
        assertThat(names(res.path("related"))).first().isEqualTo("hw5.pdf");
    }

    @Test
    void showsTheMatchingPassageWithItsLocation() {
        JsonNode hit = search(alice, "which cheese do I need to buy").path("related").get(0);
        assertThat(hit.path("name").asString()).isEqualTo("groceries.md");
        assertThat(hit.path("snippet").path("text").asString()).contains("parmesan");
        assertThat(hit.path("snippet").path("highlights").size()).isZero();
        assertThat(hit.path("path").get(0).path("name").asString()).isEqualTo("My Files");
    }

    @Test
    void leavesOutUnrelatedFiles() {
        assertThat(names(search(alice, "which cheese do I need to buy").path("related"))).doesNotContain("hw5.pdf", "trip.txt");
        assertThat(names(search(alice, "football world cup results").path("related"))).isEmpty();
    }

    @Test
    void doesNotRepeatWhatKeywordSearchAlreadyFound() {
        JsonNode res = search(alice, "parmesan");
        assertThat(names(res.path("items"))).containsExactly("groceries.md");
        assertThat(names(res.path("related"))).doesNotContain("groceries.md");
    }

    @Test
    void appliesTheSameFilters() {
        assertThat(names(search(alice, "computability theory assignment in:School").path("related"))).containsExactly("hw5.pdf");
        assertThat(names(search(alice, "computability theory assignment type:text").path("related"))).doesNotContain("hw5.pdf");
    }

    @Test
    void onlySearchesTheSearchersOwnFiles() {
        var bob = newUser();
        assertThat(search(bob, "computability theory assignment").path("related").size()).isZero();
        // Same bytes as Alice's file: one set of passages, but each user only finds their own copy.
        upload(bob, "my-hw.pdf", Pdfs.withPages(
                "Problem 3. Prove that the halting problem is undecidable: no Turing machine can decide "
                        + "whether an arbitrary program eventually stops on a given input."), bob.rootId());
        assertThat(names(search(bob, "computability theory assignment").path("related"))).containsExactly("my-hw.pdf");
    }

    @Test
    void dropsTrashedFiles() {
        var carol = newUser();
        byte[] notes = utf8("Notes on photosynthesis: plants turn sunlight, water and carbon dioxide into sugar. " + UUID.randomUUID());
        JsonNode node = carol.uploadSmall("bio.txt", notes);
        upload(carol, "other.txt", utf8("unrelated"), carol.rootId());
        eventually(() -> names(search(carol, "how do plants make food").path("related")), n -> n.contains("bio.txt"));
        assertThat(carol.api().delete("/api/nodes/" + node.path("id").asString()).status()).isEqualTo(204);
        assertThat(names(search(carol, "how do plants make food").path("related"))).doesNotContain("bio.txt");
    }

    @Test
    void sendsRelatedFilesWithTheFirstPageOnly() {
        // Three files, one per page.
        JsonNode first = alice.api().get("/api/search?q=type:file&limit=1").json();
        assertThat(first.has("related")).isTrue();
        String cursor = first.path("nextCursor").asString();
        assertThat(cursor).isNotBlank();
        JsonNode second = alice.api().get("/api/search?q=type:file&limit=1&cursor=" + cursor).json();
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.has("related")).isFalse();
    }
}
