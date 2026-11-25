package com.performantfs.nodes;

import com.performantfs.auth.AuthUser;
import com.performantfs.http.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trash")
class TrashController {

    private static final Logger log = LoggerFactory.getLogger(TrashController.class);

    private final NodeRepository nodes;
    private final NodeService service;

    TrashController(NodeRepository nodes, NodeService service) {
        this.nodes = nodes;
        this.service = service;
    }

    @GetMapping
    Map<String, List<NodeDto>> list(@AuthenticationPrincipal AuthUser user) {
        return Map.of("items", nodes.listTrash(user.id()));
    }

    /** Empties the trash. */
    @DeleteMapping
    Map<String, Integer> empty(@AuthenticationPrincipal AuthUser user) {
        int deleted = service.purge(user.id(), null);
        log.info("emptied trash: {} item(s) deleted forever user={}", deleted, user.id());
        return Map.of("deleted", deleted);
    }

    /** Deletes one trashed item forever. */
    @DeleteMapping("/{id}")
    Map<String, Integer> deleteForever(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        int deleted = service.purge(user.id(), List.of(id));
        if (deleted == 0) throw ApiException.notFound("Item");
        log.info("deleted {} forever ({} item(s)) user={}", id, deleted, user.id());
        return Map.of("deleted", deleted);
    }
}
