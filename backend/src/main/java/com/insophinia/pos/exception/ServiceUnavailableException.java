package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 503 — downstream service (e.g. SMTP) unavailable. */
public class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }
}
