package com.performantfs.nodes;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operations on the folder tree, each in one transaction. */
@Service
public class NodeService {

    private final NodeRepository nodes;

    NodeService(NodeRepository nodes) {
        this.nodes = nodes;
    }

    @Transactional
    public NodeRow createFolder(UUID ownerId, UUID parentId, String rawName, ConflictPolicy policy) {
        String name = Names.normalize(rawName);
        nodes.lockTree(ownerId, true);
        nodes.getLiveFolder(ownerId, parentId, false);
        return nodes.insert(NewNode.folder(ownerId, parentId, name), policy);
    }
}
