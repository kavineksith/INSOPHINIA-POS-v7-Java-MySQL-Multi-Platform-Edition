package com.insophinia.pos.exception;

import org.springframework.http.HttpStatus;

/** 423 — account locked after failed logins. */
public class AccountLockedException extends ApiException {
    public AccountLockedException(String message) {
        super(HttpStatus.LOCKED, "ACCOUNT_LOCKED", message);
    }
}
