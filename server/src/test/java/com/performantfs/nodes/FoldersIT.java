package com.performantfs.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class FoldersIT extends IntegrationTest {

    @Autowired JdbcClient db;

    /** Inserts file rows directly; uploads arrive in a later PR. */
    private void seedFiles(TestUser user, UUID parentId, List<String> names) {
        db.sql("INSERT INTO blobs (hash, size, refcount) VALUES (repeat('0', 64), 0, 0) ON CONFLICT DO NOTHING").update();
        for (int i = 0; i < names.size(); i++) {
            db.sql("INSERT INTO nodes (owner_id, parent_id, is_folder, name, size, blob_hash) VALUES (?, ?, false, ?, ?, repeat('0', 64))")
                    .params(user.id(), parentId, names.get(i), i * 10L)
                    .update();
        }
    }

    @Test
    void createsNestedFoldersAndReportsTheirBreadcrumbPath() {
        var alice = newUser();
        UUID a = alice.folder("a");
        UUID b = alice.folder("b", a);
        JsonNode node = alice.api().get("/api/nodes/" + b).json();
        assertThat(node.path("name").asString()).isEqualTo("b");
        assertThat(node.path("kind").asString()).isEqualTo("folder");
        assertThat(node.path("path").valueStream().map(c -> c.path("name").asString())).containsExactly("My Files", "a");
    }

    @Test
    void rejectsACaseInsensitiveClashOrRenamesWhenAsked() {
        var alice = newUser();
        UUID parent = alice.folder("names");
        alice.folder("Reports", parent);
        var clash = alice.api().post("/api/folders", Map.of("parentId", parent, "name", "reports"));
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.errorCode()).isEqualTo("name_conflict");
        var renamed = alice.api().post("/api/folders", Map.of("parentId", parent, "name", "reports", "onConflict", "rename"));
        assertThat(renamed.status()).isEqualTo(201);
        assertThat(renamed.json().path("name").asString()).isEqualTo("reports (1)");
    }

    @Test
    void treatsNfdAndNfcSpellingsAsTheSameName() {
        var alice = newUser();
        UUID parent = alice.folder("unicode");
        alice.folder("café", parent);
        assertThat(alice.api().post("/api/folders", Map.of("parentId", parent, "name", "café")).status()).isEqualTo(409);
    }

    @Test
    void onlyWorksInsideTheUsersOwnTree() {
        var alice = newUser();
        var mallory = newUser();
        assertThat(mallory.api().post("/api/folders", Map.of("parentId", alice.rootId(), "name", "x")).status()).isEqualTo(404);
        assertThat(mallory.api().get("/api/nodes/" + alice.rootId() + "/children").status()).isEqualTo(404);
        assertThat(mallory.api().get("/api/nodes/" + alice.rootId()).status()).isEqualTo(404);
        assertThat(anonymous().get("/api/nodes/" + alice.rootId()).status()).isEqualTo(401);
    }

    @ParameterizedTest
    @CsvSource({"name, asc", "name, desc", "size, desc", "modified, asc"})
    void listsFoldersFirstAndPagesThroughEveryItemExactlyOnce(String sort, String dir) {
        var alice = newUser();
        UUID parent = alice.folder("listing");
        for (String name : List.of("f-b", "f-a", "f-c")) alice.folder(name, parent);
        seedFiles(alice, parent, IntStream.range(0, 12).mapToObj(i -> "file-%02d.txt".formatted(i)).toList());

        List<JsonNode> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = alice.children(parent, "?limit=4&sort=" + sort + "&dir=" + dir + (cursor == null ? "" : "&cursor=" + cursor));
            page.path("items").forEach(seen::add);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null);

        assertThat(seen).hasSize(15);
        assertThat(new HashSet<>(seen.stream().map(n -> n.path("id").asString()).toList())).hasSize(15);
        assertThat(seen.subList(0, 3)).allMatch(n -> n.path("kind").asString().equals("folder"));
        if (sort.equals("name")) {
            List<String> names = seen.subList(3, 15).stream().map(n -> n.path("name").asString()).toList();
            Comparator<String> order = dir.equals("asc") ? Comparator.naturalOrder() : Comparator.reverseOrder();
            assertThat(names).isSortedAccordingTo(order);
        }
    }

    @Autowired NodeRepository nodes;

    @Test
    void batchedPathsMatchSinglePathsForEveryId() {
        var alice = newUser();
        UUID a = alice.folder("paths-a");
        UUID b = alice.folder("paths-b", a);
        UUID c = alice.folder("paths-c", b);
        var paths = nodes.paths(List.of(alice.rootId(), a, c));
        for (UUID id : List.of(alice.rootId(), a, c)) assertThat(paths.get(id)).isEqualTo(nodes.path(id));
        assertThat(paths.get(c)).extracting(Crumb::name).containsExactly("My Files", "paths-a", "paths-b");
    }

    @Test
    void rejectsBadListingParameters() {
        var alice = newUser();
        String base = "/api/nodes/" + alice.rootId() + "/children";
        assertThat(alice.api().get(base + "?cursor=garbage").status()).isEqualTo(400);
        assertThat(alice.api().get(base + "?limit=0").status()).isEqualTo(400);
        assertThat(alice.api().get(base + "?sort=colour").status()).isEqualTo(400);
        assertThat(alice.api().get("/api/nodes/not-a-uuid").status()).isEqualTo(400);
    }

    @Test
    void timestampsAreIsoStrings() {
        var alice = newUser();
        JsonNode root = alice.api().get("/api/nodes/" + alice.rootId()).json();
        assertThat(root.path("createdAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(root.path("deletedAt").isNull()).isTrue();
        assertThat(root.path("parentId").isNull()).isTrue();
    }
}
