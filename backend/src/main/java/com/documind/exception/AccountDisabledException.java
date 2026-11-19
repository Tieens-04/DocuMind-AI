package com.documind.exception;

/**
 * Thrown when an authenticated user account has {@code is_active = false}.
 */
public class AccountDisabledException extends RuntimeException {

    public AccountDisabledException(String message) {
        super(message);
    }
}
