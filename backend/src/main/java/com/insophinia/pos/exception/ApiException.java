package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** Base class for all business exceptions. Carries the HTTP status to return. */
public abstract class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
