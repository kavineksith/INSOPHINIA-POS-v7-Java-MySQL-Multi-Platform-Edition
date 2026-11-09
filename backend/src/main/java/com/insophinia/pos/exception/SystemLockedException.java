package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 423 — system-wide deadman lock engaged. */
public class SystemLockedException extends ApiException {
    public SystemLockedException(String message) {
        super(HttpStatus.LOCKED, "SYSTEM_LOCKED", message);
    }
}
