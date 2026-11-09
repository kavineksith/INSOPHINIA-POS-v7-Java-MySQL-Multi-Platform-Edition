package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 409 — concurrent sale consumed the remaining stock. */
public class InsufficientStockException extends ApiException {
    public InsufficientStockException(String message) {
        super(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", message);
    }
}
