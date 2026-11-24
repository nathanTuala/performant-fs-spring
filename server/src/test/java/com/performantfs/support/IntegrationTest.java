package com.performantfs.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for end-to-end tests: the real server on a random port, backed by a real Postgres
 * (Testcontainers), exercised over HTTP. Every test class shares one context and one database;
 * tests stay independent by working as their own freshly registered users.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @LocalServerPort
    private int port;

    /** Blobs go to a throwaway directory; small chunks keep multi-chunk tests fast. */
    @DynamicPropertySource
    static void testSettings(DynamicPropertyRegistry registry) {
        registry.add("pfs.data-dir", () -> tempDataDir().toString());
        registry.add("pfs.chunk-size", () -> "1MB");
        registry.add("pfs.fast-path-max", () -> "1MB");
    }

    private static Path dataDir;

    private static synchronized Path tempDataDir() {
        if (dataDir == null) {
            try {
                dataDir = Files.createTempDirectory("pfs-test-");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return dataDir;
    }

    /** A client with no session. */
    protected ApiClient anonymous() {
        return new ApiClient("http://localhost:" + port);
    }

    /** A freshly registered, signed-in user. */
    protected TestUser newUser() {
        ApiClient client = anonymous();
        var res = client.post("/api/auth/register",
                Map.of("email", "u" + UUID.randomUUID() + "@test.dev", "password", "correct horse battery"));
        if (res.status() != 201) throw new IllegalStateException("register failed: " + res.status() + " " + res.text());
        var body = res.json();
        return new TestUser(client, UUID.fromString(body.path("id").asString()), UUID.fromString(body.path("rootId").asString()));
    }

    /** A signed-in client plus the ids tests usually need. */
    public record TestUser(ApiClient api, UUID id, UUID rootId) {

        /** Creates a folder and returns its id. */
        public UUID folder(String name, UUID parentId) {
            var res = api.post("/api/folders", Map.of("parentId", parentId, "name", name));
            if (res.status() != 201) throw new IllegalStateException("create folder failed: " + res.status() + " " + res.text());
            return UUID.fromString(res.json().path("id").asString());
        }

        public UUID folder(String name) {
            return folder(name, rootId);
        }

        /** GET /api/nodes/{folder}/children with an optional query string (e.g. "?limit=4"). */
        public tools.jackson.databind.JsonNode children(UUID folderId, String query) {
            var res = api.get("/api/nodes/" + folderId + "/children" + query);
            if (res.status() != 200) throw new IllegalStateException("list failed: " + res.status() + " " + res.text());
            return res.json();
        }
    }
}
