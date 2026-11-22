package com.documind.controller.dto.chat;

import java.util.Objects;

/**
 * Terminal error payload for SSE {@code event: error}, emitted when an AI/IO or concurrency failure
 * occurs after the {@code text/event-stream} response has already started.
 */
public record ChatStreamErrorEvent(
    String errorCode,
    String message,
    String provider,
    boolean retryable
) {

    public static final String CODE_LLM_PROVIDER_UNAVAILABLE = "LLM_PROVIDER_UNAVAILABLE";
    public static final String CODE_LLM_STREAM_INTERRUPTED = "LLM_STREAM_INTERRUPTED";
    public static final String CODE_SSE_STREAM_TIMEOUT = "SSE_STREAM_TIMEOUT";
    public static final String CODE_RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    public ChatStreamErrorEvent {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
