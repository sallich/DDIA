package ru.itmo.ddia.hw1.service.web.dto;

import java.util.List;

public record ItemPage(List<ItemResponse> items, int offset, int limit, int total) {
}