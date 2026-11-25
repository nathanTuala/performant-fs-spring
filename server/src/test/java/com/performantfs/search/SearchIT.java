package com.performantfs.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS) // one seeded user for the whole class
class SearchIT extends IntegrationTest {

    private TestUser alice;

    @BeforeAll
    void seed() {
        alice = newUser();
        JsonNode f = alice.api().post("/api/folders/batch",
                Map.of("parentId", alice.rootId(), "paths", List.of("Work/Reports", "Tax returns/2025", "Photos"))).json().path("folders");
        UUID reports = UUID.fromString(f.path("Work/Reports").asString());
        UUID y2025 = UUID.fromString(f.path("Tax returns/2025").asString());
        UUID tax = UUID.fromString(f.path("Tax returns").asString());
        UUID photos = UUID.fromString(f.path("Photos").asString());
        alice.uploadSmall("Q3 quarterly report final.xlsx", new byte[5000], reports);
        alice.uploadSmall("quarterly_summary.pdf", new byte[20_000], reports);
        alice.uploadSmall("invoice-march.pdf", new byte[300], y2025);
        alice.uploadSmall("invoice-april.pdf", new byte[400], tax);
        alice.uploadSmall("beach.jpg", new byte[900_000], photos);
        alice.uploadSmall("invoice template.docx", new byte[100]);
        alice.uploadSmall("readme.md", new byte[10]);
    }

    private JsonNode search(TestUser user, String q, String extra) {
        var res = user.api().get("/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + extra);
        assertThat(res.status()).as(res.text()).isEqualTo(200);
        return res.json();
    }

    private List<String> names(String q) {
        return search(alice, q, "").path("items").valueStream().map(n -> n.path("name").asString()).toList();
    }

    @Test
    void findsWholeWordsIncludingInsideSnakeCaseAndDottedNames() {
        assertThat(names("summary")).containsExactly("quarterly_summary.pdf");
        assertThat(names("pdf invoice")).contains("invoice-march.pdf", "invoice-april.pdf");
    }

    @Test
    void matchesWordPrefixesAsTypedMidWord() {
        assertThat(names("summ")).containsExactly("quarterly_summary.pdf");
        assertThat(names("invo mar").getFirst()).isEqualTo("invoice-march.pdf");
    }

    @Test
    void copesWithInputThatHasNoWords() {
        assertThat(names("/")).isEmpty();
        assertThat(names("&|!:*")).isEmpty();
    }

    @Test
    void matchesTyposFuzzily() {
        assertThat(names("quartrly")).contains("Q3 quarterly report final.xlsx", "quarterly_summary.pdf");
        assertThat(names("reprt")).contains("Q3 quarterly report final.xlsx");
        assertThat(names("reedme")).containsExactly("readme.md"); // one-letter substitution in a short word
        assertThat(names("zebra")).isEmpty();
    }

    @Test
    void matchesQuotedPhrasesAsSubstrings() {
        assertThat(names("\"report fin\"")).containsExactly("Q3 quarterly report final.xlsx");
        assertThat(names("\"100%\"")).isEmpty(); // LIKE wildcards in a phrase are literal
    }

    @Test
    void filtersByTypeSizeAndDate() {
        assertThat(names("type:image")).containsExactly("beach.jpg");
        assertThat(names("type:folder")).containsExactlyInAnyOrder("2025", "Photos", "Reports", "Tax returns", "Work");
        assertThat(names("invoice type:pdf")).containsExactlyInAnyOrder("invoice-april.pdf", "invoice-march.pdf");
        assertThat(names("size:>500kb")).containsExactly("beach.jpg");
        assertThat(names("modified:<2000-01-01")).isEmpty();
        assertThat(names("type:pdf modified:>=2000-01-01")).hasSize(3);
    }

    @Test
    void scopesToAFolderSubtreeWithIn() {
        assertThat(names("invoice in:\"Tax returns\"")).containsExactlyInAnyOrder("invoice-april.pdf", "invoice-march.pdf");
        assertThat(names("invoice in:2025")).containsExactly("invoice-march.pdf");
        assertThat(names("invoice in:\"No such folder\"")).isEmpty();
    }

    @Test
    void returnsEachResultWithItsBreadcrumbPath() {
        JsonNode hit = search(alice, "invoice-march", "").path("items").get(0);
        assertThat(hit.path("path").valueStream().map(c -> c.path("name").asString())).containsExactly("My Files", "Tax returns", "2025");
    }

    @Test
    void excludesTrashedItemsAndOtherUsers() {
        var bob = newUser();
        String id = bob.uploadSmall("ephemeral-note.txt", new byte[1]).path("id").asString();
        assertThat(search(bob, "ephemeral", "").path("items")).hasSize(1);
        bob.api().delete("/api/nodes/" + id);
        assertThat(search(bob, "ephemeral", "").path("items")).isEmpty();
        assertThat(search(bob, "invoice", "").path("items")).isEmpty();
    }

    @Test
    void pagesThroughRankedResultsExactlyOnce() {
        var carol = newUser();
        for (int i = 0; i < 7; i++) carol.uploadSmall("paged-" + i + ".txt", new byte[i + 1]);
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = search(carol, "paged", "&limit=3" + (cursor == null ? "" : "&cursor=" + cursor));
            page.path("items").forEach(n -> seen.add(n.path("name").asString()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).containsExactlyInAnyOrder("paged-0.txt", "paged-1.txt", "paged-2.txt", "paged-3.txt",
                "paged-4.txt", "paged-5.txt", "paged-6.txt");
    }

    @Test
    void answers400WithAHintForABadFilterAndNothingForAnEmptyQuery() {
        var res = alice.api().get("/api/search?q=size:huge");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.json().path("error").path("message").asString()).contains("size:>10mb");
        assertThat(search(alice, "  ", "").path("items")).isEmpty();
    }

    @Test
    void prefixQueriesDropAnythingToTsqueryCouldMisread() {
        assertThat(SearchRepository.prefixQuery("quart rep")).isEqualTo("quart:* & rep:*");
        assertThat(SearchRepository.prefixQuery("q3_report.pdf")).isEqualTo("q3:* & report:* & pdf:*");
        assertThat(SearchRepository.prefixQuery("it's & | ! ( ) :*")).isEqualTo("it:* & s:*");
        assertThat(SearchRepository.prefixQuery("/")).isEmpty();
        assertThat(SearchRepository.prefixQuery("Café")).isEqualTo("café:*");
    }
}
