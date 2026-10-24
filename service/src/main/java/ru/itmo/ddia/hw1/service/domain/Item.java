package ru.itmo.ddia.hw1.service.domain;

import java.time.Instant;

/**
 * A stored record. Immutable: an update produces a new version.
 */
public record Item(
        String id,
        String name,
        String description,
        int quantity,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public Item withUpdate(String name, String description, int quantity, Instant now) {
        return new Item(id, name, description, quantity, version + 1, createdAt, now);
    }
}