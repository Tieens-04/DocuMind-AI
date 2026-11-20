package com.documind.exception;

/**
 * Thrown when an administrative department or user management request violates domain validation rules.
 */
public class InvalidAdminRequestException extends RuntimeException {

    public InvalidAdminRequestException(String message) {
        super(message);
    }
}
