package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 400 — malformed or invalid request data. */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }
}
