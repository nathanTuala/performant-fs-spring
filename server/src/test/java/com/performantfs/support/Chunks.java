package com.performantfs.support;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/** Drives the chunked upload protocol from tests. */
public final class Chunks {

    private Chunks() {}

    public static JsonNode open(IntegrationTest.TestUser user, String name, long size, UUID parentId) {
        var res = user.api().post("/api/uploads", java.util.Map.of("parentId", parentId, "name", name, "size", size));
        if (res.status() != 201) throw new IllegalStateException("init failed: " + res.status() + " " + res.text());
        return res.json();
    }

    /** Sends chunk {@code index} of {@code bytes}, with a digest of {@code digestOf} (default: the chunk itself). */
    public static ApiClient.Response put(IntegrationTest.TestUser user, JsonNode session, byte[] bytes, int index, byte[] digestOf) {
        int chunkSize = session.path("chunkSize").asInt();
        byte[] chunk = Arrays.copyOfRange(bytes, index * chunkSize, Math.min(bytes.length, (index + 1) * chunkSize));
        String digest = Base64.getEncoder().encodeToString(sha(digestOf != null ? digestOf : chunk));
        return user.api().put("/api/uploads/" + session.path("uploadId").asString() + "/chunks/" + index, chunk,
                "content-digest", "sha-256=:" + digest + ":");
    }

    public static ApiClient.Response put(IntegrationTest.TestUser user, JsonNode session, byte[] bytes, int index) {
        return put(user, session, bytes, index, null);
    }

    public static ApiClient.Response complete(IntegrationTest.TestUser user, JsonNode session) {
        return user.api().post("/api/uploads/" + session.path("uploadId").asString() + "/complete", null);
    }

    /** A whole upload, chunks in {@code order} (default: in order); returns the created node. */
    public static JsonNode upload(IntegrationTest.TestUser user, String name, byte[] bytes, UUID parentId, int... order) {
        JsonNode session = open(user, name, bytes.length, parentId);
        int[] indexes = order.length > 0 ? order : IntStream.range(0, session.path("chunkCount").asInt()).toArray();
        for (int i : indexes) {
            var res = put(user, session, bytes, i);
            if (res.status() != 200) throw new IllegalStateException("chunk " + i + ": " + res.status() + " " + res.text());
        }
        var done = complete(user, session);
        if (done.status() != 200) throw new IllegalStateException("complete failed: " + done.status() + " " + done.text());
        return done.json().path("node");
    }

    private static byte[] sha(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
