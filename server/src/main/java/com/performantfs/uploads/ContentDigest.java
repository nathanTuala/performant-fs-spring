package com.performantfs.uploads;

import com.performantfs.http.ApiException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads {@code Content-Digest: sha-256=:<base64>:} (RFC 9530). */
final class ContentDigest {

    private static final Pattern SHA256 = Pattern.compile("(?:^|,)\\s*sha-256=:([A-Za-z0-9+/]+=*):", Pattern.CASE_INSENSITIVE);

    private ContentDigest() {}

    /** The SHA-256 as hex, or null when the header is absent. */
    static String sha256Hex(String header) {
        if (header == null) return null;
        Matcher m = SHA256.matcher(header);
        if (!m.find()) throw ApiException.badRequest("Content-Digest must include sha-256=:<base64>:");
        byte[] bytes = Base64.getDecoder().decode(m.group(1));
        if (bytes.length != 32) throw ApiException.badRequest("Content-Digest sha-256 value has the wrong length");
        return HexFormat.of().formatHex(bytes);
    }
}
