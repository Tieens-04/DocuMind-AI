package com.documind.service.ingestion;

import com.documind.domain.event.DocumentUploadedEvent;
import com.documind.exception.DocumentExtractionException;
import com.documind.infrastructure.config.IngestionAsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous Virtual-Thread worker that handles post-commit document upload events, bounds concurrent document
 * admissions, embeds retryable partitions of {@code batchSize = 50} outside long-lived DB transactions, and maintains
 * atomic {@code PENDING -> PROCESSING -> COMPLETED / FAILED} state transitions plus startup crash recovery.
 *
 * <p><b>Downstream Retrieval Contract ({@code DA-10}):</b> Because each {@code batchSize = 50} upsert commits
 * outside a multi-minute DB transaction to prevent HikariCP starvation, downstream {@code RagService} retrieval must
 * verify candidate hits against {@code documents.status = 'COMPLETED' AND documents.current_version = doc_version}
 * so partially ingested or superseded versions are never cited before completion.
 */
@Service
@ConditionalOnProperty(prefix = "documind.ingestion.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionWorker.class);

    private final DocumentIngestionStateService stateService;
    private final ChunkBatchEmbeddingService batchEmbeddingService;
    private final SmartChunkingService smartChunkingService;
    private final IngestionProperties properties;
    private final AsyncTaskExecutor ingestionExecutor;
    private final Semaphore documentConcurrencySemaphore;
    private final AtomicInteger activeWorkers = new AtomicInteger();

    @Autowired
    public DocumentIngestionWorker(
        DocumentIngestionStateService stateService,
        ChunkBatchEmbeddingService batchEmbeddingService,
        SmartChunkingService smartChunkingService,
        IngestionProperties properties,
        @Qualifier(IngestionAsyncConfig.INGESTION_TASK_EXECUTOR) AsyncTaskExecutor ingestionExecutor
    ) {
        this.stateService = Objects.requireNonNull(stateService, "stateService must not be null");
        this.batchEmbeddingService = Objects.requireNonNull(
            batchEmbeddingService,
            "batchEmbeddingService must not be null"
        );
        this.smartChunkingService = Objects.requireNonNull(smartChunkingService, "smartChunkingService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.ingestionExecutor = Objects.requireNonNull(ingestionExecutor, "ingestionExecutor must not be null");
        this.documentConcurrencySemaphore = new Semaphore(properties.worker().maxConcurrentDocuments());
    }

    public DocumentIngestionWorker(
        DocumentIngestionStateService stateService,
        ChunkBatchEmbeddingService batchEmbeddingService,
        SmartChunkingService smartChunkingService,
        IngestionProperties properties
    ) {
        this(
            stateService,
            batchEmbeddingService,
            smartChunkingService,
            properties,
            new VirtualThreadTaskExecutor(IngestionAsyncConfig.VIRTUAL_THREAD_PREFIX)
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleDocumentsOnStartup() {
        if (!stateService.isPersistenceAvailable()) {
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.worker().recoveryStaleAfter());
        recoverStaleDocuments(cutoff);
    }

    public DocumentIngestionStateService.RecoverySummary recoverStaleDocuments(OffsetDateTime cutoff) {
        DocumentIngestionStateService.RecoverySummary summary = stateService.recoverStaleDocuments(cutoff);
        if (summary.failedProcessingCount() > 0 || !summary.pendingEventsToRedrive().isEmpty()) {
            log.warn(
                "Recovered stale ingestion documents (cutoff={}): markedFailedProcessing={} redrivingPending={}",
                cutoff,
                summary.failedProcessingCount(),
                summary.pendingEventsToRedrive().size()
            );
        }
        for (DocumentUploadedEvent pendingEvent : summary.pendingEventsToRedrive()) {
            ingestionExecutor.execute(() -> onDocumentUploaded(pendingEvent));
        }
        return summary;
    }

    int activeWorkerCount() {
        return activeWorkers.get();
    }

    @Async(IngestionAsyncConfig.INGESTION_TASK_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        if (event == null || event.documentId() == null) {
            return;
        }
        activeWorkers.incrementAndGet();
        long startedAtNanos = System.nanoTime();
        boolean permitAcquired = false;
        try {
            try {
                documentConcurrencySemaphore.acquire();
                permitAcquired = true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for document ingestion permit", ex);
            }
            runPipeline(event, startedAtNanos);
        } catch (Exception ex) {
            handleFailure(event, ex);
        } catch (Error err) {
            handleFailure(event, err);
            throw err;
        } finally {
            if (permitAcquired) {
                documentConcurrencySemaphore.release();
            }
            activeWorkers.decrementAndGet();
        }
    }

    private void runPipeline(DocumentUploadedEvent event, long startedAtNanos) {
        Optional<DocumentIngestionContext> claimed = stateService.tryStartProcessing(
            event.documentId(),
            event.version()
        );
        if (claimed.isEmpty()) {
            log.debug(
                "Skipping ingestion for documentId={} version={}: already claimed, superseded, or deleted",
                event.documentId(),
                event.version()
            );
            return;
        }

        DocumentIngestionContext context = claimed.get();
        List<Document> chunks = extractAndChunk(context);

        int batchSize = properties.worker().batchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            if (!stateService.isActiveProcessingVersion(context.documentId(), context.docVersion())) {
                stateService.purgeVersionChunks(context.documentId(), context.docVersion());
                log.info(
                    "Aborting remaining embedding batches for documentId={} version={}: no longer active in PROCESSING",
                    context.documentId(),
                    context.docVersion()
                );
                return;
            }
            int end = Math.min(start + batchSize, chunks.size());
            batchEmbeddingService.embedAndStoreBatch(chunks.subList(start, end));
        }

        boolean completed = stateService.markCompleted(context, chunks.size());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        if (completed) {
            log.info(
                "Completed async ingestion for documentId={} version={} chunks={} elapsedMs={} thread={} virtual={}",
                context.documentId(),
                context.docVersion(),
                chunks.size(),
                elapsedMs,
                Thread.currentThread().getName(),
                Thread.currentThread().isVirtual()
            );
        } else {
            log.info(
                "Document {} version={} was deleted or superseded before completion commit (elapsedMs={})",
                context.documentId(),
                context.docVersion(),
                elapsedMs
            );
        }
    }

    private List<Document> extractAndChunk(DocumentIngestionContext context) {
        List<ExtractedSegment> segments = batchEmbeddingService.extractWithRetry(context);
        return smartChunkingService.chunk(context, segments);
    }

    private void handleFailure(DocumentUploadedEvent event, Throwable failure) {
        String formattedError = IngestionFailureFormatter.format(failure);
        try {
            boolean marked = stateService.markFailed(event.documentId(), event.version(), formattedError);
            if (!marked) {
                log.info(
                    "Document {} version={} was deleted or superseded before marking FAILED",
                    event.documentId(),
                    event.version()
                );
                return;
            }
            if (failure instanceof DocumentExtractionException) {
                log.warn(
                    "Document ingestion failed deterministically for documentId={} version={}: {}",
                    event.documentId(),
                    event.version(),
                    formattedError
                );
            } else {
                log.error(
                    "Document ingestion failed for documentId={} version={}: {}",
                    event.documentId(),
                    event.version(),
                    formattedError,
                    failure
                );
            }
        } catch (Throwable cleanupEx) {
            log.error(
                "Failed to persist FAILED state for documentId={} version={} after primary failure",
                event.documentId(),
                event.version(),
                cleanupEx
            );
        }
    }
}
