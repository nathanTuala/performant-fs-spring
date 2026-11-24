package com.performantfs.nodes;

import com.performantfs.auth.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    record CreateTree(@NotNull UUID parentId,
                      @NotEmpty @Size(max = FolderTreeService.MAX_FOLDERS) List<@NotBlank @Size(max = 4096) String> paths) {}

    record Change(UUID parentId, @Size(min = 1, max = 255) String name, ConflictPolicy onConflict) {}

    record BulkMove(@NotEmpty @Size(max = 1000) List<@NotNull UUID> ids, @NotNull UUID parentId, ConflictPolicy onConflict) {}

    private final NodeRepository nodes;
    private final NodeService service;
    private final FolderTreeService trees;

    NodeController(NodeRepository nodes, NodeService service, FolderTreeService trees) {
        this.nodes = nodes;
        this.service = service;
        this.trees = trees;
    }

    private static ConflictPolicy orError(ConflictPolicy policy) {
        return policy == null ? ConflictPolicy.ERROR : policy;
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
        return service.createFolder(user.id(), body.parentId(), body.name(), orError(body.onConflict())).toDto();
    }

    /** Creates a dropped folder tree in one call; returns path → folder id. */
    @PostMapping("/folders/batch")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Map<String, UUID>> createTree(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateTree body) {
        return Map.of("folders", trees.create(user.id(), body.parentId(), body.paths()));
    }

    /** Rename and/or move. */
    @PatchMapping("/nodes/{id}")
    NodeDto change(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id, @Valid @RequestBody Change body) {
        return service.move(user.id(), id, body.parentId(), body.name(), orError(body.onConflict())).toDto();
    }

    /** Moves several items into one folder, all or nothing. */
    @PostMapping("/nodes/move")
    Map<String, List<NodeDto>> moveAll(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody BulkMove body) {
        List<UUID> ids = body.ids().stream().distinct().toList();
        return Map.of("items", service.moveAll(user.id(), ids, body.parentId(), orError(body.onConflict())).stream()
                .map(NodeRow::toDto).toList());
    }

    /** Moves an item (and everything inside it) to the trash. */
    @DeleteMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void trash(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        service.trash(user.id(), id);
    }

    @PostMapping("/nodes/{id}/restore")
    NodeDto restore(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return service.restore(user.id(), user.rootId(), id).toDto();
    }
}
