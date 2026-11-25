package com.performantfs.blobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalBlobStoreTest {

    @TempDir Path dir;
    private LocalBlobStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new LocalBlobStore(dir);
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void assemblesChunksWrittenOutOfOrderAtTheirOffsets() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] data = random(300_000);
        store.createTemp(id, data.length);
        for (int i : new int[] {2, 0, 1}) {
            byte[] part = Arrays.copyOfRange(data, i * 100_000, (i + 1) * 100_000);
            assertThat(store.writeTemp(id, i * 100_000L, part.length, new ByteArrayInputStream(part))).isEqualTo(sha(part));
        }
        assertThat(store.hashTemp(id)).isEqualTo(sha(data));
    }

    @Test
    void refusesMoreOrFewerBytesThanDeclared() throws Exception {
        UUID id = UUID.randomUUID();
        store.createTemp(id, 10);
        assertThatThrownBy(() -> store.writeTemp(id, 0, 10, new ByteArrayInputStream(random(11))))
                .isInstanceOf(LengthMismatchException.class);
        assertThatThrownBy(() -> store.writeTemp(id, 0, 10, new ByteArrayInputStream(random(9))))
                .isInstanceOf(LengthMismatchException.class);
    }

    @Test
    void promotesToAShardedPathOrDropsTheTempWhenTheBlobExists() throws Exception {
        byte[] data = random(64);
        String hash = sha(data);
        UUID first = write(data);
        assertThat(store.promoteTemp(first, hash)).isEqualTo(BlobStore.Promotion.STORED);
        assertThat(store.blobPath(hash)).isEqualTo(dir.resolve("blobs").resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash));
        assertThat(Files.readAllBytes(store.blobPath(hash))).isEqualTo(data);

        UUID second = write(data);
        assertThat(store.promoteTemp(second, hash)).isEqualTo(BlobStore.Promotion.DEDUPLICATED);
        assertThat(store.listTemps()).extracting(BlobStore.Temp::id).doesNotContain(first, second);
    }

    @Test
    void readsFromAnOffset() throws Exception {
        byte[] data = random(1000);
        String hash = sha(data);
        store.promoteTemp(write(data), hash);
        try (var in = store.openBlob(hash, 900)) {
            assertThat(in.readAllBytes()).isEqualTo(Arrays.copyOfRange(data, 900, 1000));
        }
    }

    @Test
    void rejectsHashesThatCouldEscapeTheDataDirectory() {
        assertThatThrownBy(() -> store.blobPath("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    private UUID write(byte[] data) throws Exception {
        UUID id = UUID.randomUUID();
        store.createTemp(id, data.length);
        store.writeTemp(id, 0, data.length, new ByteArrayInputStream(data));
        return id;
    }
}
