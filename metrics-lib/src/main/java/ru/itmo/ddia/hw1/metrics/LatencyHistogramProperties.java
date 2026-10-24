package ru.itmo.ddia.hw1.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Bucket boundaries of the latency histogram.
 *
 * The defaults come from the measured distribution of the demo service (p50 around 1.9 ms,
 * p99 around 3.8 ms): dense between 1 and 5 ms, with a sparse tail up to a second for load
 * conditions. Boundaries have to match the actual distribution: Micrometer defaults start
 * at 1 ms, and on a faster service every percentile collapses into a single line.
 *
 * Overridden in the application configuration:
 * <pre>
 * ddia.metrics.latency.buckets[0]=250us
 * ddia.metrics.latency.buckets[1]=1ms
 * </pre>
 */
@ConfigurationProperties(prefix = "ddia.metrics.latency")
public record LatencyHistogramProperties(List<Duration> buckets) {

    private static final List<Duration> DEFAULT_BUCKETS = List.of(
            Duration.ofNanos(500_000),      // 500us
            Duration.ofNanos(750_000),      // 750us
            Duration.ofMillis(1),
            Duration.ofNanos(1_500_000),    // 1.5ms
            Duration.ofMillis(2),
            Duration.ofMillis(3),
            Duration.ofMillis(4),
            Duration.ofMillis(5),
            Duration.ofNanos(7_500_000),    // 7.5ms
            Duration.ofMillis(10),
            Duration.ofMillis(25),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1));

    public LatencyHistogramProperties {
        buckets = (buckets == null || buckets.isEmpty()) ? DEFAULT_BUCKETS : List.copyOf(buckets);
    }

    public Duration[] bucketsAsArray() {
        return buckets.toArray(new Duration[0]);
    }
}