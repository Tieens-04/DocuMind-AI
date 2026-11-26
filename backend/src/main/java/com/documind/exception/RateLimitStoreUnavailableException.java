package com.documind.exception;

/**
 * Thrown when the Redis-backed Bucket4j rate-limit store is unavailable or timed out so callers fail closed (503).
 */
public class RateLimitStoreUnavailableException extends RuntimeException {

    public RateLimitStoreUnavailableException(String message) {
        super(message);
    }

    public RateLimitStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
