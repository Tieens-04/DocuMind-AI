package com.documind.service.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration properties governing extraction safety bounds, token chunking behavior, and async worker retries.
 */
@Validated
@ConfigurationProperties(prefix = "documind.ingestion")
public record IngestionProperties(
    ExtractionProperties extraction,
    ChunkingProperties chunking,
    WorkerProperties worker
) {

    public static final long DEFAULT_MAX_SPOOL_BYTES = 52_428_800L;
    public static final int DEFAULT_MAX_EXTRACTED_CHARS = 5_000_000;
    public static final double DEFAULT_ZIP_MIN_INFLATE_RATIO = 0.01d;
    public static final long DEFAULT_ZIP_MAX_ENTRY_SIZE_BYTES = 104_857_600L;
    public static final long DEFAULT_ZIP_MAX_FILE_COUNT = 1_000L;

    public static final int DEFAULT_CHUNK_SIZE = 512;
    public static final int DEFAULT_CHUNK_OVERLAP = 100;
    public static final int DEFAULT_MAX_CHUNKS_PER_DOCUMENT = 10_000;

    public static final boolean DEFAULT_WORKER_ENABLED = true;
    public static final int DEFAULT_MAX_CONCURRENT_DOCUMENTS = 10;
    public static final int DEFAULT_BATCH_SIZE = 50;
    public static final int DEFAULT_MAX_CONCURRENT_BATCHES = 10;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0d;
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(5);
    public static final Duration DEFAULT_RECOVERY_STALE_AFTER = Duration.ofMinutes(15);

    @ConstructorBinding
    public IngestionProperties {
        extraction = extraction != null ? extraction : ExtractionProperties.defaults();
        chunking = chunking != null ? chunking : ChunkingProperties.defaults();
        worker = worker != null ? worker : WorkerProperties.defaults();
    }

    public IngestionProperties(ExtractionProperties extraction, ChunkingProperties chunking) {
        this(extraction, chunking, WorkerProperties.defaults());
    }

    public static IngestionProperties defaults() {
        return new IngestionProperties(
            ExtractionProperties.defaults(),
            ChunkingProperties.defaults(),
            WorkerProperties.defaults()
        );
    }

    public record ExtractionProperties(
        long maxSpoolBytes,
        int maxExtractedChars,
        double zipMinInflateRatio,
        long zipMaxEntrySizeBytes,
        long zipMaxFileCount
    ) {

        public ExtractionProperties {
            if (maxSpoolBytes <= 0L) {
                throw new IllegalArgumentException("maxSpoolBytes must be > 0");
            }
            if (maxExtractedChars <= 0) {
                throw new IllegalArgumentException("maxExtractedChars must be > 0");
            }
            if (Double.isNaN(zipMinInflateRatio) || zipMinInflateRatio < 0.0d || zipMinInflateRatio > 1.0d) {
                throw new IllegalArgumentException("zipMinInflateRatio must be between 0.0 and 1.0");
            }
            if (zipMaxEntrySizeBytes <= 0L) {
                throw new IllegalArgumentException("zipMaxEntrySizeBytes must be > 0");
            }
            if (zipMaxFileCount <= 0L) {
                throw new IllegalArgumentException("zipMaxFileCount must be > 0");
            }
        }

        public static ExtractionProperties defaults() {
            return new ExtractionProperties(
                DEFAULT_MAX_SPOOL_BYTES,
                DEFAULT_MAX_EXTRACTED_CHARS,
                DEFAULT_ZIP_MIN_INFLATE_RATIO,
                DEFAULT_ZIP_MAX_ENTRY_SIZE_BYTES,
                DEFAULT_ZIP_MAX_FILE_COUNT
            );
        }
    }

    public record ChunkingProperties(
        int chunkSize,
        int chunkOverlap,
        int maxChunksPerDocument
    ) {

        public ChunkingProperties {
            if (chunkSize < 32) {
                throw new IllegalArgumentException("chunkSize must be >= 32");
            }
            if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
                throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize");
            }
            if (maxChunksPerDocument < 1) {
                throw new IllegalArgumentException("maxChunksPerDocument must be >= 1");
            }
        }

        public static ChunkingProperties defaults() {
            return new ChunkingProperties(
                DEFAULT_CHUNK_SIZE,
                DEFAULT_CHUNK_OVERLAP,
                DEFAULT_MAX_CHUNKS_PER_DOCUMENT
            );
        }
    }

    public record WorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int maxConcurrentDocuments,
        @DefaultValue("50") int batchSize,
        @DefaultValue("10") int maxConcurrentBatches,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500ms") Duration initialBackoff,
        @DefaultValue("2.0") double backoffMultiplier,
        @DefaultValue("5s") Duration maxBackoff,
        @DefaultValue("15m") Duration recoveryStaleAfter
    ) {

        @ConstructorBinding
        public WorkerProperties {
            if (maxConcurrentDocuments < 1) {
                throw new IllegalArgumentException("maxConcurrentDocuments must be >= 1");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be >= 1");
            }
            if (maxConcurrentBatches < 1) {
                throw new IllegalArgumentException("maxConcurrentBatches must be >= 1");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
                throw new IllegalArgumentException("initialBackoff must be > 0");
            }
            if (Double.isNaN(backoffMultiplier) || Double.isInfinite(backoffMultiplier) || backoffMultiplier < 1.0d) {
                throw new IllegalArgumentException("backoffMultiplier must be finite and >= 1.0");
            }
            if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("maxBackoff must be >= initialBackoff");
            }
            if (recoveryStaleAfter == null || recoveryStaleAfter.isNegative() || recoveryStaleAfter.isZero()) {
                throw new IllegalArgumentException("recoveryStaleAfter must be > 0");
            }
        }

        public WorkerProperties(
            boolean enabled,
            int batchSize,
            int maxConcurrentBatches,
            int maxAttempts,
            Duration initialBackoff,
            double backoffMultiplier,
            Duration maxBackoff
        ) {
            this(
                enabled,
                DEFAULT_MAX_CONCURRENT_DOCUMENTS,
                batchSize,
                maxConcurrentBatches,
                maxAttempts,
                initialBackoff,
                backoffMultiplier,
                maxBackoff,
                DEFAULT_RECOVERY_STALE_AFTER
            );
        }

        public static WorkerProperties defaults() {
            return new WorkerProperties(
                DEFAULT_WORKER_ENABLED,
                DEFAULT_MAX_CONCURRENT_DOCUMENTS,
                DEFAULT_BATCH_SIZE,
                DEFAULT_MAX_CONCURRENT_BATCHES,
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_BACKOFF_MULTIPLIER,
                DEFAULT_MAX_BACKOFF,
                DEFAULT_RECOVERY_STALE_AFTER
            );
        }
    }
}
