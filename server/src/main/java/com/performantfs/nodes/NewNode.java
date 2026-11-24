package com.performantfs.nodes;

import java.util.UUID;

/** A row about to be inserted; the parent must already be validated. */
public record NewNode(UUID ownerId, UUID parentId, boolean isFolder, String name, long size, String mime, String blobHash) {

    public static NewNode folder(UUID ownerId, UUID parentId, String name) {
        return new NewNode(ownerId, parentId, true, name, 0, null, null);
    }

    public NewNode withName(String newName) {
        return new NewNode(ownerId, parentId, isFolder, newName, size, mime, blobHash);
    }
}
