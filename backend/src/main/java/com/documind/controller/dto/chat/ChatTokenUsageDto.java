package com.documind.controller.dto.chat;

/**
 * Aggregated FinOps token usage reported in SSE {@code event: done} (combining optional query rewrite
 * and streamed answer tokens).
 */
public record ChatTokenUsageDto(
    int promptTokens,
    int completionTokens,
    int totalTokens
) {

    public ChatTokenUsageDto {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        totalTokens = Math.addExact(promptTokens, completionTokens);
    }

    public static ChatTokenUsageDto of(int promptTokens, int completionTokens) {
        int safePrompt = Math.max(0, promptTokens);
        int safeCompletion = Math.max(0, completionTokens);
        return new ChatTokenUsageDto(safePrompt, safeCompletion, safePrompt + safeCompletion);
    }
}
