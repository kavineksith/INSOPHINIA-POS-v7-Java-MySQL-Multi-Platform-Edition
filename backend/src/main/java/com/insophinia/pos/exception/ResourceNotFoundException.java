package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 404 — requested entity does not exist (or is soft-deleted). */
public class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
