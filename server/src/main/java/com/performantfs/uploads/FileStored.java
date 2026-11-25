package com.performantfs.uploads;

/**
 * Published inside the transaction that stores a file. Listeners that act after commit see the
 * file's node and blob rows, and nothing at all if the upload rolled back.
 */
public record FileStored(String hash) {}
