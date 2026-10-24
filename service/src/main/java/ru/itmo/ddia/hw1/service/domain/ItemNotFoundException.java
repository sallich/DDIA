package ru.itmo.ddia.hw1.service.domain;

public class ItemNotFoundException extends RuntimeException {

    public ItemNotFoundException(String id) {
        super("Item not found: " + id);
    }
}