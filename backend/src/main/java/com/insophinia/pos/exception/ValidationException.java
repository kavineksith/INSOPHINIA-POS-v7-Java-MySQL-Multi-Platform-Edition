package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** 400 — bean-validation failure, carries per-field error messages. */
public class ValidationException extends ApiException {
    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed");
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() { return fieldErrors; }
}
