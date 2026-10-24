package ru.itmo.ddia.hw1.dashboards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public final class GrafanaClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String authorization;

    private GrafanaClient(String baseUrl, String authorization) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authorization = authorization;
    }

    public static GrafanaClient withBasicAuth(String baseUrl, String user, String password) {
        String raw = user + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return new GrafanaClient(baseUrl, "Basic " + encoded);
    }

    public static GrafanaClient withToken(String baseUrl, String token) {
        return new GrafanaClient(baseUrl, "Bearer " + token);
    }

    public Response get(String path) throws IOException, InterruptedException {
        return send(request(path).GET().build());
    }

    public Response post(String path, String jsonBody) throws IOException, InterruptedException {
        return send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", authorization);
    }

    private Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    public record Response(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonNode json() throws IOException {
            return JSON.readTree(body);
        }

        public Response requireOk(String what) throws IOException {
            if (!ok()) {
                throw new IOException(what + ": HTTP " + status + " " + body);
            }
            return this;
        }
    }
}