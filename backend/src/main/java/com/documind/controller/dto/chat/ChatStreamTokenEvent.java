package com.documind.controller.dto.chat;

import java.util.Objects;

/**
 * Payload for SSE {@code event: token}, wrapping each incremental text delta in JSON so leading/trailing
 * spaces, Markdown newlines ({@code \n}), and multi-byte Vietnamese UTF-8 characters are preserved losslessly.
 */
public record ChatStreamTokenEvent(
    String delta
) {

    public ChatStreamTokenEvent {
        Objects.requireNonNull(delta, "delta must not be null");
    }
}
