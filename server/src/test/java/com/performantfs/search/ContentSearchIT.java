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
class ContentSearchIT extends IntegrationTest {

    @Autowired JdbcClient db;

    private TestUser alice;
    private UUID courses;

    @BeforeAll
    void seed() {
        alice = newUser();
        courses = alice.folder("Courses");
        upload(alice, "hw5.pdf", Pdfs.withPages("CS 101 homework five", "Problem 3: prove the halting problem is undecidable"), courses);
        upload(alice, "notes.md", utf8("# Lecture 7\nDijkstra's shortest path, then the knapsack problem."), alice.rootId());
        upload(alice, "problem set.txt", utf8("questions only"), alice.rootId());
        upload(alice, "photo.jpg", new byte[2048], alice.rootId());
    }

    private void upload(TestUser user, String name, byte[] bytes, UUID parent) {
        user.uploadSmall(name, bytes, parent);
        eventually(() -> db.sql("SELECT status FROM blob_text WHERE hash = ?").param(sha256(bytes)).query(String.class).single(),
                status -> !status.equals("pending"));
    }

    private List<String> names(TestUser user, String q) {
        var res = user.api().get("/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        assertThat(res.status()).as(res.text()).isEqualTo(200);
        return res.json().path("items").valueStream().map(n -> n.path("name").asString()).toList();
    }

    @Test
    void findsWordsInsidePdfsAndTextFiles() {
        assertThat(names(alice, "halting")).containsExactly("hw5.pdf");
        assertThat(names(alice, "dijkstra")).containsExactly("notes.md");
        assertThat(names(alice, "undecid")).as("prefixes work in contents too").containsExactly("hw5.pdf");
    }

    private JsonNode first(String q) {
        var res = alice.api().get("/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        return res.json().path("items").get(0);
    }

    @Test
    void showsAnExcerptAroundTheMatchWithTheWordsMarked() {
        JsonNode snippet = first("halting").path("snippet");
        String text = snippet.path("text").asString();
        assertThat(text).contains("prove the halting problem is undecidable");
        JsonNode span = snippet.path("highlights").get(0);
        assertThat(text.substring(span.path("start").asInt(), span.path("end").asInt())).isEqualToIgnoringCase("halting");
    }

    @Test
    void centresTheExcerptOnTheMatchInALongFile() {
        String filler = "lorem ipsum dolor sit amet ".repeat(400);
        upload(alice, "long.txt", utf8(filler + "the zanzibar clause " + filler), alice.rootId());
        String text = first("zanzibar").path("snippet").path("text").asString();
        assertThat(text).startsWith("…").endsWith("…").contains("zanzibar clause").hasSizeLessThan(400);
    }

    @Test
    void leavesTheExcerptOutWhenOnlyTheNameMatched() {
        assertThat(first("set").has("snippet")).isFalse();
    }

    @Test
    void ranksNameMatchesAboveContentsMatches() {
        assertThat(names(alice, "problem")).containsExactly("problem set.txt", "hw5.pdf", "notes.md");
    }

    @Test
    void needsEveryWordInTheNameOrEveryWordInTheContents() {
        assertThat(names(alice, "halting undecidable")).containsExactly("hw5.pdf");
        assertThat(names(alice, "halting dijkstra")).isEmpty();
    }

    @Test
    void matchesPhrasesAsWordsInOrder() {
        assertThat(names(alice, "\"halting problem\"")).containsExactly("hw5.pdf");
        assertThat(names(alice, "\"problem halting\"")).isEmpty();
    }

    @Test
    void appliesFiltersToContentsMatches() {
        assertThat(names(alice, "problem type:pdf")).containsExactly("hw5.pdf");
        assertThat(names(alice, "problem in:Courses")).containsExactly("hw5.pdf");
        assertThat(names(alice, "knapsack size:>1mb")).isEmpty();
    }

    @Test
    void neverShowsAnotherUsersCopyOfTheSameBytes() {
        // Identical bytes are one blob with one text row; only the owner's own nodes can match.
        var bob = newUser();
        byte[] secret = utf8("bob's diary " + UUID.randomUUID() + " zeppelin");
        upload(bob, "diary.txt", secret, bob.rootId());
        upload(alice, "found.txt", secret, alice.rootId());
        assertThat(names(alice, "zeppelin")).containsExactly("found.txt");
        assertThat(names(newUser(), "zeppelin")).isEmpty();
    }

    @Test
    void dropsTrashedFilesFromContentsResults() {
        byte[] text = utf8("ephemeral marmalade " + UUID.randomUUID());
        JsonNode node = alice.uploadSmall("trash me.txt", text);
        upload(alice, "keep.txt", utf8("unrelated"), alice.rootId());
        eventually(() -> names(alice, "marmalade"), n -> !n.isEmpty());
        assertThat(alice.api().delete("/api/nodes/" + node.path("id").asString()).status()).isEqualTo(204);
        assertThat(names(alice, "marmalade")).isEmpty();
    }
}
