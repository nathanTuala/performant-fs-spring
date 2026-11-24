package com.performantfs.maintenance;

import static com.performantfs.support.Bytes.random;
import static com.performantfs.support.Bytes.sha256;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.blobs.LocalBlobStore;
import com.performantfs.support.Chunks;
import com.performantfs.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class MaintenanceIT extends IntegrationTest {

    @Autowired Maintenance maintenance;
    @Autowired LocalBlobStore blobs;
    @Autowired JdbcClient db;
    @Value("${pfs.data-dir}") Path dataDir;

    private Integer refcount(String hash) {
        return db.sql("SELECT refcount FROM blobs WHERE hash = ?").param(hash).query(Integer.class).optional().orElse(null);
    }

    private static void trashAndPurge(TestUser user, JsonNode node) {
        String id = node.path("id").asString();
        assertThat(user.api().delete("/api/nodes/" + id).status()).isEqualTo(204);
        assertThat(user.api().delete("/api/trash/" + id).status()).isEqualTo(200);
    }

    @Test
    void freesABlobOnlyAfterItsLastReferenceIsPurged() {
        byte[] data = random(1500);
        var alice = newUser();
        var bob = newUser();
        JsonNode a = alice.uploadSmall("one.bin", data);
        JsonNode b = bob.uploadSmall("two.bin", data);
        String hash = a.path("hash").asString();
        Path file = blobs.blobPath(hash);
        assertThat(refcount(hash)).isEqualTo(2);

        trashAndPurge(alice, a);
        maintenance.runOnce();
        assertThat(refcount(hash)).isEqualTo(1);
        assertThat(file).exists(); // bob still has it

        trashAndPurge(bob, b);
        assertThat(refcount(hash)).isZero();
        assertThat(file).exists(); // gone from the tree, not yet from disk
        maintenance.runOnce();
        assertThat(refcount(hash)).isNull();
        assertThat(file).doesNotExist();
    }

    @Test
    void reUploadingBytesWhoseBlobWasJustCollectedStoresThemAgain() {
        var alice = newUser();
        byte[] data = random(777);
        trashAndPurge(alice, alice.uploadSmall("gc1.bin", data));
        maintenance.runOnce();
        JsonNode second = alice.uploadSmall("gc2.bin", data);
        byte[] downloaded = alice.api().get("/api/files/" + second.path("id").asString() + "/content").body();
        assertThat(sha256(downloaded)).isEqualTo(sha256(data));
    }

    @Test
    void expiresIdleUploadsReleasingTheirReservationAndBytes() {
        var alice = newUser();
        JsonNode session = Chunks.open(alice, "idle.bin", 5000, alice.rootId());
        String id = session.path("uploadId").asString();
        Path temp = dataDir.resolve("tmp").resolve(id);
        assertThat(temp).exists();
        db.sql("UPDATE upload_sessions SET expires_at = now() - interval '1 minute' WHERE id = ?").param(UUID.fromString(id)).update();

        assertThat(maintenance.runOnce().expiredUploads()).isGreaterThanOrEqualTo(1);
        assertThat(alice.api().get("/api/uploads/" + id).json().path("status").asString()).isEqualTo("cancelled");
        assertThat(alice.api().get("/api/stats").json().path("reservedBytes").asLong()).isZero();
        assertThat(temp).doesNotExist();
    }

    @Test
    void removesOldTempFilesThatNoSessionOwns() throws Exception {
        Path orphan = dataDir.resolve("tmp").resolve(UUID.randomUUID().toString());
        Files.write(orphan, new byte[10]);
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));
        Path fresh = dataDir.resolve("tmp").resolve(UUID.randomUUID().toString());
        Files.write(fresh, new byte[10]); // could be a single-request upload in flight

        maintenance.runOnce();
        assertThat(orphan).doesNotExist();
        assertThat(fresh).exists();
        Files.delete(fresh);
    }

    @Test
    void purgesItemsTrashedLongerAgoThanTheRetentionPeriod() {
        var alice = newUser();
        String id = alice.uploadSmall("old.txt", random(10)).path("id").asString();
        alice.api().delete("/api/nodes/" + id);
        db.sql("UPDATE nodes SET deleted_at = now() - interval '31 days' WHERE id = ?").param(UUID.fromString(id)).update();
        assertThat(maintenance.runOnce().trashPurged()).isGreaterThanOrEqualTo(1);
        assertThat(alice.api().get("/api/nodes/" + id).status()).isEqualTo(404);
    }
}
