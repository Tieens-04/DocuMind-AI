package com.documind.service.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties controlling vector retrieval bounds, excerpt sizing, and LLM ask safeguards.
 */
@Validated
@ConfigurationProperties(prefix = "documind.rag")
public record RagProperties(
    RetrievalProperties retrieval,
    LlmProperties llm
) {

    public static final int DEFAULT_TOP_K = 5;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.58d;
    public static final int DEFAULT_OVERFETCH_MULTIPLIER = 2;
    public static final int DEFAULT_MAX_EXCERPT_CHARS = 500;
    public static final int DEFAULT_MAX_QUESTION_CHARS = 4000;
    public static final boolean DEFAULT_FALLBACK_ENABLED = true;

    @ConstructorBinding
    public RagProperties {
        retrieval = retrieval != null ? retrieval : RetrievalProperties.defaults();
        llm = llm != null ? llm : LlmProperties.defaults();
    }

    public static RagProperties defaults() {
        return new RagProperties(RetrievalProperties.defaults(), LlmProperties.defaults());
    }

    public record RetrievalProperties(
        @DefaultValue("5") int topK,
        @DefaultValue("0.58") double similarityThreshold,
        @DefaultValue("2") int overfetchMultiplier,
        @DefaultValue("500") int maxExcerptChars
    ) {

        @ConstructorBinding
        public RetrievalProperties {
            if (topK < 1 || topK > 20) {
                throw new IllegalArgumentException("topK must be between 1 and 20");
            }
            if (Double.isNaN(similarityThreshold) || similarityThreshold < 0.0d || similarityThreshold > 1.0d) {
                throw new IllegalArgumentException("similarityThreshold must be between 0.0 and 1.0");
            }
            if (overfetchMultiplier < 1 || overfetchMultiplier > 5) {
                throw new IllegalArgumentException("overfetchMultiplier must be between 1 and 5");
            }
            if (maxExcerptChars < 50 || maxExcerptChars > 4000) {
                throw new IllegalArgumentException("maxExcerptChars must be between 50 and 4000");
            }
        }

        public static RetrievalProperties defaults() {
            return new RetrievalProperties(
                DEFAULT_TOP_K,
                DEFAULT_SIMILARITY_THRESHOLD,
                DEFAULT_OVERFETCH_MULTIPLIER,
                DEFAULT_MAX_EXCERPT_CHARS
            );
        }

        public int candidateFetchLimit() {
            return Math.multiplyExact(topK, overfetchMultiplier);
        }
    }

    public record LlmProperties(
        @DefaultValue("4000") int maxQuestionChars,
        @DefaultValue("true") boolean fallbackEnabled
    ) {

        @ConstructorBinding
        public LlmProperties {
            if (maxQuestionChars < 10 || maxQuestionChars > 16000) {
                throw new IllegalArgumentException("maxQuestionChars must be between 10 and 16000");
            }
        }

        public static LlmProperties defaults() {
            return new LlmProperties(DEFAULT_MAX_QUESTION_CHARS, DEFAULT_FALLBACK_ENABLED);
        }
    }
}
