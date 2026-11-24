package com.performantfs.nodes;

import static com.performantfs.support.Bytes.utf8;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.ApiClient;
import com.performantfs.support.Chunks;
import com.performantfs.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class TreeIT extends IntegrationTest {

    @Autowired JdbcClient db;

    private static JsonNode folders(TestUser user, UUID parent, String... paths) {
        var res = user.api().post("/api/folders/batch", Map.of("parentId", parent, "paths", List.of(paths)));
        assertThat(res.status()).isEqualTo(201);
        return res.json().path("folders");
    }

    private static UUID id(JsonNode folders, String path) {
        return UUID.fromString(folders.path(path).asString());
    }

    private static List<String> pathNames(TestUser user, UUID id) {
        return user.api().get("/api/nodes/" + id).json().path("path").valueStream().map(c -> c.path("name").asString()).toList();
    }

    // ------------------------------------------------------------------ folder trees

    @Test
    void createsADroppedFolderTreeInOneCall() {
        var alice = newUser();
        UUID drop = alice.folder("drop");
        JsonNode f = folders(alice, drop, "Album/2024/June", "Album/2023", "Notes");
        assertThat(f.propertyNames()).containsExactlyInAnyOrder("Album", "Album/2024", "Album/2024/June", "Album/2023", "Notes");
        assertThat(pathNames(alice, id(f, "Album/2024/June"))).containsExactly("My Files", "drop", "Album", "2024");

        // Dropping "Album" again creates "Album (1)" rather than merging into the existing one.
        JsonNode again = folders(alice, drop, "Album/x");
        assertThat(alice.api().get("/api/nodes/" + id(again, "Album")).json().path("name").asString()).isEqualTo("Album (1)");
    }

    // ------------------------------------------------------------------ move and rename

    @Test
    void movesAFolderWithItsWholeSubtree() {
        var alice = newUser();
        JsonNode f = folders(alice, alice.rootId(), "mv-src/a/b/c", "mv-dst");
        assertThat(alice.api().patch("/api/nodes/" + id(f, "mv-src/a"), Map.of("parentId", id(f, "mv-dst"))).status()).isEqualTo(200);
        assertThat(pathNames(alice, id(f, "mv-src/a/b/c"))).containsExactly("My Files", "mv-dst", "a", "b");
    }

    @Test
    void rejectsMovingAFolderIntoItselfOrADescendant() {
        var alice = newUser();
        JsonNode f = folders(alice, alice.rootId(), "cyc/a/b");
        for (UUID target : List.of(id(f, "cyc"), id(f, "cyc/a/b"))) {
            var res = alice.api().patch("/api/nodes/" + id(f, "cyc"), Map.of("parentId", target));
            assertThat(res.status()).isEqualTo(409);
            assertThat(res.errorCode()).isEqualTo("cycle");
        }
    }

    @Test
    void neverCreatesACycleUnderConcurrentCrossingMoves() throws Exception {
        var alice = newUser();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 10; round++) {
                UUID a = alice.folder("race-a-" + round);
                UUID b = alice.folder("race-b-" + round);
                var first = pool.submit(() -> alice.api().patch("/api/nodes/" + a, Map.of("parentId", b)).status());
                var second = pool.submit(() -> alice.api().patch("/api/nodes/" + b, Map.of("parentId", a)).status());
                assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
            }
        }
        // Every node must still reach the root.
        Map<String, Object> counts = db.sql("""
                        WITH RECURSIVE r AS (SELECT id FROM nodes WHERE id = ? UNION ALL
                                             SELECT n.id FROM nodes n JOIN r ON n.parent_id = r.id)
                        SELECT (SELECT count(*) FROM r) AS reachable, (SELECT count(*) FROM nodes WHERE owner_id = ?) AS total""")
                .params(alice.rootId(), alice.id())
                .query()
                .singleRow();
        assertThat(counts.get("reachable")).isEqualTo(counts.get("total"));
    }

    @Test
    void bulkMoveIsAllOrNothing() {
        var alice = newUser();
        UUID dst = alice.folder("bulk-dst");
        alice.folder("taken", dst);
        UUID x = alice.folder("bulk-x");
        UUID taken = alice.folder("taken");
        var res = alice.api().post("/api/nodes/move", Map.of("ids", List.of(x, taken), "parentId", dst));
        assertThat(res.status()).isEqualTo(409);
        assertThat(alice.api().get("/api/nodes/" + x).json().path("parentId").asString()).isEqualTo(alice.rootId().toString());
    }

    @Test
    void renamesFollowingTheNameRules() {
        var alice = newUser();
        String id = alice.uploadSmall("draft.txt", utf8("d")).path("id").asString();
        var renamed = alice.api().patch("/api/nodes/" + id, Map.of("name", "final.txt"));
        assertThat(renamed.json().path("name").asString()).isEqualTo("final.txt");
        assertThat(alice.api().patch("/api/nodes/" + id, Map.of("name", "a/b")).status()).isEqualTo(400);
        assertThat(alice.api().patch("/api/nodes/" + alice.rootId(), Map.of("name", "x")).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ trash

    @Test
    void trashesAndRestoresAWholeSubtree() {
        var alice = newUser();
        JsonNode f = folders(alice, alice.rootId(), "t/sub/deep");
        String file = alice.uploadSmall("inside.txt", utf8("hi"), id(f, "t/sub/deep")).path("id").asString();
        assertThat(alice.api().delete("/api/nodes/" + id(f, "t")).status()).isEqualTo(204);

        assertThat(alice.api().get("/api/files/" + file + "/content").status()).isEqualTo(404);
        List<String> trash = alice.api().get("/api/trash").json().path("items").valueStream().map(n -> n.path("id").asString()).toList();
        assertThat(trash).contains(id(f, "t").toString()).doesNotContain(file); // only the item deleted directly

        // A new "t" takes the name; restoring the old one renames it.
        alice.folder("t");
        var restored = alice.api().post("/api/nodes/" + id(f, "t") + "/restore", null);
        assertThat(restored.json().path("name").asString()).isEqualTo("t (1)");
        assertThat(alice.api().get("/api/files/" + file + "/content").status()).isEqualTo(200);
    }

    @Test
    void restoresToTheRootWhenTheOldFolderIsGone() {
        var alice = newUser();
        UUID parent = alice.folder("doomed");
        String file = alice.uploadSmall("orphan.txt", utf8("o"), parent).path("id").asString();
        alice.api().delete("/api/nodes/" + file);
        alice.api().delete("/api/nodes/" + parent);
        var restored = alice.api().post("/api/nodes/" + file + "/restore", null);
        assertThat(restored.json().path("parentId").asString()).isEqualTo(alice.rootId().toString());
    }

    @Test
    void emptyingTheTrashReleasesQuota() {
        var erin = newUser();
        String file = erin.uploadSmall("quota.bin", new byte[5000]).path("id").asString();
        assertThat(usage(erin).path("usedBytes").asLong()).isEqualTo(5000);
        erin.api().delete("/api/nodes/" + file);
        assertThat(usage(erin).path("usedBytes").asLong()).isEqualTo(5000); // Trash still counts toward the quota, as in Drive
        assertThat(usage(erin).path("fileBytes").asLong()).isZero();
        assertThat(usage(erin).path("trashBytes").asLong()).isEqualTo(5000);
        assertThat(erin.api().delete("/api/trash").json().path("deleted").asInt()).isEqualTo(1);
        assertThat(usage(erin).path("usedBytes").asLong()).isZero();
        assertThat(usage(erin).path("trashBytes").asLong()).isZero();
    }

    @Test
    void reportsFileAndTrashBytesAsThingsAreDeletedAndRestored() {
        var fay = newUser();
        UUID big = fay.folder("big");
        fay.uploadSmall("a.bin", new byte[3000], big);
        fay.uploadSmall("b.bin", new byte[2000], big);
        assertThat(usage(fay).path("fileBytes").asLong()).isEqualTo(5000);
        fay.api().delete("/api/nodes/" + big); // the whole subtree goes to Trash
        assertThat(usage(fay).path("fileBytes").asLong()).isZero();
        assertThat(usage(fay).path("trashBytes").asLong()).isEqualTo(5000);
        fay.api().post("/api/nodes/" + big + "/restore", null);
        assertThat(usage(fay).path("fileBytes").asLong()).isEqualTo(5000);
    }

    @Test
    void anUploadIntoAFolderTrashedMidUploadIsCancelled() {
        var alice = newUser();
        UUID parent = alice.folder("vanishing");
        JsonNode session = Chunks.open(alice, "late.bin", 10, parent);
        Chunks.put(alice, session, new byte[10], 0);
        alice.api().delete("/api/nodes/" + parent);
        assertThat(Chunks.complete(alice, session).status()).isEqualTo(404);
        JsonNode status = alice.api().get("/api/uploads/" + session.path("uploadId").asString()).json();
        assertThat(status.path("status").asString()).isEqualTo("cancelled");
        assertThat(usage(alice).path("reservedBytes").asLong()).isZero();
    }

    private static JsonNode usage(TestUser user) {
        ApiClient.Response res = user.api().get("/api/stats");
        assertThat(res.status()).isEqualTo(200);
        return res.json();
    }
}
