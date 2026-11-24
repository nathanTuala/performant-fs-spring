package com.performantfs.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure to {@link ErrorBody}: expected ones keep their code, unknown ones are logged as 500. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        return respond(e.code(), e.getMessage(), e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException e) {
        var first = e.getBindingResult().getFieldErrors().stream().findFirst();
        String message = first.map(f -> f.getField() + " " + f.getDefaultMessage()).orElse("Invalid request body");
        return respond(ErrorCode.BAD_REQUEST, message, e);
    }

    @ExceptionHandler({
        HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
    })
    ResponseEntity<ErrorBody> badRequest(Exception e) {
        String message = e instanceof HttpMessageNotReadableException ? "Malformed JSON body" : e.getMessage();
        return respond(ErrorCode.BAD_REQUEST, message, e);
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ErrorBody> noSuchEndpoint(Exception e) {
        return respond(ErrorCode.NOT_FOUND, "No such endpoint", e);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> mediaType(HttpMediaTypeNotSupportedException e) {
        return respond(ErrorCode.BAD_REQUEST, e.getMessage(), e);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("unhandled error", e);
        return respond(ErrorCode.INTERNAL, "Something went wrong", e);
    }

    private static ResponseEntity<ErrorBody> respond(ErrorCode code, String message, Exception e) {
        var details = e instanceof ApiException api ? api.details() : java.util.Map.<String, Object>of();
        return ResponseEntity.status(code.status()).body(ErrorBody.of(code, message, details));
    }
}
