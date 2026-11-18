package com.documind.controller.dto;

import com.documind.service.rag.RagProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Synchronous RAG question payload submitted to {@code POST /api/v1/chat/ask}.
 */
public record ChatAskRequest(
    @NotBlank(message = "Question must not be blank")
    // Must stay aligned with documind.rag.llm.max-question-chars in application.yml
    @Size(
        max = RagProperties.DEFAULT_MAX_QUESTION_CHARS,
        message = "Question must not exceed " + RagProperties.DEFAULT_MAX_QUESTION_CHARS + " characters"
    )
    String question
) {
}
