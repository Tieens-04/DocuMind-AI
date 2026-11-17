package com.documind.service.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties governing extraction safety bounds and token chunking behavior.
 */
@Validated
@ConfigurationProperties(prefix = "documind.ingestion")
public record IngestionProperties(
    ExtractionProperties extraction,
    ChunkingProperties chunking
) {

    public static final long DEFAULT_MAX_SPOOL_BYTES = 52_428_800L;
    public static final int DEFAULT_MAX_EXTRACTED_CHARS = 5_000_000;
    public static final double DEFAULT_ZIP_MIN_INFLATE_RATIO = 0.01d;
    public static final long DEFAULT_ZIP_MAX_ENTRY_SIZE_BYTES = 104_857_600L;
    public static final long DEFAULT_ZIP_MAX_FILE_COUNT = 1_000L;

    public static final int DEFAULT_CHUNK_SIZE = 800;
    public static final int DEFAULT_CHUNK_OVERLAP = 150;
    public static final int DEFAULT_MAX_CHUNKS_PER_DOCUMENT = 10_000;

    @ConstructorBinding
    public IngestionProperties {
        extraction = extraction != null ? extraction : ExtractionProperties.defaults();
        chunking = chunking != null ? chunking : ChunkingProperties.defaults();
    }

    public static IngestionProperties defaults() {
        return new IngestionProperties(ExtractionProperties.defaults(), ChunkingProperties.defaults());
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
}
