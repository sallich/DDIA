package ru.itmo.ddia.hw1.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Instance attributes the common metric labels are built from.
 *
 * The library owns its own namespace: `ddia.metrics.instance.*`.
 * The application is only responsible for filling these properties in.
 *
 * The values are unknown at compile time, so the defaults are deliberately neutral:
 * the library survives an incomplete configuration, and a missing value is immediately
 * visible in the exposed metrics.
 */
@ConfigurationProperties(prefix = "ddia.metrics.instance")
public record InstanceTagProperties(String pod, String datacenter, String deployment) {

    private static final String UNSET = "unknown";

    public InstanceTagProperties {
        pod = orUnset(pod);
        datacenter = orUnset(datacenter);
        deployment = orUnset(deployment);
    }

    private static String orUnset(String value) {
        return value == null || value.isBlank() ? UNSET : value;
    }
}