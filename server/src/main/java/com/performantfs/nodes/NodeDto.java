package com.performantfs.nodes;

import java.time.Instant;
import java.util.UUID;

/**
 * A node as the API returns it, the same shape as the Node version's NodeDto.
 *
 * @param kind "file" or "folder"
 * @param hash SHA-256 of the content (also the download ETag); null for folders
 */
public record NodeDto(
        UUID id,
        UUID parentId,
        String kind,
        String name,
        long size,
        String mime,
        String hash,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {}
