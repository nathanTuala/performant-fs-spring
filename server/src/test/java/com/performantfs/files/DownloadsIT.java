package com.performantfs.files;

import static com.performantfs.support.Bytes.random;
import static com.performantfs.support.Bytes.utf8;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.ApiClient;
import com.performantfs.support.IntegrationTest;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DownloadsIT extends IntegrationTest {

    private final byte[] content = random(10_000);
    private TestUser alice;
    private JsonNode file;

    @BeforeEach
    void upload() {
        alice = newUser();
        file = alice.uploadSmall("data.bin", content);
    }

    private ApiClient.Response get(String query, String... headers) {
        return alice.api().get("/api/files/" + file.path("id").asString() + "/content" + query, headers);
    }

    @Test
    void servesTheWholeFileWithAnEtag() {
        var res = get("");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.header("etag")).isEqualTo("\"" + file.path("hash").asString() + "\"");
        assertThat(res.header("accept-ranges")).isEqualTo("bytes");
        assertThat(res.header("content-length")).isEqualTo("10000");
        assertThat(res.body()).isEqualTo(content);
    }

    @Test
    void cachesImmutablyDespiteSpringSecurityDefaults() {
        assertThat(get("").header("cache-control")).isEqualTo("private, max-age=31536000, immutable");
    }

    @ParameterizedTest
    @CsvSource({"bytes=0-99, 0, 99", "bytes=9000-, 9000, 9999", "bytes=-500, 9500, 9999"})
    void servesARangeAs206(String range, int start, int end) {
        var res = get("", "range", range);
        assertThat(res.status()).isEqualTo(206);
        assertThat(res.header("content-range")).isEqualTo("bytes " + start + "-" + end + "/10000");
        assertThat(res.body()).isEqualTo(Arrays.copyOfRange(content, start, end + 1));
    }

    @Test
    void answers416ForARangePastTheEnd() {
        var res = get("", "range", "bytes=20000-");
        assertThat(res.status()).isEqualTo(416);
        assertThat(res.header("content-range")).isEqualTo("bytes */10000");
        assertThat(res.errorCode()).isEqualTo("range_not_satisfiable");
    }

    @Test
    void answers304WhenTheEtagMatches() {
        assertThat(get("", "if-none-match", "\"" + file.path("hash").asString() + "\"").status()).isEqualTo(304);
    }

    @Test
    void ignoresRangeWhenIfRangeIsStale() {
        assertThat(get("", "range", "bytes=0-9", "if-range", "\"stale\"").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page.html", "logo.svg"})
    void neverRendersUploadedHtmlOrSvgAsADocument(String name) {
        JsonNode node = alice.uploadSmall(name, utf8("<script>alert(1)</script>"));
        var res = alice.api().get("/api/files/" + node.path("id").asString() + "/content");
        assertThat(res.header("content-type")).isEqualTo("text/plain;charset=utf-8");
        assertThat(res.header("x-content-type-options")).isEqualTo("nosniff");
        assertThat(res.header("content-security-policy")).contains("sandbox");
    }

    @Test
    void downloadForcesAnAttachment() {
        assertThat(get("?download=1").header("content-disposition")).startsWith("attachment; filename=\"data.bin\"");
    }

    @Test
    void otherUsersAndFoldersAre404() {
        var mallory = newUser();
        assertThat(mallory.api().get("/api/files/" + file.path("id").asString() + "/content").status()).isEqualTo(404);
        assertThat(alice.api().get("/api/files/" + alice.rootId() + "/content").status()).isEqualTo(404);
    }

    @Test
    void servesEmptyFiles() {
        JsonNode empty = alice.uploadSmall("empty.txt", new byte[0]);
        var res = alice.api().get("/api/files/" + empty.path("id").asString() + "/content");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body()).isEmpty();
    }
}
