package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 429 — too many requests. */
public class RateLimitExceededException extends ApiException {
    public RateLimitExceededException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", message);
    }
}
