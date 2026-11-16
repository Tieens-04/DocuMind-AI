package com.documind.exception;

/**
 * Thrown when an endpoint requires an authenticated security principal that is absent.
 */
public class AuthenticationRequiredException extends RuntimeException {

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
