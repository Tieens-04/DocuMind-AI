package com.documind.controller.dto.chat;

import com.documind.domain.entity.ChatSessionEntity;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * API representation of a user-owned multi-turn chat session.
 */
public record ChatSessionResponse(
    UUID id,
    String title,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    public static ChatSessionResponse from(ChatSessionEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new ChatSessionResponse(
            entity.getId(),
            entity.getTitle(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
