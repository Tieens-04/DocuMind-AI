package com.documind.exception;

import java.util.Objects;

/**
 * Thrown when an administrative operation conflicts with existing state or governance guardrails.
 */
public class AdminConflictException extends RuntimeException {

    public static final String CODE_DUPLICATE_DEPARTMENT_CODE = "DUPLICATE_DEPARTMENT_CODE";
    public static final String CODE_DEPARTMENT_IN_USE = "DEPARTMENT_IN_USE";
    public static final String CODE_DUPLICATE_EMAIL = "DUPLICATE_EMAIL";
    public static final String CODE_ADMIN_SELF_MODIFICATION_FORBIDDEN = "ADMIN_SELF_MODIFICATION_FORBIDDEN";
    public static final String CODE_LAST_ACTIVE_ADMIN = "LAST_ACTIVE_ADMIN";

    private final String errorCode;

    public AdminConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public AdminConflictException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public String errorCode() {
        return errorCode;
    }
}
