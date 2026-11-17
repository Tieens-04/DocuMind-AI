package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import com.documind.exception.DocumentStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionFailureFormatterTest {

    @Test
    void formatsAllDocumentExtractionErrorCodesWithBracketedPrefix() {
        for (DocumentExtractionErrorCode code : DocumentExtractionErrorCode.values()) {
            String formatted = IngestionFailureFormatter.format(
                new DocumentExtractionException(code, "Document validation failed for sample.pdf")
            );
            assertThat(formatted)
                .startsWith("[" + code.name() + "] ")
                .contains("Document validation failed for sample.pdf");
        }
    }

    @Test
    void formatsStorageEmbeddingAndGenericFailuresWithoutLeakingInternalDetails() {
        String storageFormatted = IngestionFailureFormatter.format(
            new DocumentStorageException("Failed to fetch s3://internal-bucket/secret at http://localhost:9000", new IOException("C:\\Users\\admin\\tmp\\secret.pdf"))
        );
        assertThat(storageFormatted)
            .isEqualTo("[STORAGE_ERROR] Failed to read document from object storage after retry attempts.")
            .doesNotContain("localhost:9000")
            .doesNotContain("C:\\Users");

        String transientAiFormatted = IngestionFailureFormatter.format(
            new TransientAiException("POST http://localhost:11434/api/embed failed with key sk-secret")
        );
        assertThat(transientAiFormatted)
            .isEqualTo("[EMBEDDING_ERROR] Failed to generate vector embeddings for document chunks.")
            .doesNotContain("11434")
            .doesNotContain("sk-secret");

        assertThat(IngestionFailureFormatter.format(new NonTransientAiException("400 bad request")))
            .isEqualTo("[EMBEDDING_ERROR] Failed to generate vector embeddings for document chunks.");
        assertThat(IngestionFailureFormatter.format(new RestClientException("timeout")))
            .isEqualTo("[EMBEDDING_ERROR] Failed to generate vector embeddings for document chunks.");

        String genericFormatted = IngestionFailureFormatter.format(
            new RuntimeException("SELECT * FROM documents WHERE id = '123' failed")
        );
        assertThat(genericFormatted)
            .isEqualTo("[INGESTION_ERROR] Unexpected error while processing document.")
            .doesNotContain("SELECT *");

        String missingKeyFormatted = IngestionFailureFormatter.format(
            new DocumentStorageException(
                "Missing S3 key s3://internal-bucket/secret",
                software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder().message("NoSuchKey").build()
            )
        );
        assertThat(missingKeyFormatted)
            .isEqualTo("[STORAGE_OBJECT_MISSING] Document file was not found in object storage.")
            .doesNotContain("internal-bucket");

        String metadataConflictFormatted = IngestionFailureFormatter.format(
            new org.springframework.dao.DataIntegrityViolationException("ERROR: 23514 metadata mismatch on document_chunks")
        );
        assertThat(metadataConflictFormatted)
            .isEqualTo("[METADATA_CONFLICT] Document metadata changed or conflicted during ingestion.")
            .doesNotContain("23514")
            .doesNotContain("document_chunks");

        assertThat(IngestionFailureFormatter.format(new StackOverflowError("deep pdf recursion")))
            .isEqualTo("[INGESTION_ERROR] Unexpected error while processing document.");

        assertThat(IngestionFailureFormatter.format(null))
            .isEqualTo("[INGESTION_ERROR] Unexpected error while processing document.");
    }

    @Test
    void stripsNullCharactersNewlinesAndTruncatesLongExtractionMessages() {
        String rawDetail = "Line1\u0000\r\nLine2\t" + "a".repeat(1_500);
        String formatted = IngestionFailureFormatter.format(
            new DocumentExtractionException(DocumentExtractionErrorCode.CORRUPT_DOCUMENT, rawDetail)
        );

        assertThat(formatted)
            .startsWith("[CORRUPT_DOCUMENT] Line1 Line2 ")
            .doesNotContain("\u0000")
            .doesNotContain("\n")
            .doesNotContain("\r");
        assertThat(formatted.length()).isLessThanOrEqualTo(IngestionFailureFormatter.MAX_ERROR_MESSAGE_LENGTH);
        assertThat(formatted).endsWith("...");
    }
}
