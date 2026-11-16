package com.documind.exception;

public class InvalidDocumentRequestException extends RuntimeException {

    public InvalidDocumentRequestException(String message) {
        super(message);
    }
}
