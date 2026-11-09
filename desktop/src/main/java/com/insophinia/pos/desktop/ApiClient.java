package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Thin JSON client for the Spring Boot backend. Throws ApiException on non-2xx. */
public class ApiClient {

    public static class ApiException extends RuntimeException {
        public final int status;
        public final String code;
        public ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static JsonNode get(String path) { return call("GET", path, null); }
    public static JsonNode post(String path, Object body) { return call("POST", path, body); }
    public static JsonNode put(String path, Object body) { return call("PUT", path, body); }
    public static JsonNode delete(String path) { return call("DELETE", path, null); }

    /**
     * Fetch a non-JSON endpoint (e.g. the A4 receipt HTML at
     * /api/billing/{id}/a4-html which returns text/html). Returns the raw
     * response body as a String. Throws ApiException on non-2xx.
     */
    public static String getRaw(String path) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(Session.validatedBaseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "text/html, */*")
                    .GET();
            if (Session.token != null) {
                b.header("Authorization", "Bearer " + Session.token);
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new ApiException(res.statusCode(), "HTTP_ERROR",
                        "Request failed with HTTP " + res.statusCode());
            }
            return res.body();
        } catch (ApiException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new ApiException(0, "NETWORK", "Cannot reach the server at " + Session.baseUrl);
        }
    }

    public static void download(String path, Path target) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(Session.validatedBaseUrl() + path))
                .timeout(Duration.ofSeconds(60)).GET();
        if (Session.token != null) b.header("Authorization", "Bearer " + Session.token);
        HttpResponse<byte[]> response = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) throw new IOException("Download failed with HTTP " + response.statusCode());
        Files.write(target, response.body());
    }

    private static JsonNode call(String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(Session.validatedBaseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            if (Session.token != null) {
                b.header("Authorization", "Bearer " + Session.token);
            }
            String json = body == null ? null : mapper.writeValueAsString(body);
            b.method(method, json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode tree = res.body() == null || res.body().isBlank() ? null : mapper.readTree(res.body());
            if (res.statusCode() >= 400) {
                String code = tree != null && tree.has("code") ? tree.get("code").asText() : "ERROR";
                String msg = tree != null && tree.has("message") ? tree.get("message").asText() : "Request failed";
                throw new ApiException(res.statusCode(), code, msg);
            }
            return tree;
        } catch (ApiException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new ApiException(0, "NETWORK", "Cannot reach the server at " + Session.baseUrl);
        }
    }
}
