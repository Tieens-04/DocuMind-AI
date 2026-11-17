package com.documind.service.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Spring AOP retry and bounded-concurrency boundary for non-transactional ingestion I/O stages
 * (MinIO S3 extraction and {@code batchSize = 50} vector persistence).
 *
 * <p>Each {@link #embedAndStoreBatch(List)} call defines the atomic retry/upsert unit of up to {@code 50} chunks;
 * {@code PgVectorStore} may internally sub-batch HTTP embedding requests according to its {@code BatchingStrategy}
 * token budget while still upserting deterministic chunk IDs idempotently. Lazy {@link ObjectProvider} lookup keeps
 * container-less {@code @ActiveProfiles("test")} contexts loadable without an active {@link VectorStore} bean.
 */
@Service
public class ChunkBatchEmbeddingService {

    private final CompositeDocumentExtractor compositeDocumentExtractor;
    private final Supplier<VectorStore> vectorStoreSupplier;
    private final Semaphore batchConcurrencySemaphore;

    @Autowired
    public ChunkBatchEmbeddingService(
        CompositeDocumentExtractor compositeDocumentExtractor,
        ObjectProvider<VectorStore> vectorStoreProvider,
        IngestionProperties properties
    ) {
        this.compositeDocumentExtractor = Objects.requireNonNull(
            compositeDocumentExtractor,
            "compositeDocumentExtractor must not be null"
        );
        Objects.requireNonNull(vectorStoreProvider, "vectorStoreProvider must not be null");
        this.vectorStoreSupplier = vectorStoreProvider::getObject;
        IngestionProperties validatedProperties = Objects.requireNonNull(properties, "properties must not be null");
        this.batchConcurrencySemaphore = new Semaphore(validatedProperties.worker().maxConcurrentBatches());
    }

    public ChunkBatchEmbeddingService(
        CompositeDocumentExtractor compositeDocumentExtractor,
        VectorStore vectorStore,
        IngestionProperties properties
    ) {
        this.compositeDocumentExtractor = Objects.requireNonNull(
            compositeDocumentExtractor,
            "compositeDocumentExtractor must not be null"
        );
        Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.vectorStoreSupplier = () -> vectorStore;
        IngestionProperties validatedProperties = Objects.requireNonNull(properties, "properties must not be null");
        this.batchConcurrencySemaphore = new Semaphore(validatedProperties.worker().maxConcurrentBatches());
    }

    @Retryable(
        exceptionExpression = "@ingestionRetryClassifier.shouldRetryExtraction(#root)",
        maxAttemptsExpression = "@ingestionRetryClassifier.maxAttempts()",
        backoff = @Backoff(
            delayExpression = "@ingestionRetryClassifier.initialBackoffMillis()",
            multiplierExpression = "@ingestionRetryClassifier.backoffMultiplier()",
            maxDelayExpression = "@ingestionRetryClassifier.maxBackoffMillis()"
        )
    )
    public List<ExtractedSegment> extractWithRetry(DocumentIngestionContext context) {
        return compositeDocumentExtractor.extract(context);
    }

    @Retryable(
        exceptionExpression = "@ingestionRetryClassifier.shouldRetryBatch(#root)",
        maxAttemptsExpression = "@ingestionRetryClassifier.maxAttempts()",
        backoff = @Backoff(
            delayExpression = "@ingestionRetryClassifier.initialBackoffMillis()",
            multiplierExpression = "@ingestionRetryClassifier.backoffMultiplier()",
            maxDelayExpression = "@ingestionRetryClassifier.maxBackoffMillis()"
        )
    )
    public void embedAndStoreBatch(List<Document> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            batchConcurrencySemaphore.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for embedding batch permit", ex);
        }
        try {
            vectorStoreSupplier.get().add(List.copyOf(batch));
        } finally {
            batchConcurrencySemaphore.release();
        }
    }
}
