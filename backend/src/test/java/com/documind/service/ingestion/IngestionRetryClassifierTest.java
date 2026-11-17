package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import com.documind.exception.DocumentStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionRetryClassifierTest {

    private final IngestionProperties properties = new IngestionProperties(
        IngestionProperties.ExtractionProperties.defaults(),
        IngestionProperties.ChunkingProperties.defaults(),
        new IngestionProperties.WorkerProperties(
            true,
            50,
            10,
            4,
            Duration.ofMillis(250),
            2.5d,
            Duration.ofSeconds(4)
        )
    );
    private final IngestionRetryClassifier classifier = new IngestionRetryClassifier(properties);

    @Test
    void exposesWorkerRetryAndBackoffSettings() {
        assertThat(classifier.maxAttempts()).isEqualTo(4);
        assertThat(classifier.initialBackoffMillis()).isEqualTo(250L);
        assertThat(classifier.backoffMultiplier()).isEqualTo(2.5d);
        assertThat(classifier.maxBackoffMillis()).isEqualTo(4_000L);
    }

    @Test
    void extractionRetriesTransientStorageErrorsButNeverNoSuchKeyOrDeterministicExtractionErrors() {
        assertThat(classifier.shouldRetryExtraction(
            new DocumentStorageException("S3 connection reset", new IOException("Connection reset"))
        )).isTrue();
        assertThat(classifier.shouldRetryExtraction(
            new RuntimeException("Wrapped transient storage", new DocumentStorageException("S3 timeout", new IOException("timeout")))
        )).isTrue();

        assertThat(classifier.shouldRetryExtraction(
            new DocumentStorageException("Object missing", NoSuchKeyException.builder().message("missing").build())
        )).isFalse();
        assertThat(classifier.shouldRetryExtraction(
            new DocumentExtractionException(DocumentExtractionErrorCode.EMPTY_DOCUMENT, "0 bytes")
        )).isFalse();
        assertThat(classifier.shouldRetryExtraction(
            new DocumentExtractionException(DocumentExtractionErrorCode.CORRUPT_DOCUMENT, "corrupt")
        )).isFalse();
        assertThat(classifier.shouldRetryExtraction(
            new DocumentExtractionException(DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT, "locked")
        )).isFalse();
        assertThat(classifier.shouldRetryExtraction(new IllegalArgumentException("bad arg"))).isFalse();
        assertThat(classifier.shouldRetryExtraction(null)).isFalse();
    }

    @Test
    void batchEmbeddingRetriesTransientAiNetworkHttpAndDbErrorsButStopsOnDeterministicErrors() {
        assertThat(classifier.shouldRetryBatch(new TransientAiException("Ollama overloaded"))).isTrue();
        assertThat(classifier.shouldRetryBatch(new ResourceAccessException("Connection refused"))).isTrue();
        assertThat(classifier.shouldRetryBatch(
            HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "503", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        )).isTrue();
        assertThat(classifier.shouldRetryBatch(
            HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "500", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        )).isTrue();
        assertThat(classifier.shouldRetryBatch(
            HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "429", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        )).isTrue();
        assertThat(classifier.shouldRetryBatch(
            HttpClientErrorException.create(HttpStatus.REQUEST_TIMEOUT, "408", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        )).isTrue();
        assertThat(classifier.shouldRetryBatch(new QueryTimeoutException("statement timeout"))).isTrue();
        assertThat(classifier.shouldRetryBatch(new CannotAcquireLockException("deadlock"))).isTrue();
        assertThat(classifier.shouldRetryBatch(new RecoverableDataAccessException("connection dropped"))).isTrue();
        assertThat(classifier.shouldRetryBatch(
            new RuntimeException("Wrapped network issue", new ResourceAccessException("read timed out"))
        )).isTrue();

        assertThat(classifier.shouldRetryBatch(
            new NonTransientAiException("429 - rate limit exceeded")
        )).isTrue();
        assertThat(classifier.shouldRetryBatch(
            new NonTransientAiException("408 - request timed out")
        )).isTrue();

        assertThat(classifier.shouldRetryBatch(new NonTransientAiException("Invalid embedding model"))).isFalse();
        assertThat(classifier.shouldRetryBatch(new NonTransientAiException("400 - bad request"))).isFalse();
        assertThat(classifier.shouldRetryBatch(new DataIntegrityViolationException("FK violation 23503"))).isFalse();
        assertThat(classifier.shouldRetryBatch(
            new RuntimeException("Wrapped FK delete", new DataIntegrityViolationException("23503"))
        )).isFalse();
        assertThat(classifier.shouldRetryBatch(
            HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "400", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        )).isFalse();
        assertThat(classifier.shouldRetryBatch(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "404", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        )).isFalse();
        assertThat(classifier.shouldRetryBatch(
            new DocumentExtractionException(DocumentExtractionErrorCode.NO_EXTRACTABLE_TEXT, "scanned")
        )).isFalse();
        assertThat(classifier.shouldRetryBatch(new IllegalArgumentException("null batch"))).isFalse();
        assertThat(classifier.shouldRetryBatch(null)).isFalse();
    }
}
