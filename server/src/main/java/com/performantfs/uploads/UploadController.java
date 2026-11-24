package com.performantfs.uploads;

import com.performantfs.auth.AuthUser;
import com.performantfs.nodes.NodeDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final UploadService uploads;

    UploadController(UploadService uploads) {
        this.uploads = uploads;
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
