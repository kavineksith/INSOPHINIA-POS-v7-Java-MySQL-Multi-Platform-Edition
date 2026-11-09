package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 409 — uniqueness or state conflict (e.g. duplicate bill number). */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
