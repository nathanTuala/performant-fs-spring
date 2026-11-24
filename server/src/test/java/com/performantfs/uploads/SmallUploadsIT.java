package com.performantfs.uploads;

import static com.performantfs.support.Bytes.random;
import static com.performantfs.support.Bytes.sha256;
import static com.performantfs.support.Bytes.utf8;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class SmallUploadsIT extends IntegrationTest {

    private static final int MiB = 1024 * 1024;

    @Autowired JdbcClient db;

    private int refcount(String hash) {
        return db.sql("SELECT refcount FROM blobs WHERE hash = ?").param(hash).query(Integer.class).optional().orElse(0);
    }

    @Test
    void storesASmallFileAndResolvesItsTypeFromTheExtension() {
        var alice = newUser();
        byte[] data = utf8("hello, world\n");
        JsonNode node = alice.uploadSmall("hello.txt", data);
        assertThat(node.path("name").asString()).isEqualTo("hello.txt");
        assertThat(node.path("kind").asString()).isEqualTo("file");
        assertThat(node.path("size").asLong()).isEqualTo(data.length);
        assertThat(node.path("mime").asString()).isEqualTo("text/plain");
        assertThat(node.path("hash").asString()).isEqualTo(sha256(data));
    }

    @Test
    void givesATakenNameANumberInsteadOfOverwriting() {
        var alice = newUser();
        UUID photos = alice.folder("photos");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 3; i++) names.add(alice.uploadSmall("photo.jpg", utf8("v" + i), photos).path("name").asString());
        assertThat(names).containsExactly("photo.jpg", "photo (1).jpg", "photo (2).jpg");
    }

    @Test
    void refusesBodiesOverTheFastPathLimit() {
        var alice = newUser();
        var res = alice.api().post("/api/files?parentId=" + alice.rootId() + "&name=big.bin", random(MiB + 1));
        assertThat(res.status()).isEqualTo(413);
    }

    @Test
    void refusesUploadsIntoSomeoneElsesFolder() {
        var alice = newUser();
        var mallory = newUser();
        var res = mallory.api().post("/api/files?parentId=" + alice.rootId() + "&name=x.txt", utf8("x"));
        assertThat(res.status()).isEqualTo(404);
    }

    @Test
    void enforcesTheQuota() {
        var carol = newUser();
        db.sql("UPDATE users SET quota_bytes = 1000 WHERE id = ?").param(carol.id()).update();
        assertThat(carol.api().post("/api/files?parentId=" + carol.rootId() + "&name=a", random(800)).status()).isEqualTo(201);
        var over = carol.api().post("/api/files?parentId=" + carol.rootId() + "&name=b", random(800));
        assertThat(over.status()).isEqualTo(507);
        assertThat(over.errorCode()).isEqualTo("quota_exceeded");
    }

    @Test
    void storesIdenticalContentOnceAcrossUsers() {
        byte[] data = random(1500);
        String a = newUser().uploadSmall("one.bin", data).path("hash").asString();
        String b = newUser().uploadSmall("two.bin", data).path("hash").asString();
        assertThat(a).isEqualTo(b);
        assertThat(refcount(a)).isEqualTo(2);
    }

    @Test
    void concurrentUploadsOfIdenticalNewContentAllSucceed() throws Exception {
        var alice = newUser();
        byte[] data = random(4096);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonNode>> uploads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String name = "same-" + i + ".bin";
                uploads.add(pool.submit(() -> alice.uploadSmall(name, data)));
            }
            for (Future<JsonNode> f : uploads) assertThat(f.get().path("hash").asString()).isEqualTo(sha256(data));
        }
        assertThat(refcount(sha256(data))).isEqualTo(8);
    }

    @Test
    void reportsUsage() {
        var dave = newUser();
        byte[] data = random(2000);
        dave.folder("docs");
        dave.uploadSmall("a.bin", data);
        dave.uploadSmall("b.bin", data);
        JsonNode usage = dave.api().get("/api/stats").json();
        assertThat(usage.path("fileBytes").asLong()).isEqualTo(4000);
        assertThat(usage.path("trashBytes").asLong()).isZero();
        assertThat(usage.path("usedBytes").asLong()).isEqualTo(4000);
        assertThat(usage.path("reservedBytes").asLong()).isZero();
        assertThat(usage.path("fileCount").asInt()).isEqualTo(2);
        assertThat(usage.path("folderCount").asInt()).isEqualTo(1);
        assertThat(usage.path("dedupSavedBytes").asLong()).isGreaterThanOrEqualTo(2000);
    }
}
