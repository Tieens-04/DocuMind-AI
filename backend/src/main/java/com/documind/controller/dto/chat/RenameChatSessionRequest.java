package com.documind.controller.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for renaming an existing chat session via {@code PATCH /api/v1/chat/sessions/{id}}.
 */
public record RenameChatSessionRequest(
    @Schema(description = "New non-blank session title", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
    @NotBlank(message = "Session title must not be blank")
    @Size(max = 255, message = "Session title must not exceed 255 characters")
    String title
) {
}
