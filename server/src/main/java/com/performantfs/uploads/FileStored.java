package com.performantfs.uploads;

import java.util.UUID;

/**
 * Published inside the transaction that stores a file. Listeners that act after commit see the
 * file's node and blob rows, and nothing at all if the upload rolled back.
 *
 * @param deduplicated the bytes were already stored, so this upload added no new blob
 * @param chunked      it came through the resumable protocol rather than in one request
 */
public record FileStored(String hash, UUID nodeId, UUID ownerId, long size, boolean deduplicated, boolean chunked) {}
