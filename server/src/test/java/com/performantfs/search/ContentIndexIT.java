package com.performantfs.search;

import static com.performantfs.support.Bytes.sha256;
import static com.performantfs.support.Bytes.utf8;
import static com.performantfs.support.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.blobs.BlobStore;
import com.performantfs.support.Chunks;
import com.performantfs.support.IntegrationTest;
import com.performantfs.support.Pdfs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ContentIndexIT extends IntegrationTest {

    @Autowired JdbcClient db;
    @Autowired ContentIndexer indexer;
    @Autowired BlobStore blobs;

    record Row(String status, String content, int attempts) {}

    private Row row(String hash) {
        return db.sql("SELECT status, content, attempts FROM blob_text WHERE hash = ?")
                .param(hash)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .optional()
                .orElse(null);
    }

    /** Waits for the indexer to finish with a file's bytes. */
    private Row indexed(byte[] bytes) {
        return eventually(() -> row(sha256(bytes)), r -> r != null && !r.status().equals("pending"));
    }

    @Test
    void indexesTextFilesSoonAfterUpload() {
        var alice = newUser();
        byte[] notes = utf8("Meeting notes " + UUID.randomUUID() + "\n\nBudget review for the lighthouse project");
        alice.uploadSmall("notes.md", notes);
        Row row = indexed(notes);
        assertThat(row.status()).isEqualTo("indexed");
        assertThat(row.content()).contains("lighthouse project");
    }

    @Test
    void indexesTheTextInsidePdfs() {
        var alice = newUser();
        byte[] pdf = Pdfs.withPages("Homework 5: the halting problem " + UUID.randomUUID());
        alice.uploadSmall("hw5.pdf", pdf);
        assertThat(indexed(pdf).content()).contains("halting problem");
    }

    @Test
    void marksFormatsItCannotReadInsteadOfRetrying() {
        var alice = newUser();
        byte[] binary = new byte[256];
        new java.util.Random().nextBytes(binary);
        binary[0] = 0;
        alice.uploadSmall("photo.jpg", binary);
        assertThat(indexed(binary).status()).isEqualTo("unsupported");

        byte[] locked = Pdfs.encrypted("pw", "secret " + UUID.randomUUID());
        alice.uploadSmall("locked.pdf", locked);
        Row row = indexed(locked);
        assertThat(row.status()).isEqualTo("failed");
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    void retakesAClaimLeftBehindByADeadWorker() {
        var alice = newUser();
        byte[] text = utf8("recovered after a crash " + UUID.randomUUID());
        alice.uploadSmall("crash.txt", text);
        indexed(text);
        // As if a worker had claimed it an hour ago and died.
        db.sql("UPDATE blob_text SET status = 'pending', content = NULL, claimed_at = now() - interval '1 hour' WHERE hash = ?")
                .param(sha256(text))
                .update();
        indexer.wake();
        assertThat(indexed(text).status()).isEqualTo("indexed");
    }

    @Test
    void givesUpOnAFileThatKeepsKillingWorkers() {
        var alice = newUser();
        byte[] text = utf8("poison " + UUID.randomUUID());
        alice.uploadSmall("poison.txt", text);
        indexed(text);
        db.sql("UPDATE blob_text SET status = 'pending', content = NULL, claimed_at = NULL, attempts = ? WHERE hash = ?")
                .params(ContentIndexer.MAX_ATTEMPTS, sha256(text))
                .update();
        indexer.wake();
        assertThat(indexed(text).status()).isEqualTo("failed");
    }

    @Test
    void readsIdenticalBytesOnce() {
        var alice = newUser();
        var bob = newUser();
        byte[] text = utf8("shared handout " + UUID.randomUUID());
        alice.uploadSmall("handout.txt", text);
        indexed(text);
        bob.uploadSmall("copy.txt", text);
        Row row = row(sha256(text));
        assertThat(row.status()).isEqualTo("indexed");
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    void indexesFilesThatArrivedInChunks() {
        var alice = newUser();
        // Over the 1 MB test chunk size, so this goes through the resumable protocol.
        String marker = "chunkyword" + UUID.randomUUID().toString().replace("-", "");
        byte[] text = utf8(marker + " " + "lorem ipsum dolor sit amet ".repeat(100_000));
        Chunks.upload(alice, "big.txt", text, alice.rootId());
        Row row = indexed(text);
        assertThat(row.status()).isEqualTo("indexed");
        assertThat(row.content()).startsWith(marker);
    }

    @Test
    void marksAFileWhoseBytesAreMissingAsFailedAndCarriesOn() throws Exception {
        var alice = newUser();
        byte[] lost = utf8("lost on disk " + UUID.randomUUID());
        byte[] fine = utf8("still fine " + UUID.randomUUID());
        alice.uploadSmall("lost.txt", lost);
        indexed(lost);
        blobs.deleteBlob(sha256(lost));
        db.sql("UPDATE blob_text SET status = 'pending', content = NULL, claimed_at = NULL, attempts = 0 WHERE hash = ?")
                .param(sha256(lost))
                .update();
        alice.uploadSmall("fine.txt", fine);
        assertThat(indexed(lost).status()).isEqualTo("failed");
        assertThat(indexed(fine).status()).isEqualTo("indexed");
    }

    @Test
    void forgetsTheTextWhenGarbageCollectionDeletesTheBlob() {
        var alice = newUser();
        byte[] text = utf8("short-lived " + UUID.randomUUID());
        alice.uploadSmall("tmp.txt", text);
        indexed(text);
        db.sql("UPDATE blobs SET refcount = 0 WHERE hash = ?").param(sha256(text)).update();
        db.sql("DELETE FROM nodes WHERE blob_hash = ?").param(sha256(text)).update();
        db.sql("DELETE FROM blobs WHERE hash = ?").param(sha256(text)).update();
        assertThat(row(sha256(text))).isNull();
    }
}
