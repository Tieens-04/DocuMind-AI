package com.documind.exception;

public class UnsupportedDocumentTypeException extends RuntimeException {

    private final String detectedMimeType;

    public UnsupportedDocumentTypeException(String detectedMimeType, String message) {
        super(message);
        this.detectedMimeType = detectedMimeType;
    }

    public String detectedMimeType() {
        return detectedMimeType;
    }
}
