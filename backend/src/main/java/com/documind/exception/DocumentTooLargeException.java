package com.documind.exception;

public class DocumentTooLargeException extends RuntimeException {

    private final long maxSizeBytes;

    public DocumentTooLargeException(long maxSizeBytes, String message) {
        super(message);
        this.maxSizeBytes = maxSizeBytes;
    }

    public long maxSizeBytes() {
        return maxSizeBytes;
    }
}
