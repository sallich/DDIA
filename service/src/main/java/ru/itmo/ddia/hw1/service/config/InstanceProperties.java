package ru.itmo.ddia.hw1.service.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Values unknown at compile time: supplied through environment variables
 * (INSTANCE_POD, INSTANCE_DATACENTER, INSTANCE_DEPLOYMENT), see docker-compose.yaml.
 */
@Validated
@ConfigurationProperties(prefix = "instance")
public record InstanceProperties(
        @NotBlank String pod,
        @NotBlank String datacenter,
        @NotBlank String deployment
) {
}