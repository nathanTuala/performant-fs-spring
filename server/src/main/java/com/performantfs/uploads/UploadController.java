package com.performantfs.uploads;

import com.performantfs.auth.AuthUser;
import com.performantfs.nodes.NodeDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Byte-carrying endpoints. They read the raw servlet input stream and pipe it straight to disk,
 * never into a request body object, so memory stays flat whatever the file size.
 */
@RestController
@RequestMapping("/api")
class UploadController {

    record InitRequest(@NotNull UUID parentId, @NotBlank @Size(max = 255) String name, @PositiveOrZero long size,
                       @Size(max = 255) String mime) {}

    private final UploadService uploads;
    private final ChunkedUploadService chunked;

    UploadController(UploadService uploads, ChunkedUploadService chunked) {
        this.uploads = uploads;
        this.chunked = chunked;
    }

    /** Opens a resumable upload session and reserves its size against the quota. */
    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    UploadSession init(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody InitRequest body) {
        return chunked.init(user.id(), body.parentId(), body.name(), body.size(), body.mime());
    }

    /** Which chunks the server holds, so a client resumes by sending only the rest. */
    @GetMapping("/uploads/{id}")
    UploadSession status(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return chunked.status(user.id(), id);
    }

    /** One chunk as the raw body, with an exact Content-Length and optionally a Content-Digest. */
    @PutMapping("/uploads/{id}/chunks/{index}")
    Map<String, Integer> putChunk(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable UUID id,
            @PathVariable @Min(0) int index,
            @RequestHeader(name = "Content-Digest", required = false) String digest,
            HttpServletRequest request) throws IOException {
        return chunked.putChunk(user.id(), id, index, request.getInputStream(), request.getContentLengthLong(), digest);
    }

    @PostMapping("/uploads/{id}/complete")
    UploadSession complete(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return chunked.complete(user.id(), id);
    }

    @DeleteMapping("/uploads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        chunked.cancel(user.id(), id);
    }

    /** Single-request upload for small files: POST /api/files?parentId=…&name=… with the bytes as the body. */
    @PostMapping("/files")
    @ResponseStatus(HttpStatus.CREATED)
    NodeDto uploadSmall(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam UUID parentId,
            @RequestParam @NotBlank @Size(max = 255) String name,
            @RequestParam(required = false) String mime,
            HttpServletRequest request) throws IOException {
        return uploads.uploadSmall(user.id(), parentId, name, mime, request.getInputStream(), request.getContentLengthLong());
    }
}
