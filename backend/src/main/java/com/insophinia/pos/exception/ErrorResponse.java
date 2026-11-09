package com.insophinia.pos.exception;

import java.time.Instant;
import java.util.Map;

/** Uniform error envelope, uniform response envelope. */
public record ErrorResponse(
        boolean success,
        String message,
        String code,
        Map<String, String> errors,
        int status,
        String path,
        Instant timestamp
) {
    public static ErrorResponse of(String message, String code, int status, String path) {
        return new ErrorResponse(false, message, code, null, status, path, Instant.now());
    }

    public static ErrorResponse ofValidation(Map<String, String> errors, String path) {
        return new ErrorResponse(false, "Validation failed", "VALIDATION_FAILED", errors, 400, path, Instant.now());
    }
}
