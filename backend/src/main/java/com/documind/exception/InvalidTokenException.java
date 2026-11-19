package com.documind.exception;

import java.util.Objects;

/**
 * Thrown when an access JWT or refresh token is malformed, expired, revoked, or reused.
 */
public class InvalidTokenException extends RuntimeException {

    public static final String CODE_INVALID_TOKEN = "INVALID_TOKEN";
    public static final String CODE_TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String CODE_TOKEN_REVOKED = "TOKEN_REVOKED";
    public static final String CODE_REFRESH_TOKEN_REUSED = "REFRESH_TOKEN_REUSED";

    private final String errorCode;

    public InvalidTokenException(String errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public InvalidTokenException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public String errorCode() {
        return errorCode;
    }
}
