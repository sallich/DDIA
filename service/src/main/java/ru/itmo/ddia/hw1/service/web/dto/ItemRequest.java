package ru.itmo.ddia.hw1.service.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ItemRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1024) String description,
        @Min(0) int quantity
) {
}