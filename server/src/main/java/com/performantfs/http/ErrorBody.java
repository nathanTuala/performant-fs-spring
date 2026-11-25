package com.performantfs.http;

import java.util.LinkedHashMap;
import java.util.Map;

/** The one error shape every endpoint uses: {@code {"error": {"code": "...", "message": "...", ...}}}. */
public record ErrorBody(Map<String, Object> error) {

    public static ErrorBody of(ErrorCode code, String message, Map<String, Object> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.putAll(details);
        return new ErrorBody(error);
    }

    public static ErrorBody of(ErrorCode code, String message) {
        return of(code, message, Map.of());
    }
}
