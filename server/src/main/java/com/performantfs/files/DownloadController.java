package com.performantfs.files;

import com.performantfs.auth.AuthUser;
import com.performantfs.blobs.BlobStore;
import com.performantfs.http.ApiException;
import com.performantfs.http.ErrorCode;
import com.performantfs.nodes.NodeRepository;
import com.performantfs.nodes.NodeRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Streams file contents: whole, or one byte range, with caching and safe rendering. */
@RestController
class DownloadController {

    private static final String SANDBOX_CSP =
            "sandbox; default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'";
    // Browsers' built-in PDF viewers refuse to run inside a CSP sandbox.
    private static final String PDF_CSP = "default-src 'none'; object-src 'self'";

    private final NodeRepository nodes;
    private final BlobStore blobs;

    DownloadController(NodeRepository nodes, BlobStore blobs) {
        this.nodes = nodes;
        this.blobs = blobs;
    }

    @GetMapping("/api/files/{id}/content")
    void content(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable UUID id,
            @RequestParam(required = false) String download,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        NodeRow node = nodes.getLive(user.id(), id, false);
        if (node.isFolder() || node.blobHash() == null) throw ApiException.notFound("File");

        String hash = node.blobHash().strip();
        String etag = "\"" + hash + "\"";
        String mime = node.mime() == null ? "application/octet-stream" : node.mime();
        boolean forceDownload = download != null && !download.equals("0");
        boolean inline = !forceDownload && (MimeTypes.isSafeInline(mime) || MimeTypes.isTextLike(mime));
        // Scriptable or textual types are previewed as plain text, never rendered as documents.
        String servedType = inline && MimeTypes.isTextLike(mime) ? "text/plain; charset=utf-8"
                : inline ? mime : "application/octet-stream";

        response.setHeader(HttpHeaders.ETAG, etag);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        // Blobs are immutable: a given ETag always means the same bytes.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", mime.equals("application/pdf") ? PDF_CSP : SANDBOX_CSP);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, HttpFields.contentDisposition(inline, node.name()));

        if (HttpFields.etagMatches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        // If-Range: only honour Range while the client's cached copy is still current.
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        String rangeHeader = ifRange == null || ifRange.equals(etag) ? request.getHeader(HttpHeaders.RANGE) : null;
        ByteRange range = ByteRange.parse(rangeHeader, node.size());

        switch (range) {
            case ByteRange.Unsatisfiable u -> {
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + node.size());
                throw new ApiException(ErrorCode.RANGE_NOT_SATISFIABLE,
                        "Range is outside the file (" + node.size() + " bytes)");
            }
            case ByteRange.Part part -> {
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + part.start() + "-" + part.end() + "/" + node.size());
                send(response, servedType, hash, part.start(), part.length());
            }
            case ByteRange.Full f -> send(response, servedType, hash, 0, node.size());
        }
    }

    private void send(HttpServletResponse response, String type, String hash, long offset, long length) throws IOException {
        response.setContentType(type);
        response.setContentLengthLong(length);
        if (length == 0) return;
        try (InputStream in = blobs.openBlob(hash, offset); OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n == -1) break;
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }
}
