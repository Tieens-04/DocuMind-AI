package com.documind.controller.dto;

import com.documind.service.rag.RagProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Synchronous RAG question payload submitted to {@code POST /api/v1/chat/ask}.
 */
public record ChatAskRequest(
    @Schema(description = "User question in Vietnamese or English", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Question must not be blank")
    // Must stay aligned with documind.rag.llm.max-question-chars in application.yml
    @Size(
        max = RagProperties.DEFAULT_MAX_QUESTION_CHARS,
        message = "Question must not exceed " + RagProperties.DEFAULT_MAX_QUESTION_CHARS + " characters"
    )
    String question,

    @Schema(
        description = "Optional existing chat session UUID owned by the caller; when omitted, a new session is automatically created for authenticated users",
        nullable = true
    )
    UUID sessionId,

    @Schema(
        description = "Optional target department UUID filter allowed exclusively for SYSTEM_ADMIN; rejected with 403 for non-admin callers",
        nullable = true
    )
    UUID departmentId
) {

    public ChatAskRequest(String question) {
        this(question, null, null);
    }

    public ChatAskRequest(String question, UUID departmentId) {
        this(question, null, departmentId);
    }
}
