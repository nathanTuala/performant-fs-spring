package com.performantfs.http;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Every error the API can answer with, and the HTTP status it maps to. Serialised in snake_case. */
public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    CHUNK_INVALID(HttpStatus.BAD_REQUEST),
    DIGEST_MISMATCH(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    NAME_CONFLICT(HttpStatus.CONFLICT),
    CYCLE(HttpStatus.CONFLICT),
    UPLOAD_INCOMPLETE(HttpStatus.CONFLICT),
    UPLOAD_CLOSED(HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    RANGE_NOT_SATISFIABLE(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE),
    QUOTA_EXCEEDED(HttpStatus.INSUFFICIENT_STORAGE),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
