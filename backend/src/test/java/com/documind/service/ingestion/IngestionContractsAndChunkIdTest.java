package com.documind.service.ingestion;

import com.documind.domain.enums.AccessLevel;
import com.documind.domain.event.DocumentUploadedEvent;
import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionContractsAndChunkIdTest {

    @Test
    void ingestionPropertiesDefaultsAndValidationBoundsWorkAsExpected() {
        IngestionProperties defaults = IngestionProperties.defaults();
        assertThat(defaults.extraction().maxSpoolBytes()).isEqualTo(52_428_800L);
        assertThat(defaults.extraction().maxExtractedChars()).isEqualTo(5_000_000);
        assertThat(defaults.extraction().zipMinInflateRatio()).isEqualTo(0.01d);
        assertThat(defaults.extraction().zipMaxEntrySizeBytes()).isEqualTo(104_857_600L);
        assertThat(defaults.extraction().zipMaxFileCount()).isEqualTo(1_000L);
        assertThat(defaults.chunking().chunkSize()).isEqualTo(800);
        assertThat(defaults.chunking().chunkOverlap()).isEqualTo(150);
        assertThat(defaults.chunking().maxChunksPerDocument()).isEqualTo(10_000);
        assertThat(defaults.worker().enabled()).isTrue();
        assertThat(defaults.worker().maxConcurrentDocuments()).isEqualTo(10);
        assertThat(defaults.worker().batchSize()).isEqualTo(50);
        assertThat(defaults.worker().maxConcurrentBatches()).isEqualTo(10);
        assertThat(defaults.worker().maxAttempts()).isEqualTo(3);
        assertThat(defaults.worker().initialBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(defaults.worker().backoffMultiplier()).isEqualTo(2.0d);
        assertThat(defaults.worker().maxBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.worker().recoveryStaleAfter()).isEqualTo(Duration.ofMinutes(15));

        IngestionProperties nullNestedLegacy = new IngestionProperties(null, null);
        assertThat(nullNestedLegacy).isEqualTo(defaults);
        IngestionProperties nullNestedFull = new IngestionProperties(null, null, null);
        assertThat(nullNestedFull).isEqualTo(defaults);

        assertThatThrownBy(() -> new IngestionProperties.ChunkingProperties(800, 800, 10_000))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.ChunkingProperties(800, -1, 10_000))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.ChunkingProperties(31, 0, 10_000))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.ChunkingProperties(800, 150, 0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IngestionProperties.ExtractionProperties(0L, 5_000_000, 0.01d, 100L, 10L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.ExtractionProperties(1024L, 0, 0.01d, 100L, 10L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.ExtractionProperties(1024L, 1000, 1.01d, 100L, 10L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.ExtractionProperties(1024L, 1000, 0.01d, 0L, 10L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.ExtractionProperties(1024L, 1000, 0.01d, 100L, 0L))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 0, 10, 3, Duration.ofMillis(500), 2.0d, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 0, 3, Duration.ofMillis(500), 2.0d, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 0, Duration.ofMillis(500), 2.0d, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 3, Duration.ZERO, 2.0d, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 3, Duration.ofMillis(-10), 2.0d, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 3, null, 2.0d, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 3, Duration.ofMillis(500), 0.99d, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 3, Duration.ofMillis(500), Double.NaN, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 3, Duration.ofMillis(500), Double.POSITIVE_INFINITY, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 3, Duration.ofMillis(500), 2.0d, Duration.ofMillis(400)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 50, 10, 3, Duration.ofMillis(500), 2.0d, null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 0, 50, 10, 3, Duration.ofMillis(500), 2.0d, Duration.ofSeconds(5), Duration.ofMinutes(15)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 10, 50, 10, 3, Duration.ofMillis(500), 2.0d, Duration.ofSeconds(5), Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 10, 50, 10, 3, Duration.ofMillis(500), 2.0d, Duration.ofSeconds(5), Duration.ofSeconds(-1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionProperties.WorkerProperties(
            true, 10, 50, 10, 3, Duration.ofMillis(500), 2.0d, Duration.ofSeconds(5), null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentIngestionContextMapsAndNormalizesEventAndRejectsInvalidInputs() {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        DocumentUploadedEvent event = new DocumentUploadedEvent(
            docId,
            deptId,
            "departments/" + deptId + "/upload/v2/so_tay.pdf",
            "Sổ tay nhân sự #2026.PDF",
            " .PDF ",
            AccessLevel.DEPARTMENT,
            2
        );

        DocumentIngestionContext ctx = DocumentIngestionContext.from(event);
        assertThat(ctx.documentId()).isEqualTo(docId);
        assertThat(ctx.departmentId()).isEqualTo(deptId);
        assertThat(ctx.storageKey()).isEqualTo(event.storageKey());
        assertThat(ctx.originalName()).isEqualTo("Sổ tay nhân sự #2026.PDF");
        assertThat(ctx.fileExtension()).isEqualTo("pdf");
        assertThat(ctx.accessLevel()).isEqualTo(AccessLevel.DEPARTMENT);
        assertThat(ctx.docVersion()).isEqualTo(2);

        assertThatThrownBy(() -> DocumentIngestionContext.from(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentIngestionContext(
            docId, deptId, "key", "file.pdf", "pdf", AccessLevel.PUBLIC, 0
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentIngestionContext(
            docId, deptId, " ", "file.pdf", "pdf", AccessLevel.PUBLIC, 1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractedSegmentAndMetadataKeysAndExtractionExceptionEnforceContracts() {
        ExtractedSegment segment = new ExtractedSegment("Nội dung tiếng Việt", 1, "   ");
        assertThat(segment.content()).isEqualTo("Nội dung tiếng Việt");
        assertThat(segment.pageNumber()).isEqualTo(1);
        assertThat(segment.sectionHeading()).isNull();

        ExtractedSegment trimmedHeading = new ExtractedSegment("Đoạn văn", null, "  Chương 1  ");
        assertThat(trimmedHeading.sectionHeading()).isEqualTo("Chương 1");

        assertThatThrownBy(() -> new ExtractedSegment("   ", 1, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedSegment("Valid", 0, null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(ChunkMetadataKeys.DOCUMENT_ID).isEqualTo("document_id");
        assertThat(ChunkMetadataKeys.DEPARTMENT_ID).isEqualTo("department_id");
        assertThat(ChunkMetadataKeys.ACCESS_LEVEL).isEqualTo("access_level");
        assertThat(ChunkMetadataKeys.FILE_NAME).isEqualTo("file_name");
        assertThat(ChunkMetadataKeys.DOC_VERSION).isEqualTo("doc_version");
        assertThat(ChunkMetadataKeys.CHUNK_INDEX).isEqualTo("chunk_index");
        assertThat(ChunkMetadataKeys.PAGE_NUMBER).isEqualTo("page_number");
        assertThat(ChunkMetadataKeys.SECTION_HEADING).isEqualTo("section_heading");

        RuntimeException cause = new RuntimeException("boom");
        DocumentExtractionException ex = new DocumentExtractionException(
            DocumentExtractionErrorCode.NO_EXTRACTABLE_TEXT,
            "No text",
            cause
        );
        assertThat(ex.errorCode()).isEqualTo(DocumentExtractionErrorCode.NO_EXTRACTABLE_TEXT);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void chunkIdGeneratorIsDeterministicAndIsolatedAcrossDocumentVersionAndIndex() {
        UUID docA = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
        UUID docB = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

        UUID firstCall = ChunkIdGenerator.generate(docA, 1, 0);
        UUID secondCall = ChunkIdGenerator.generate(docA, 1, 0);
        UUID expected = UUID.nameUUIDFromBytes(
            ("documind:chunk:" + docA + ":1:0").getBytes(StandardCharsets.UTF_8)
        );

        assertThat(firstCall).isEqualTo(secondCall).isEqualTo(expected);
        assertThat(ChunkIdGenerator.generate(docA, 1, 1)).isNotEqualTo(firstCall);
        assertThat(ChunkIdGenerator.generate(docA, 2, 0)).isNotEqualTo(firstCall);
        assertThat(ChunkIdGenerator.generate(docB, 1, 0)).isNotEqualTo(firstCall);

        assertThatThrownBy(() -> ChunkIdGenerator.generate(null, 1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChunkIdGenerator.generate(docA, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChunkIdGenerator.generate(docA, 1, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
