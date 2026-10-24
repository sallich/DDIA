package ru.itmo.ddia.hw1.dashboards;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads configuration from the environment. Secrets come from files only, never from variables. */
final class Env {

    private Env() {
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not set: " + name);
        }
        return value;
    }

    static String orDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * The secret is read from a file whose path arrives in the variable.
     * The secret value itself never goes into an environment variable: it would leak
     * into docker inspect and into the output of ps inside the container.
     */
    static String secretFromFile(String pathVariable) throws IOException {
        Path path = Path.of(required(pathVariable));
        String value = Files.readString(path).trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Secret file is empty: " + path);
        }
        return value;
    }
}