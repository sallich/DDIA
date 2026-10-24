package ru.itmo.ddia.hw1.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the golden signals. Kept separate from the interceptor on purpose: the interceptor
 * decides WHEN and WHAT to measure, the recorder decides HOW it reaches Prometheus.
 *
 *   ddia_http_request_duration_seconds  - histogram, labels route and method
 *   ddia_http_requests_total            - counter, labels route, method and status
 *
 * The pod / datacenter / deployment labels are not passed here: a MeterRegistryCustomizer
 * applies them to the whole registry at once.
 */
public class GoldenSignalsRecorder {

    public static final String LATENCY = "ddia.http.request.duration";
    public static final String REQUESTS = "ddia.http.requests";
    static final String TYPE_LATENCY = "latency";
    static final String TYPE_THROUGHPUT = "throughput";

    private final MeterRegistry registry;
    private final Duration[] buckets;

    public GoldenSignalsRecorder(MeterRegistry registry, LatencyHistogramProperties histogram) {
        this.registry = registry;
        this.buckets = histogram.bucketsAsArray();
    }

    public void record(String route, String httpMethod, int status, long durationNanos) {
        Timer.builder(LATENCY)
                .description("Время обработки запроса от начала обработчика до записи ответа")
                .tag("route", route)
                .tag("method", httpMethod)
                .tag("metric_type", TYPE_LATENCY)
                // Service level objectives produce exactly the listed buckets. histogram_quantile
                // computes the percentile at read time, so aggregation over pods and datacenters stays correct.
                .serviceLevelObjectives(buckets)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        Counter.builder(REQUESTS)
                .description("Количество обработанных запросов с разбивкой по коду ответа")
                .tag("route", route)
                .tag("method", httpMethod)
                .tag("status", Integer.toString(status))
                .tag("metric_type", TYPE_THROUGHPUT)
                .register(registry)
                .increment();
    }
}