package com.documind.controller.dto.chat;

import com.documind.domain.enums.CacheTier;

import java.util.Objects;
import java.util.UUID;

/**
 * Terminal success payload for SSE {@code event: done}, emitted after atomic persistence of
 * {@code chat_sessions}, {@code chat_messages}, and {@code audit_logs}.
 */
public record ChatStreamDoneEvent(
    UUID sessionId,
    UUID messageId,
    boolean abstained,
    String modelProvider,
    boolean cacheHit,
    CacheTier cacheTier,
    ChatTokenUsageDto tokenUsage,
    long latencyMs
) {

    public ChatStreamDoneEvent {
        cacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
        tokenUsage = Objects.requireNonNullElse(tokenUsage, ChatTokenUsageDto.of(0, 0));
        latencyMs = Math.max(0L, latencyMs);
    }
}
