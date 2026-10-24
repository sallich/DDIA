package ru.itmo.ddia.hw1.service.storage;

import ru.itmo.ddia.hw1.service.domain.Item;

import java.util.List;
import java.util.Optional;

public interface ItemRepository {

    Item create(String name, String description, int quantity);

    Optional<Item> findById(String id);

    List<Item> findAll(int offset, int limit);

    int count();

    Optional<Item> update(String id, String name, String description, int quantity);

    boolean delete(String id);
}