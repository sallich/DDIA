package ru.itmo.ddia.hw1.dashboards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of instrumented handlers, obtained from the running application at runtime.
 *
 * The source is /actuator/measured, the contract of metrics-lib. The metric names arrive
 * through the same response, so the generator hardcodes neither handlers nor metric names.
 */
public record MeasuredHandlers(String deployment, String latencyMetric, String throughputMetric,
                               List<Handler> handlers) {

    public record Handler(String route, List<String> methods) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static MeasuredHandlers fetch(String serviceBaseUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(serviceBaseUrl) + "/actuator/measured"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch the handler list: HTTP "
                    + response.statusCode() + " " + response.body());
        }

        JsonNode root = JSON.readTree(response.body());
        List<Handler> handlers = new ArrayList<>();
        for (JsonNode node : root.path("handlers")) {
            List<String> methods = new ArrayList<>();
            node.path("methods").forEach(method -> methods.add(method.asText()));
            handlers.add(new Handler(node.path("route").asText(), List.copyOf(methods)));
        }
        if (handlers.isEmpty()) {
            throw new IOException("The application reported no instrumented handlers");
        }

        return new MeasuredHandlers(
                root.path("deployment").asText(),
                root.path("metrics").path("latency").asText(),
                root.path("metrics").path("throughput").asText(),
                List.copyOf(handlers));
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}