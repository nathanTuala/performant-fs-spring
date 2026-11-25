package com.performantfs.blobs;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Where file bytes live. The rest of the app only sees hashes and upload ids, so an S3 adapter
 * could replace this without touching the upload protocol or the metadata.
 */
public interface BlobStore {

    /** Creates a sparse temp file of {@code size} bytes for an upload. */
    void createTemp(UUID uploadId, long size) throws IOException;

    /**
     * Streams exactly {@code length} bytes from {@code source} into the temp file at {@code offset}.
     *
     * @return the SHA-256 (hex) of the bytes written
     * @throws LengthMismatchException if the stream carries more or fewer bytes
     */
    String writeTemp(UUID uploadId, long offset, long length, InputStream source) throws IOException;

    /** SHA-256 (hex) of the whole temp file. */
    String hashTemp(UUID uploadId) throws IOException;

    /**
     * Moves the temp file into content-addressed storage, or discards it when those bytes are
     * already stored. Callers must hold the blob's row lock.
     */
    Promotion promoteTemp(UUID uploadId, String hash) throws IOException;

    void deleteTemp(UUID uploadId) throws IOException;

    void deleteBlob(String hash) throws IOException;

    /** Opens a blob for reading, positioned at {@code offset}. */
    InputStream openBlob(String hash, long offset) throws IOException;

    /** Temp files on disk, for orphan cleanup. */
    List<Temp> listTemps() throws IOException;

    enum Promotion { STORED, DEDUPLICATED }

    record Temp(UUID id, java.time.Instant modified) {}
}
