package com.performantfs.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A small HTTP client for tests that remembers the session cookie, like a browser would. */
public class ApiClient {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String base;
    private String cookie;

    public ApiClient(String base) {
        this.base = base;
    }

    public Response get(String path, String... headers) {
        return send("GET", path, null, headers);
    }

    public Response post(String path, Object body, String... headers) {
        return send("POST", path, body, headers);
    }

    public Response patch(String path, Object body) {
        return send("PATCH", path, body);
    }

    public Response put(String path, Object body, String... headers) {
        return send("PUT", path, body, headers);
    }

    public Response delete(String path) {
        return send("DELETE", path, null);
    }

    /** Sends JSON for objects, raw bytes for byte[]; {@code headers} are name/value pairs. */
    public Response send(String method, String path, Object body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (body instanceof byte[] bytes) {
            request.header("content-type", "application/octet-stream");
            request.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
        } else if (body != null) {
            request.header("content-type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (cookie != null) request.header("cookie", cookie);
        for (int i = 0; i + 1 < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        try {
            HttpResponse<byte[]> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            response.headers().firstValue("set-cookie").ifPresent(c -> cookie = c.split(";", 2)[0]);
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public String cookie() {
        return cookie;
    }

    public void cookie(String value) {
        this.cookie = value;
    }

    public record Response(int status, Map<String, java.util.List<String>> headers, byte[] body) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String header(String name) {
            var values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        /** The error code of an {error: {code, ...}} body. */
        public String errorCode() {
            return json().path("error").path("code").asString();
        }
    }
}
