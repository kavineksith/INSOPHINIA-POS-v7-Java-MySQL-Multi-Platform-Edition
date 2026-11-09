package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 403 — authenticated but lacks permission. */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
