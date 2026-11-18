package com.documind.service.rag;

/**
 * Canonical constants shared across retrieval, prompt grounding, safe refusal, and provider selection.
 */
public final class RagConstants {

    public static final String SAFE_REFUSAL_MESSAGE = "Không tìm thấy thông tin trong tài liệu nội bộ";
    public static final String PROVIDER_GROQ = "groq";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_GROQ_GEMINI_FAILOVER = "groq+gemini";

    private RagConstants() {
    }
}
