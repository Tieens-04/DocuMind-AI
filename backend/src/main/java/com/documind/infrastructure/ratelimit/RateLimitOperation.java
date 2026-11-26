package com.documind.infrastructure.ratelimit;

/**
 * Rate-limited API operation families backed by independent Redis token buckets.
 */
public enum RateLimitOperation {
    CHAT("chat"),
    UPLOAD("upload");

    private final String keySegment;

    RateLimitOperation(String keySegment) {
        this.keySegment = keySegment;
    }

    public String keySegment() {
        return keySegment;
    }
}
