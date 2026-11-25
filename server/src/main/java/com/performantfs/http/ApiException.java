package com.performantfs.http;

import java.util.Map;

/**
 * An expected failure: it leaves the API as {@code {error: {code, message, ...details}}} with
 * the status its {@link ErrorCode} maps to. Anything else is a bug and becomes an opaque 500.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message, null, false, false); // expected outcomes don't need stack traces
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(ErrorCode.BAD_REQUEST, message);
    }
}
