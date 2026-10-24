package ru.itmo.ddia.hw1.dashboards;

import java.util.List;

/**
 * PromQL query templates for the golden signals.
 *
 * Percentiles are computed with histogram_quantile: buckets are counters and they add up
 * correctly, so selecting several pods or datacenters yields an honest percentile over the
 * combined sample instead of an average of percentiles.
 *
 * $__rate_interval is a built-in Grafana variable: it substitutes a window aligned with the
 * graph step and the scrape interval, so rate does not break apart on zoom.
 *
 * Selectors are applied with =~ against variables whose allValue is ".*", so that the "All"
 * option works through the very same expression as an explicit multi-value selection.
 */
final class PromQl {

    static final List<String> PERCENTILES = List.of("0.50", "0.75", "0.90", "0.95", "0.99");

    private static final String INSTANCE_SELECTOR = "datacenter=~\"$datacenter\",pod=~\"$pod\"";

    private PromQl() {
    }

    static String latencyPercentile(String metric, String route, String method, String quantile) {
        return """
                histogram_quantile(%s, sum by (le) (rate(%s_bucket{route="%s",method="%s",%s}[$__rate_interval])))"""
                .formatted(quantile, metric, route, method, INSTANCE_SELECTOR);
    }

    static String throughput(String metric, String route, String method) {
        return """
                sum(rate(%s{route="%s",method="%s",%s}[$__rate_interval]))"""
                .formatted(metric, route, method, INSTANCE_SELECTOR);
    }

    static String errorsByStatus(String metric, String route, String method) {
        return """
                sum by (status) (rate(%s{route="%s",method="%s",status=~"4..|5..",%s}[$__rate_interval]))"""
                .formatted(metric, route, method, INSTANCE_SELECTOR);
    }

    static String throughputByHandler(String metric) {
        return """
                sum by (route, method) (rate(%s{%s}[$__rate_interval]))"""
                .formatted(metric, INSTANCE_SELECTOR);
    }

    static String errorsByStatusTotal(String metric) {
        return """
                sum by (status) (rate(%s{status=~"4..|5..",%s}[$__rate_interval]))"""
                .formatted(metric, INSTANCE_SELECTOR);
    }

    static String labelValues(String metric, String label) {
        return "label_values(%s, %s)".formatted(metric, label);
    }

    static String labelValuesFilteredByDatacenter(String metric, String label) {
        return "label_values(%s{datacenter=~\"$datacenter\"}, %s)".formatted(metric, label);
    }
}