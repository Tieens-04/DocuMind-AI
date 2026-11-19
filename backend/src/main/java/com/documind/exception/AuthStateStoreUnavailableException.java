package com.documind.exception;

/**
 * Thrown when the backing authentication state store (Redis token blacklist) is unreachable,
 * enforcing fail-closed rejection so revoked JWTs cannot bypass logout enforcement.
 */
public class AuthStateStoreUnavailableException extends RuntimeException {

    public AuthStateStoreUnavailableException(String message) {
        super(message);
    }

    public AuthStateStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
