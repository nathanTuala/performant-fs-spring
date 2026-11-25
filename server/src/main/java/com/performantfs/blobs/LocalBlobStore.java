package com.performantfs.blobs;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Blobs on local disk: {@code blobs/ab/cd/<sha256>} (two levels of 256 shards, so no directory
 * grows huge) and {@code tmp/<uploadId>} for uploads in progress. Both live under one root, so a
 * finished upload is moved into place with an atomic rename.
 */
public class LocalBlobStore implements BlobStore {

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final int BUFFER = 64 * 1024;

    private final Path tmpDir;
    private final Path blobDir;

    public LocalBlobStore(Path dataDir) throws IOException {
        this.tmpDir = dataDir.resolve("tmp");
        this.blobDir = dataDir.resolve("blobs");
        Files.createDirectories(tmpDir);
        Files.createDirectories(blobDir);
    }

    public Path blobPath(String hash) {
        if (!HASH.matcher(hash).matches()) throw new IllegalArgumentException("invalid blob hash: " + hash);
        return blobDir.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash);
    }

    private Path tempPath(UUID uploadId) {
        return tmpDir.resolve(uploadId.toString()); // a UUID can't contain path separators
    }

    @Override
    public void createTemp(UUID uploadId, long size) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(tempPath(uploadId).toFile(), "rw")) {
            file.setLength(size); // sparse: no blocks are allocated until chunks arrive
        }
    }

    @Override
    public String writeTemp(UUID uploadId, long offset, long length, InputStream source) throws IOException {
        MessageDigest sha = sha256();
        // Each write opens its own channel and writes at its offset, so chunks can land in any
        // order and in parallel without coordinating.
        try (FileChannel channel = FileChannel.open(tempPath(uploadId), StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER];
            long written = 0;
            int n;
            while ((n = source.read(buffer)) != -1) {
                if (written + n > length) throw new LengthMismatchException(length, written + n);
                sha.update(buffer, 0, n);
                ByteBuffer slice = ByteBuffer.wrap(buffer, 0, n);
                while (slice.hasRemaining()) channel.write(slice, offset + written + (n - slice.remaining()));
                written += n;
            }
            if (written != length) throw new LengthMismatchException(length, written);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    @Override
    public String hashTemp(UUID uploadId) throws IOException {
        MessageDigest sha = sha256();
        try (InputStream in = Files.newInputStream(tempPath(uploadId))) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) sha.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    @Override
    public Promotion promoteTemp(UUID uploadId, String hash) throws IOException {
        Path target = blobPath(hash);
        if (Files.exists(target)) {
            deleteTemp(uploadId);
            return Promotion.DEDUPLICATED;
        }
        Files.createDirectories(target.getParent());
        Files.move(tempPath(uploadId), target, StandardCopyOption.ATOMIC_MOVE);
        return Promotion.STORED;
    }

    @Override
    public void deleteTemp(UUID uploadId) throws IOException {
        Files.deleteIfExists(tempPath(uploadId));
    }

    @Override
    public void deleteBlob(String hash) throws IOException {
        Files.deleteIfExists(blobPath(hash));
    }

    @Override
    public InputStream openBlob(String hash, long offset) throws IOException {
        FileChannel channel = FileChannel.open(blobPath(hash), StandardOpenOption.READ);
        channel.position(offset);
        return Channels.newInputStream(channel);
    }

    @Override
    public List<Temp> listTemps() throws IOException {
        List<Temp> temps = new ArrayList<>();
        try (Stream<Path> files = Files.list(tmpDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                UUID id;
                try {
                    id = UUID.fromString(file.getFileName().toString());
                } catch (IllegalArgumentException e) {
                    continue; // not ours
                }
                try {
                    temps.add(new Temp(id, Files.getLastModifiedTime(file).toInstant()));
                } catch (NoSuchFileException e) {
                    // finished or cancelled while we were listing
                }
            }
        }
        return temps;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
