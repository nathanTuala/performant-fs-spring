package com.performantfs.nodes;

import com.performantfs.auth.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class NodeController {

    record CreateFolder(@NotNull UUID parentId, @NotBlank @Size(max = 255) String name, ConflictPolicy onConflict) {}

    private final NodeRepository nodes;
    private final NodeService service;

    NodeController(NodeRepository nodes, NodeService service) {
        this.nodes = nodes;
        this.service = service;
    }

    /** A node with its breadcrumb path. */
    @GetMapping("/nodes/{id}")
    NodeWithPath get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        NodeRow node = nodes.get(user.id(), id, false);
        return new NodeWithPath(node.toDto(), nodes.path(node.id()));
    }

    /** One keyset page of a folder's contents, folders first. */
    @GetMapping("/nodes/{id}/children")
    Page<NodeDto> children(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String dir) {
        NodeRow folder = nodes.getLiveFolder(user.id(), id, false);
        return nodes.listChildren(folder.id(), Sort.parse(sort, dir), limit, cursor);
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    NodeDto createFolder(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateFolder body) {
        ConflictPolicy policy = body.onConflict() == null ? ConflictPolicy.ERROR : body.onConflict();
        return service.createFolder(user.id(), body.parentId(), body.name(), policy).toDto();
    }
}
