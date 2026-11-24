package com.documind.service.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration properties controlling vector retrieval bounds, excerpt sizing, LLM ask safeguards,
 * conversation memory windows, typed SSE streaming timeouts, and two-tier RBAC-scoped RAG caching.
 */
@Validated
@ConfigurationProperties(prefix = "documind.rag")
public record RagProperties(
    RetrievalProperties retrieval,
    LlmProperties llm,
    MemoryProperties memory,
    SseProperties sse,
    CacheProperties cache
) {

    public static final int DEFAULT_TOP_K = 5;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.58d;
    public static final int DEFAULT_OVERFETCH_MULTIPLIER = 2;
    public static final int DEFAULT_MAX_EXCERPT_CHARS = 500;
    public static final int DEFAULT_MAX_QUESTION_CHARS = 4000;
    public static final boolean DEFAULT_FALLBACK_ENABLED = true;
    public static final int DEFAULT_HISTORY_WINDOW_SIZE = 6;
    public static final int DEFAULT_MAX_HISTORY_MESSAGE_CHARS = 1000;
    public static final int DEFAULT_AUTO_TITLE_MAX_CHARS = 80;
    public static final Duration DEFAULT_SSE_TIMEOUT = Duration.ofSeconds(120);
    public static final boolean DEFAULT_CACHE_ENABLED = true;
    public static final Duration DEFAULT_CACHE_L1_TTL = Duration.ofMinutes(30);
    public static final Duration DEFAULT_CACHE_L2_TTL = Duration.ofHours(24);
    public static final double DEFAULT_CACHE_SEMANTIC_THRESHOLD = 0.94d;
    public static final Duration DEFAULT_CACHE_VERSION_TTL = Duration.ofHours(24);

    @ConstructorBinding
    public RagProperties {
        retrieval = retrieval != null ? retrieval : RetrievalProperties.defaults();
        llm = llm != null ? llm : LlmProperties.defaults();
        memory = memory != null ? memory : MemoryProperties.defaults();
        sse = sse != null ? sse : SseProperties.defaults();
        cache = cache != null ? cache : CacheProperties.defaults();
    }

    public RagProperties(RetrievalProperties retrieval, LlmProperties llm) {
        this(retrieval, llm, MemoryProperties.defaults(), SseProperties.defaults(), CacheProperties.defaults());
    }

    public RagProperties(RetrievalProperties retrieval, LlmProperties llm, MemoryProperties memory) {
        this(retrieval, llm, memory, SseProperties.defaults(), CacheProperties.defaults());
    }

    public RagProperties(
        RetrievalProperties retrieval,
        LlmProperties llm,
        MemoryProperties memory,
        SseProperties sse
    ) {
        this(retrieval, llm, memory, sse, CacheProperties.defaults());
    }

    public static RagProperties defaults() {
        return new RagProperties(
            RetrievalProperties.defaults(),
            LlmProperties.defaults(),
            MemoryProperties.defaults(),
            SseProperties.defaults(),
            CacheProperties.defaults()
        );
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

    public record MemoryProperties(
        @DefaultValue("6") int historyWindowSize,
        @DefaultValue("1000") int maxHistoryMessageChars,
        @DefaultValue("80") int autoTitleMaxChars
    ) {

        @ConstructorBinding
        public MemoryProperties {
            if (historyWindowSize < 1 || historyWindowSize > 20) {
                throw new IllegalArgumentException("historyWindowSize must be between 1 and 20");
            }
            if (maxHistoryMessageChars < 100 || maxHistoryMessageChars > 8000) {
                throw new IllegalArgumentException("maxHistoryMessageChars must be between 100 and 8000");
            }
            if (autoTitleMaxChars < 16 || autoTitleMaxChars > 255) {
                throw new IllegalArgumentException("autoTitleMaxChars must be between 16 and 255");
            }
        }

        public static MemoryProperties defaults() {
            return new MemoryProperties(
                DEFAULT_HISTORY_WINDOW_SIZE,
                DEFAULT_MAX_HISTORY_MESSAGE_CHARS,
                DEFAULT_AUTO_TITLE_MAX_CHARS
            );
        }
    }

    public record SseProperties(
        @DefaultValue("120s") Duration timeout
    ) {

        private static final Duration MIN_TIMEOUT = Duration.ofMillis(50);
        private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

        @ConstructorBinding
        public SseProperties {
            if (timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
                throw new IllegalArgumentException(
                    "sse.timeout must be between " + MIN_TIMEOUT + " and " + MAX_TIMEOUT
                );
            }
        }

        public static SseProperties defaults() {
            return new SseProperties(DEFAULT_SSE_TIMEOUT);
        }
    }

    public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30m") Duration l1Ttl,
        @DefaultValue("24h") Duration l2Ttl,
        @DefaultValue("0.94") double semanticSimilarityThreshold,
        @DefaultValue("24h") Duration versionTtl
    ) {

        private static final Duration MIN_TTL = Duration.ofSeconds(1);
        private static final Duration MAX_L1_TTL = Duration.ofDays(7);
        private static final Duration MAX_L2_TTL = Duration.ofDays(30);
        private static final Duration MAX_VERSION_TTL = Duration.ofDays(30);

        @ConstructorBinding
        public CacheProperties {
            if (l1Ttl == null || l1Ttl.compareTo(MIN_TTL) < 0 || l1Ttl.compareTo(MAX_L1_TTL) > 0) {
                throw new IllegalArgumentException(
                    "cache.l1Ttl must be between " + MIN_TTL + " and " + MAX_L1_TTL
                );
            }
            if (l2Ttl == null || l2Ttl.compareTo(MIN_TTL) < 0 || l2Ttl.compareTo(MAX_L2_TTL) > 0) {
                throw new IllegalArgumentException(
                    "cache.l2Ttl must be between " + MIN_TTL + " and " + MAX_L2_TTL
                );
            }
            if (!Double.isFinite(semanticSimilarityThreshold)
                || semanticSimilarityThreshold < 0.50d
                || semanticSimilarityThreshold > 1.0d) {
                throw new IllegalArgumentException(
                    "cache.semanticSimilarityThreshold must be between 0.50 and 1.0"
                );
            }
            if (versionTtl == null || versionTtl.compareTo(MIN_TTL) < 0 || versionTtl.compareTo(MAX_VERSION_TTL) > 0) {
                throw new IllegalArgumentException(
                    "cache.versionTtl must be between " + MIN_TTL + " and " + MAX_VERSION_TTL
                );
            }
        }

        public static CacheProperties defaults() {
            return new CacheProperties(
                DEFAULT_CACHE_ENABLED,
                DEFAULT_CACHE_L1_TTL,
                DEFAULT_CACHE_L2_TTL,
                DEFAULT_CACHE_SEMANTIC_THRESHOLD,
                DEFAULT_CACHE_VERSION_TTL
            );
        }
    }
}
