package com.performantfs.demo;

import static com.performantfs.support.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.auth.AuthService;
import com.performantfs.support.ApiClient;
import com.performantfs.support.IntegrationTest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** The credentials in application.properties (and the README) must work and show the samples. */
class DemoAccountIT extends IntegrationTest {

    static final String EMAIL = "demo@performant-fs.dev";
    static final String PASSWORD = "try-the-demo-2026";

    @Autowired DemoAccount demo;
    @Autowired AuthService auth;

    private ApiClient signIn() {
        ApiClient client = anonymous();
        var res = client.post("/api/auth/login", Map.of("email", EMAIL, "password", PASSWORD));
        assertThat(res.status()).as(res.text()).isEqualTo(200);
        return client;
    }

    private List<String> topLevel(ApiClient client) {
        String root = client.get("/api/auth/me").json().path("rootId").asString();
        return client.get("/api/nodes/" + root + "/children").json().path("items").valueStream()
                .map(n -> n.path("name").asString()).toList();
    }

    private JsonNode search(ApiClient client, String q) {
        return client.get("/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)).json();
    }

    private static List<String> names(JsonNode list) {
        return list.valueStream().map(n -> n.path("name").asString()).toList();
    }

    @Test
    void theDocumentedCredentialsOpenAnAccountWithSampleFiles() {
        assertThat(topLevel(signIn())).containsExactly("Coursework", "Home", "Travel");
    }

    @Test
    void theSearchesSuggestedInTheReadmeWork() {
        ApiClient client = signIn();
        // The samples are indexed in the background; wait for the slowest stage (embedding).
        eventually(() -> names(search(client, "how do plants make food").path("related")),
                n -> n.contains("Biology lecture 7 - notes.md"));
        assertThat(search(client, "how do plants make food").path("items").size()).as("no file contains those words").isZero();
        assertThat(names(search(client, "quartrly").path("items"))).contains("Quarterly budget 2026.csv");
        assertThat(names(search(client, "halting type:pdf in:Coursework").path("items")))
                .containsExactly("CS 374 HW5 - computability.pdf");
        assertThat(names(search(client, "weekend getaway to Portugal").path("related"))).first().isEqualTo("Lisbon long weekend.txt");
        assertThat(search(client, "football world cup results").path("related").size()).isZero();
    }

    @Test
    void theSignInPageCanOfferTheCredentials() {
        JsonNode creds = anonymous().get("/api/auth/demo").json();
        assertThat(creds.path("email").asString()).isEqualTo(EMAIL);
        assertThat(creds.path("password").asString()).isEqualTo(PASSWORD);
    }

    @Test
    void restartingNeitherDuplicatesTheSamplesNorLosesThePassword() {
        var user = auth.find(EMAIL).orElseThrow();
        auth.setPassword(user.id(), "someone changed it");
        demo.ensure(); // what every startup runs
        demo.ensure();
        assertThat(topLevel(signIn())).hasSize(3);
    }
}
