package ru.itmo.ddia.hw1.service.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import ru.itmo.ddia.hw1.metrics.Measured;
import ru.itmo.ddia.hw1.service.domain.Item;
import ru.itmo.ddia.hw1.service.domain.ItemNotFoundException;
import ru.itmo.ddia.hw1.service.storage.ItemRepository;
import ru.itmo.ddia.hw1.service.web.dto.ItemPage;
import ru.itmo.ddia.hw1.service.web.dto.ItemRequest;
import ru.itmo.ddia.hw1.service.web.dto.ItemResponse;

import java.net.URI;
import java.util.List;

/**
 * CRUD API on top of the in-memory storage.
 *
 * There is no metrics code here: golden signals are collected by metrics-lib through
 * the {@link Measured} annotation, without touching the handler bodies.
 */
@RestController
@RequestMapping(path = "/api/v1/items", produces = "application/json")
@Validated
public class ItemController {

    private final ItemRepository repository;

    public ItemController(ItemRepository repository) {
        this.repository = repository;
    }

    @Measured
    @PostMapping(consumes = "application/json")
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest request) {
        Item created = repository.create(request.name(), request.description(), request.quantity());
        URI location = UriComponentsBuilder.fromPath("/api/v1/items/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(ItemResponse.from(created));
    }

    @Measured
    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable String id) {
        return repository.findById(id)
                .map(ItemResponse::from)
                .orElseThrow(() -> new ItemNotFoundException(id));
    }

    @Measured
    @GetMapping
    public ItemPage list(
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        List<ItemResponse> items = repository.findAll(offset, limit).stream()
                .map(ItemResponse::from)
                .toList();
        return new ItemPage(items, offset, limit, repository.count());
    }

    @Measured
    @PutMapping(path = "/{id}", consumes = "application/json")
    public ItemResponse update(@PathVariable String id, @Valid @RequestBody ItemRequest request) {
        return repository.update(id, request.name(), request.description(), request.quantity())
                .map(ItemResponse::from)
                .orElseThrow(() -> new ItemNotFoundException(id));
    }

    @Measured
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repository.delete(id)) {
            throw new ItemNotFoundException(id);
        }
        return ResponseEntity.noContent().build();
    }
}