package com.performantfs.nodes;

import com.performantfs.http.ApiException;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where a listing page ended: which segment (folders, then files), the last sort key as Postgres
 * text (so timestamps keep their microseconds), and the last id. Opaque base64url JSON on the wire.
 */
record ListingCursor(boolean f, String k, UUID id) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(this));
    }

    static ListingCursor decode(String raw) {
        try {
            ListingCursor c = JSON.readValue(Base64.getUrlDecoder().decode(raw), ListingCursor.class);
            if (c.k() != null && c.id() != null) return c;
        } catch (IllegalArgumentException | JacksonException e) {
            // fall through
        }
        throw ApiException.badRequest("Invalid cursor");
    }
}
