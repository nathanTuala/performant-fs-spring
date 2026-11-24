package com.performantfs.nodes;

import com.performantfs.http.ApiException;
import com.performantfs.http.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Operations on the folder tree, each in one transaction. */
@Service
public class NodeService {

    private final NodeRepository nodes;
    /** Runs one attempt inside a savepoint, so a name clash doesn't abort the whole transaction. */
    private final TransactionTemplate savepoint;

    NodeService(NodeRepository nodes, PlatformTransactionManager transactions) {
        this.nodes = nodes;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Transactional
    public NodeRow createFolder(UUID ownerId, UUID parentId, String rawName, ConflictPolicy policy) {
        String name = Names.normalize(rawName);
        nodes.lockTree(ownerId, true);
        nodes.getLiveFolder(ownerId, parentId, false);
        return nodes.insert(NewNode.folder(ownerId, parentId, name), policy);
    }

    /**
     * Renames and/or moves one node: a single-row update whatever the size of the subtree. A folder
     * may not move into itself or a descendant; the exclusive tree lock makes that check hold even
     * against a concurrent move in the other direction.
     */
    @Transactional
    public NodeRow move(UUID ownerId, UUID id, UUID newParentId, String newName, ConflictPolicy policy) {
        if (newParentId == null && newName == null) throw ApiException.badRequest("Nothing to change");
        nodes.lockTree(ownerId, false);
        NodeRow node = nodes.getLive(ownerId, id, true);
        if (node.parentId() == null) throw ApiException.badRequest("The root folder cannot be moved or renamed");

        UUID parentId = newParentId != null ? newParentId : node.parentId();
        String name = newName != null ? Names.normalize(newName) : node.name();
        if (!parentId.equals(node.parentId())) {
            nodes.getLiveFolder(ownerId, parentId, false);
            if (node.isFolder() && nodes.isAncestorOrSelf(node.id(), parentId)) {
                throw new ApiException(ErrorCode.CYCLE, "A folder cannot be moved into itself or one of its subfolders");
            }
        } else if (name.equals(node.name())) {
            return node; // nothing changes
        }
        return nodes.withUniqueName(parentId, name, policy, candidate -> attempt(() -> nodes.moveTo(node.id(), parentId, candidate)));
    }

    /** Moves several nodes, all or nothing: one bad item leaves every item where it was. */
    @Transactional
    public List<NodeRow> moveAll(UUID ownerId, List<UUID> ids, UUID parentId, ConflictPolicy policy) {
        return ids.stream().map(id -> move(ownerId, id, parentId, null, policy)).toList();
    }

    @Transactional
    public void trash(UUID ownerId, UUID id) {
        nodes.lockTree(ownerId, false);
        NodeRow node = nodes.getLive(ownerId, id, true);
        if (node.parentId() == null) throw ApiException.badRequest("The root folder cannot be deleted");
        nodes.trashSubtree(node.id(), UUID.randomUUID());
    }

    /**
     * Restores a trashed subtree: to its original folder if that is still live, otherwise to the
     * root. A name clash at the destination renames the restored item.
     */
    @Transactional
    public NodeRow restore(UUID ownerId, UUID rootId, UUID id) {
        nodes.lockTree(ownerId, false);
        NodeRow node = nodes.get(ownerId, id, true);
        if (node.isLive() || node.trashId() == null) throw ApiException.badRequest("Item is not in the trash");
        NodeRow parent = nodes.get(ownerId, node.parentId(), false);
        if (!parent.isLive() && node.trashId().equals(parent.trashId())) {
            throw ApiException.badRequest("Restore the folder this item was deleted with");
        }
        UUID target = parent.isLive() ? parent.id() : rootId;
        NodeRow restored = nodes.withUniqueName(target, node.name(), ConflictPolicy.RENAME,
                candidate -> attempt(() -> nodes.untrashTo(node.id(), target, candidate)));
        nodes.untrashGroup(node.trashId());
        return restored;
    }

    /** Permanently deletes trashed items ({@code ids}), or the whole trash when null. */
    @Transactional
    public int purge(UUID ownerId, List<UUID> ids) {
        nodes.lockTree(ownerId, false);
        return nodes.purge(ownerId, ids);
    }

    /** Runs a write in a savepoint; a sibling-name clash rolls back to it and yields empty. */
    private <T> Optional<T> attempt(Supplier<T> write) {
        try {
            return Optional.ofNullable(savepoint.execute(status -> write.get()));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }
}
