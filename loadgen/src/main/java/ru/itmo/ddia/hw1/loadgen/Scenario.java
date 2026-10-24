package ru.itmo.ddia.hw1.loadgen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * The load scenario: a mixed CRUD workload over every handler plus a configured share of
 * requests that are bound to fail.
 *
 * The errors do not come from artificial fault injection in the service but from its ordinary
 * contract: a request for a missing id returns 404, an invalid body returns 400.
 * The service did not have to be changed to produce load.
 */
final class Scenario {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final List<String> targets;
    private final double errorRate;

    /** A pool of created ids per target, used by GET, PUT and DELETE. */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> createdIds = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Integer, LongAdder> byStatus = new ConcurrentHashMap<>();
    private final LongAdder failures = new LongAdder();

    Scenario(List<String> targets, double errorRate) {
        this.targets = targets;
        this.errorRate = errorRate;
        targets.forEach(target -> createdIds.put(target, new ConcurrentLinkedDeque<>()));
    }

    void fireOnce() {
        String target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        try {
            HttpRequest request = ThreadLocalRandom.current().nextDouble() < errorRate
                    ? failingRequest(target)
                    : successfulRequest(target);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            byStatus.computeIfAbsent(response.statusCode(), code -> new LongAdder()).increment();
            rememberCreatedId(target, response);
        } catch (Exception e) {
            failures.increment();
        }
    }

    /** The share of requests that must fail: 404 for an unknown id, 400 for an invalid body. */
    private HttpRequest failingRequest(String target) {
        String unknownId = UUID.randomUUID().toString();
        return switch (ThreadLocalRandom.current().nextInt(3)) {
            case 0 -> get(target + "/api/v1/items/" + unknownId);
            case 1 -> put(target + "/api/v1/items/" + unknownId, "{\"name\":\"ghost\",\"quantity\":1}");
            default -> post(target + "/api/v1/items", "{\"name\":\"\",\"quantity\":-1}");
        };
    }

    private HttpRequest successfulRequest(String target) {
        String id = createdIds.get(target).pollFirst();
        if (id == null) {
            return post(target + "/api/v1/items", newItemBody());
        }
        return switch (ThreadLocalRandom.current().nextInt(5)) {
            case 0 -> {
                createdIds.get(target).addLast(id);
                yield get(target + "/api/v1/items/" + id);
            }
            case 1 -> {
                createdIds.get(target).addLast(id);
                yield get(target + "/api/v1/items?limit=20");
            }
            case 2 -> {
                createdIds.get(target).addLast(id);
                yield put(target + "/api/v1/items/" + id, newItemBody());
            }
            // the id is not returned to the pool: the object is deleted
            case 3 -> delete(target + "/api/v1/items/" + id);
            default -> {
                createdIds.get(target).addLast(id);
                yield post(target + "/api/v1/items", newItemBody());
            }
        };
    }

    private void rememberCreatedId(String target, HttpResponse<String> response) {
        if (response.statusCode() != 201) {
            return;
        }
        String body = response.body();
        int start = body.indexOf("\"id\":\"");
        if (start < 0) {
            return;
        }
        start += 6;
        int end = body.indexOf('"', start);
        if (end < 0) {
            return;
        }
        ConcurrentLinkedDeque<String> pool = createdIds.get(target);
        // The pool is bounded, otherwise it grows with the length of the run.
        if (pool.size() < 500) {
            pool.addLast(body.substring(start, end));
        }
    }

    String statsLine() {
        StringBuilder line = new StringBuilder();
        byStatus.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> line.append(entry.getKey()).append('=').append(entry.getValue().sum()).append(' '));
        if (failures.sum() > 0) {
            line.append("transport-errors=").append(failures.sum());
        }
        return line.toString().trim();
    }

    private static String newItemBody() {
        return "{\"name\":\"load-%d\",\"description\":\"generated\",\"quantity\":%d}"
                .formatted(ThreadLocalRandom.current().nextInt(100_000),
                        ThreadLocalRandom.current().nextInt(100));
    }

    private HttpRequest get(String url) {
        return base(url).GET().build();
    }

    private HttpRequest post(String url, String body) {
        return base(url).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest put(String url, String body) {
        return base(url).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest delete(String url) {
        return base(url).DELETE().build();
    }

    private static HttpRequest.Builder base(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
    }
}