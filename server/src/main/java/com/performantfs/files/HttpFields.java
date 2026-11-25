package com.performantfs.files;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Small helpers for download response headers. */
final class HttpFields {

    private HttpFields() {}

    /** True when an If-None-Match header lists our ETag, weakly or exactly, or "*". */
    static boolean etagMatches(String header, String etag) {
        if (header == null) return false;
        return Arrays.stream(header.split(","))
                .map(String::strip)
                .map(t -> t.startsWith("W/") ? t.substring(2) : t)
                .anyMatch(t -> t.equals("*") || t.equals(etag));
    }

    /** Content-Disposition with an ASCII fallback plus the exact UTF-8 name (RFC 6266 / RFC 8187). */
    static String contentDisposition(boolean inline, String filename) {
        String ascii = filename.replaceAll("[^\\x20-\\x7e]", "_").replaceAll("[\"\\\\]", "_");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20") // URLEncoder is for forms; RFC 8187 wants %20
                .replace("'", "%27").replace("(", "%28").replace(")", "%29").replace("*", "%2A");
        return (inline ? "inline" : "attachment") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
