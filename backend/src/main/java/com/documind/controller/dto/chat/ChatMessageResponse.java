package com.documind.controller.dto.chat;

import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.enums.MessageRole;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * API representation of a persisted conversation message with RBAC-reverified citation links and telemetry.
 */
public record ChatMessageResponse(
    UUID id,
    UUID sessionId,
    MessageRole role,
    String content,
    String rewrittenQuery,
    List<CitationDto> citations,
    boolean abstained,
    int promptTokens,
    int completionTokens,
    Long latencyMs,
    String modelProvider,
    OffsetDateTime createdAt
) {

    public ChatMessageResponse {
        citations = citations != null ? List.copyOf(citations) : List.of();
    }

    public static ChatMessageResponse from(ChatMessageEntity entity, List<CitationDto> hydratedCitations) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new ChatMessageResponse(
            entity.getId(),
            entity.getSessionId(),
            entity.getRole(),
            entity.getContent(),
            entity.getRewrittenQuery(),
            hydratedCitations,
            entity.isAbstained(),
            entity.getPromptTokens() != null ? entity.getPromptTokens() : 0,
            entity.getCompletionTokens() != null ? entity.getCompletionTokens() : 0,
            entity.getLatencyMs(),
            entity.getModelProvider(),
            entity.getCreatedAt()
        );
    }
}
