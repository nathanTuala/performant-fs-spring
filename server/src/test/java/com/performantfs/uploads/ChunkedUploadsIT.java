package com.performantfs.uploads;

import static com.performantfs.support.Bytes.random;
import static com.performantfs.support.Bytes.sha256;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.Chunks;
import com.performantfs.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class ChunkedUploadsIT extends IntegrationTest {

    private static final int MiB = 1024 * 1024;

    @Autowired JdbcClient db;
    @Value("${pfs.data-dir}") Path dataDir;

    private byte[] download(TestUser user, JsonNode node) {
        var res = user.api().get("/api/files/" + node.path("id").asString() + "/content");
        assertThat(res.status()).isEqualTo(200);
        return res.body();
    }

    private static List<Integer> ints(JsonNode array) {
        return array.valueStream().map(JsonNode::asInt).toList();
    }

    @Test
    void assemblesChunksSentOutOfOrderAndVerifiesTheHash() {
        var alice = newUser();
        byte[] bytes = random(3 * MiB + 12345); // 4 chunks, the last one short
        JsonNode node = Chunks.upload(alice, "shuffled.bin", bytes, alice.rootId(), 3, 1, 0, 2);
        assertThat(node.path("size").asLong()).isEqualTo(bytes.length);
        assertThat(node.path("hash").asString()).isEqualTo(sha256(bytes));
        assertThat(sha256(download(alice, node))).isEqualTo(sha256(bytes));
    }

    @Test
    void resumesBySendingOnlyTheMissingChunks() {
        var alice = newUser();
        byte[] bytes = random(4 * MiB);
        JsonNode session = Chunks.open(alice, "resume.bin", bytes.length, alice.rootId());
        Chunks.put(alice, session, bytes, 0);
        Chunks.put(alice, session, bytes, 2);

        // "Connection dropped." The client asks what the server holds.
        JsonNode status = alice.api().get("/api/uploads/" + session.path("uploadId").asString()).json();
        assertThat(ints(status.path("received"))).containsExactly(0, 2);

        var early = Chunks.complete(alice, session);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.errorCode()).isEqualTo("upload_incomplete");
        assertThat(ints(early.json().path("error").path("missing"))).containsExactly(1, 3);

        for (int i : new int[] {1, 3}) assertThat(Chunks.put(alice, session, bytes, i).status()).isEqualTo(200);
        assertThat(Chunks.complete(alice, session).json().path("node").path("hash").asString()).isEqualTo(sha256(bytes));
    }

    @Test
    void acceptsParallelChunks() throws Exception {
        var alice = newUser();
        byte[] bytes = random(6 * MiB);
        JsonNode session = Chunks.open(alice, "parallel.bin", bytes.length, alice.rootId());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> sends = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int index = i;
                sends.add(pool.submit(() -> Chunks.put(alice, session, bytes, index).status()));
            }
            for (Future<Integer> f : sends) assertThat(f.get()).isEqualTo(200);
        }
        assertThat(Chunks.complete(alice, session).json().path("node").path("hash").asString()).isEqualTo(sha256(bytes));
    }

    @Test
    void rejectsACorruptedChunkAndForgetsAnEarlierGoodCopyOfIt() {
        var alice = newUser();
        byte[] bytes = random(2 * MiB);
        JsonNode session = Chunks.open(alice, "corrupt.bin", bytes.length, alice.rootId());
        Chunks.put(alice, session, bytes, 0);
        Chunks.put(alice, session, bytes, 1);

        // Re-send chunk 0 with a flipped byte but the digest of the good bytes.
        byte[] bad = bytes.clone();
        bad[10] ^= (byte) 0xff;
        var res = Chunks.put(alice, session, bad, 0, Arrays.copyOfRange(bytes, 0, MiB));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("digest_mismatch");

        JsonNode status = alice.api().get("/api/uploads/" + session.path("uploadId").asString()).json();
        assertThat(ints(status.path("received"))).containsExactly(1);
        Chunks.put(alice, session, bytes, 0);
        assertThat(Chunks.complete(alice, session).json().path("node").path("hash").asString()).isEqualTo(sha256(bytes));
    }

    @Test
    void rejectsAChunkWithTheWrongLengthOrIndex() {
        var alice = newUser();
        byte[] bytes = random(2 * MiB);
        JsonNode session = Chunks.open(alice, "short.bin", bytes.length, alice.rootId());
        String base = "/api/uploads/" + session.path("uploadId").asString() + "/chunks/";
        var wrongLength = alice.api().put(base + "0", Arrays.copyOf(bytes, 100));
        assertThat(wrongLength.status()).isEqualTo(400);
        assertThat(wrongLength.errorCode()).isEqualTo("chunk_invalid");
        assertThat(alice.api().put(base + "9", Arrays.copyOf(bytes, 100)).status()).isEqualTo(400);
    }

    @Test
    void completeIsIdempotentEvenWhenConcurrent() throws Exception {
        var alice = newUser();
        byte[] bytes = random(MiB + 1);
        JsonNode session = Chunks.open(alice, "twice.bin", bytes.length, alice.rootId());
        Chunks.put(alice, session, bytes, 0);
        Chunks.put(alice, session, bytes, 1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> complete = () -> Chunks.complete(alice, session).status();
            var a = pool.submit(complete);
            var b = pool.submit(complete);
            assertThat(List.of(a.get(), b.get())).containsOnly(200);
        }
        assertThat(Chunks.complete(alice, session).json().path("status").asString()).isEqualTo("done");
        JsonNode list = alice.children(alice.rootId(), "");
        assertThat(list.path("items").valueStream().filter(n -> n.path("name").asString().startsWith("twice"))).hasSize(1);
    }

    @Test
    void handlesEmptyFiles() {
        var alice = newUser();
        JsonNode node = Chunks.upload(alice, "empty.txt", new byte[0], alice.rootId());
        assertThat(node.path("size").asLong()).isZero();
        assertThat(node.path("hash").asString()).isEqualTo(sha256(new byte[0]));
        assertThat(download(alice, node)).isEmpty();
    }

    @Test
    void reservesSpaceAtInitSoParallelUploadsCannotOvershoot() throws Exception {
        var carol = newUser();
        db.sql("UPDATE users SET quota_bytes = ? WHERE id = ?").params(3L * MiB, carol.id()).update();
        List<Integer> statuses = new ArrayList<>();
        List<JsonNode> opened = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<com.performantfs.support.ApiClient.Response>> inits = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String name = "q" + i;
                inits.add(pool.submit(() -> carol.api().post("/api/uploads",
                        Map.of("parentId", carol.rootId(), "name", name, "size", 2 * MiB))));
            }
            for (var f : inits) {
                var res = f.get();
                statuses.add(res.status());
                if (res.status() == 201) opened.add(res.json());
            }
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 507, 507);
        assertThat(carol.api().get("/api/stats").json().path("reservedBytes").asLong()).isEqualTo(2L * MiB);

        String uploadId = opened.getFirst().path("uploadId").asString();
        assertThat(carol.api().delete("/api/uploads/" + uploadId).status()).isEqualTo(204);
        assertThat(carol.api().get("/api/stats").json().path("reservedBytes").asLong()).isZero();
        assertThat(Files.exists(dataDir.resolve("tmp").resolve(uploadId))).isFalse();
    }

    @Test
    void usersCannotSeeOrTouchEachOthersUploads() {
        var alice = newUser();
        var mallory = newUser();
        JsonNode session = Chunks.open(alice, "secret.bin", 10, alice.rootId());
        String id = session.path("uploadId").asString();
        assertThat(mallory.api().get("/api/uploads/" + id).status()).isEqualTo(404);
        assertThat(mallory.api().post("/api/uploads/" + id + "/complete", null).status()).isEqualTo(404);
        assertThat(mallory.api().delete("/api/uploads/" + id).status()).isEqualTo(404);
        assertThat(mallory.api().post("/api/uploads", Map.of("parentId", alice.rootId(), "name", "x", "size", 1)).status())
                .isEqualTo(404);
    }
}
