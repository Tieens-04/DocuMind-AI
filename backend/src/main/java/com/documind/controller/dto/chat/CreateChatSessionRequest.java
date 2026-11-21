package com.documind.controller.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Optional request body for creating a new chat session via {@code POST /api/v1/chat/sessions}.
 */
public record CreateChatSessionRequest(
    @Schema(
        description = "Optional custom session title (defaults to 'Cuộc hội thoại mới' and auto-titles on first question)",
        maxLength = 255,
        nullable = true
    )
    @Size(max = 255, message = "Session title must not exceed 255 characters")
    String title
) {
}
