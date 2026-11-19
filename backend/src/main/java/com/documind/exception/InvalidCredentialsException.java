package com.documind.exception;

/**
 * Thrown when login email or password verification fails.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
