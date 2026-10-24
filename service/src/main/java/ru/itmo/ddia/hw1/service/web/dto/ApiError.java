package ru.itmo.ddia.hw1.service.web.dto;

import java.time.Instant;
import java.util.List;

public record ApiError(int status, String error, String message, List<String> details, Instant timestamp) {

    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(status, error, message, details, Instant.now());
    }
}