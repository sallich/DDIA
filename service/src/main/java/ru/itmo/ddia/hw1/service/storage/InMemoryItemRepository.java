package ru.itmo.ddia.hw1.service.storage;

import org.springframework.stereotype.Repository;
import ru.itmo.ddia.hw1.service.domain.Item;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Storage in a runtime container (ConcurrentHashMap): no separate database is needed here.
 * Data lives only for the lifetime of the process.
 */
@Repository
public class InMemoryItemRepository implements ItemRepository {

    private final ConcurrentMap<String, Item> items = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryItemRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Item create(String name, String description, int quantity) {
        Instant now = clock.instant();
        Item created = new Item(UUID.randomUUID().toString(), name, description, quantity, 1L, now, now);
        items.put(created.id(), created);
        return created;
    }

    @Override
    public Optional<Item> findById(String id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public List<Item> findAll(int offset, int limit) {
        return items.values().stream()
                .sorted(Comparator.comparing(Item::createdAt).thenComparing(Item::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public int count() {
        return items.size();
    }

    @Override
    public Optional<Item> update(String id, String name, String description, int quantity) {
        Instant now = clock.instant();
        return Optional.ofNullable(
                items.computeIfPresent(id, (key, current) -> current.withUpdate(name, description, quantity, now)));
    }

    @Override
    public boolean delete(String id) {
        return items.remove(id) != null;
    }
}