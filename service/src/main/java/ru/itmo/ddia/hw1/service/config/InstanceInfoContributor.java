package ru.itmo.ddia.hw1.service.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Exposes on /actuator/info the values the container started with: a quick way
 * to confirm that the environment variables reached the instance.
 */
@Component
public class InstanceInfoContributor implements InfoContributor {

    private final InstanceProperties properties;

    public InstanceInfoContributor(InstanceProperties properties) {
        this.properties = properties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("instance", Map.of(
                "pod", properties.pod(),
                "datacenter", properties.datacenter(),
                "deployment", properties.deployment()));
    }
}