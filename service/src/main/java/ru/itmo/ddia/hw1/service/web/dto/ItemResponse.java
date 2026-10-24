package ru.itmo.ddia.hw1.service.web.dto;

import ru.itmo.ddia.hw1.service.domain.Item;

import java.time.Instant;

public record ItemResponse(
        String id,
        String name,
        String description,
        int quantity,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static ItemResponse from(Item item) {
        return new ItemResponse(
                item.id(),
                item.name(),
                item.description(),
                item.quantity(),
                item.version(),
                item.createdAt(),
                item.updatedAt());
    }
}