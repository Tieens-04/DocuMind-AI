package com.documind.service.ingestion;

import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.event.DocumentUploadedEvent;
import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import com.documind.infrastructure.vector.DocumentChunkVectorRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentIngestionWorkerTest {

    private static IngestionProperties workerProperties(int batchSize) {
        return new IngestionProperties(
            IngestionProperties.ExtractionProperties.defaults(),
            IngestionProperties.ChunkingProperties.defaults(),
            new IngestionProperties.WorkerProperties(
                true,
                batchSize,
                10,
                3,
                Duration.ofMillis(10),
                2.0d,
                Duration.ofMillis(50)
            )
        );
    }

    private static DocumentUploadedEvent sampleEvent(UUID docId, UUID deptId, AccessLevel accessLevel) {
        return new DocumentUploadedEvent(
            docId,
            deptId,
            "departments/" + deptId + "/" + docId + "/v1/policy.txt",
            "policy.txt",
            "txt",
            accessLevel,
            1
        );
    }

    private static DocumentIngestionContext sampleContext(DocumentUploadedEvent event) {
        return DocumentIngestionContext.from(event);
    }

    private static List<Document> generateChunks(int count) {
        List<Document> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            chunks.add(new Document(UUID.randomUUID().toString(), "Chunk " + i, Map.of("chunk_index", i)));
        }
        return chunks;
    }

    @Test
    void slices120ChunksIntoBatchesOf50_50_20AndMarksCompleted() {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        DocumentUploadedEvent event = sampleEvent(docId, deptId, AccessLevel.DEPARTMENT);
        DocumentIngestionContext ctx = sampleContext(event);

        DocumentIngestionStateService stateService = mock(DocumentIngestionStateService.class);
        ChunkBatchEmbeddingService batchService = mock(ChunkBatchEmbeddingService.class);
        SmartChunkingService chunkingService = mock(SmartChunkingService.class);

        List<ExtractedSegment> segments = List.of(new ExtractedSegment("Nội dung quy chế", null, "Chương 1"));
        List<Document> allChunks = generateChunks(120);

        when(stateService.tryStartProcessing(docId, 1)).thenReturn(Optional.of(ctx));
        when(batchService.extractWithRetry(ctx)).thenReturn(segments);
        when(chunkingService.chunk(ctx, segments)).thenReturn(allChunks);
        when(stateService.isActiveProcessingVersion(docId, 1)).thenReturn(true);
        when(stateService.markCompleted(ctx, 120)).thenReturn(true);

        DocumentIngestionWorker worker = new DocumentIngestionWorker(
            stateService,
            batchService,
            chunkingService,
            workerProperties(50)
        );
        worker.onDocumentUploaded(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> batchCaptor = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(stateService, batchService, chunkingService);
        order.verify(stateService).tryStartProcessing(docId, 1);
        order.verify(batchService).extractWithRetry(ctx);
        order.verify(chunkingService).chunk(ctx, segments);
        order.verify(stateService).isActiveProcessingVersion(docId, 1);
        order.verify(batchService).embedAndStoreBatch(batchCaptor.capture());
        order.verify(stateService).isActiveProcessingVersion(docId, 1);
        order.verify(batchService).embedAndStoreBatch(batchCaptor.capture());
        order.verify(stateService).isActiveProcessingVersion(docId, 1);
        order.verify(batchService).embedAndStoreBatch(batchCaptor.capture());
        order.verify(stateService).markCompleted(ctx, 120);

        List<List<Document>> batches = batchCaptor.getAllValues();
        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(50);
        assertThat(batches.get(1)).hasSize(50);
        assertThat(batches.get(2)).hasSize(20);
        assertThat(batches.get(0).getFirst().getText()).isEqualTo("Chunk 0");
        assertThat(batches.get(1).getFirst().getText()).isEqualTo("Chunk 50");
        assertThat(batches.get(2).getLast().getText()).isEqualTo("Chunk 119");
        verify(stateService, never()).markFailed(any(), anyInt(), anyString());
    }

    @Test
    void skipsDuplicateStaleOrNullEventsWithoutExtractingOrEmbedding() {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        DocumentUploadedEvent event = sampleEvent(docId, deptId, AccessLevel.PUBLIC);

        DocumentIngestionStateService stateService = mock(DocumentIngestionStateService.class);
        ChunkBatchEmbeddingService batchService = mock(ChunkBatchEmbeddingService.class);
        SmartChunkingService chunkingService = mock(SmartChunkingService.class);

        when(stateService.tryStartProcessing(docId, 1)).thenReturn(Optional.empty());

        DocumentIngestionWorker worker = new DocumentIngestionWorker(
            stateService,
            batchService,
            chunkingService,
            workerProperties(50)
        );
        worker.onDocumentUploaded(null);
        worker.onDocumentUploaded(event);

        verify(stateService, times(1)).tryStartProcessing(docId, 1);
        verifyNoInteractions(batchService, chunkingService);
        verify(stateService, never()).markCompleted(any(), anyInt());
        verify(stateService, never()).markFailed(any(), anyInt(), anyString());
    }

    @Test
    void marksFailedWithFormattedErrorOnDeterministicExtractionFailure() {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        DocumentUploadedEvent event = sampleEvent(docId, deptId, AccessLevel.DEPARTMENT);
        DocumentIngestionContext ctx = sampleContext(event);

        DocumentIngestionStateService stateService = mock(DocumentIngestionStateService.class);
        ChunkBatchEmbeddingService batchService = mock(ChunkBatchEmbeddingService.class);
        SmartChunkingService chunkingService = mock(SmartChunkingService.class);

        when(stateService.tryStartProcessing(docId, 1)).thenReturn(Optional.of(ctx));
        when(batchService.extractWithRetry(ctx)).thenThrow(
            new DocumentExtractionException(DocumentExtractionErrorCode.EMPTY_DOCUMENT, "Uploaded text document is blank")
        );
        when(stateService.markFailed(eq(docId), eq(1), anyString())).thenReturn(true);

        DocumentIngestionWorker worker = new DocumentIngestionWorker(
            stateService,
            batchService,
            chunkingService,
            workerProperties(50)
        );
        worker.onDocumentUploaded(event);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(stateService).markFailed(eq(docId), eq(1), errorCaptor.capture());
        assertThat(errorCaptor.getValue())
            .startsWith("[EMPTY_DOCUMENT] ")
            .contains("Uploaded text document is blank");
        verifyNoInteractions(chunkingService);
        verify(batchService, never()).embedAndStoreBatch(any());
        verify(stateService, never()).markCompleted(any(), anyInt());
    }

    @Test
    void stopsBeforeThirdBatchAndMarksFailedWhenSecondBatchExhaustsRetries() {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        DocumentUploadedEvent event = sampleEvent(docId, deptId, AccessLevel.DEPARTMENT);
        DocumentIngestionContext ctx = sampleContext(event);

        DocumentIngestionStateService stateService = mock(DocumentIngestionStateService.class);
        ChunkBatchEmbeddingService batchService = mock(ChunkBatchEmbeddingService.class);
        SmartChunkingService chunkingService = mock(SmartChunkingService.class);

        List<ExtractedSegment> segments = List.of(new ExtractedSegment("Nội dung", null, "Mục 1"));
        List<Document> allChunks = generateChunks(120);

        when(stateService.tryStartProcessing(docId, 1)).thenReturn(Optional.of(ctx));
        when(batchService.extractWithRetry(ctx)).thenReturn(segments);
        when(chunkingService.chunk(ctx, segments)).thenReturn(allChunks);
        when(stateService.isActiveProcessingVersion(docId, 1)).thenReturn(true);
        doNothing()
            .doThrow(new TransientAiException("Ollama offline"))
            .when(batchService)
            .embedAndStoreBatch(any());
        when(stateService.markFailed(eq(docId), eq(1), anyString())).thenReturn(true);

        DocumentIngestionWorker worker = new DocumentIngestionWorker(
            stateService,
            batchService,
            chunkingService,
            workerProperties(50)
        );
        worker.onDocumentUploaded(event);

        verify(batchService, times(2)).embedAndStoreBatch(any());
        verify(stateService).markFailed(docId, 1, "[EMBEDDING_ERROR] Failed to generate vector embeddings for document chunks.");
        verify(stateService, never()).markCompleted(any(), anyInt());
    }

    @Test
    void abortsGracefullyWhenDocumentDeletedMidIngestionBeforeNextBatchOrDuringInsert() {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        DocumentUploadedEvent event = sampleEvent(docId, deptId, AccessLevel.DEPARTMENT);
        DocumentIngestionContext ctx = sampleContext(event);

        DocumentIngestionStateService stateService = mock(DocumentIngestionStateService.class);
        ChunkBatchEmbeddingService batchService = mock(ChunkBatchEmbeddingService.class);
        SmartChunkingService chunkingService = mock(SmartChunkingService.class);

        List<ExtractedSegment> segments = List.of(new ExtractedSegment("Nội dung", null, null));
        List<Document> allChunks = generateChunks(75);

        when(stateService.tryStartProcessing(docId, 1)).thenReturn(Optional.of(ctx));
        when(batchService.extractWithRetry(ctx)).thenReturn(segments);
        when(chunkingService.chunk(ctx, segments)).thenReturn(allChunks);
        when(stateService.isActiveProcessingVersion(docId, 1))
            .thenReturn(true)
            .thenReturn(false);

        DocumentIngestionWorker worker = new DocumentIngestionWorker(
            stateService,
            batchService,
            chunkingService,
            workerProperties(50)
        );
        assertThatCode(() -> worker.onDocumentUploaded(event)).doesNotThrowAnyException();

        verify(batchService, times(1)).embedAndStoreBatch(any());
        verify(stateService).purgeVersionChunks(docId, 1);
        verify(stateService, never()).markCompleted(any(), anyInt());
        verify(stateService, never()).markFailed(any(), anyInt(), anyString());

        // Concurrent delete hitting FK violation during batch insert exits silently when markFailed updates 0 rows
        DocumentIngestionStateService fkStateService = mock(DocumentIngestionStateService.class);
        ChunkBatchEmbeddingService fkBatchService = mock(ChunkBatchEmbeddingService.class);
        when(fkStateService.tryStartProcessing(docId, 1)).thenReturn(Optional.of(ctx));
        when(fkBatchService.extractWithRetry(ctx)).thenReturn(segments);
        when(fkStateService.isActiveProcessingVersion(docId, 1)).thenReturn(true);
        doThrow(new DataIntegrityViolationException("23503 parent deleted"))
            .when(fkBatchService)
            .embedAndStoreBatch(any());
        when(fkStateService.markFailed(eq(docId), eq(1), anyString())).thenReturn(false);

        DocumentIngestionWorker fkWorker = new DocumentIngestionWorker(
            fkStateService,
            fkBatchService,
            chunkingService,
            workerProperties(50)
        );
        assertThatCode(() -> fkWorker.onDocumentUploaded(event)).doesNotThrowAnyException();
        verify(fkStateService).markFailed(eq(docId), eq(1), anyString());
    }

    @Test
    void stateServiceManagesTransactionsChunkCleanupAndScopeAwareCacheVersionBump() {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        DocumentRepository docRepo = mock(DocumentRepository.class);
        DepartmentRepository deptRepo = mock(DepartmentRepository.class);
        DocumentChunkVectorRepository chunkRepo = mock(DocumentChunkVectorRepository.class);
        TransactionTemplate txTemplate = new TransactionTemplate(new NoOpTransactionManager());

        DocumentIngestionStateService stateService = new DocumentIngestionStateService(
            docRepo,
            deptRepo,
            chunkRepo,
            txTemplate
        );

        DocumentEntity entity = new DocumentEntity();
        entity.setId(docId);
        entity.setStorageKey("departments/" + deptId + "/" + docId + "/v1/doc.md");
        entity.setOriginalName("doc.md");
        entity.setFileExtension("md");
        entity.setAccessLevel(AccessLevel.PUBLIC);
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setCurrentVersion(1);
        var dept = new com.documind.domain.entity.DepartmentEntity();
        dept.setId(deptId);
        entity.setDepartment(dept);

        when(docRepo.tryMarkProcessing(docId, 1)).thenReturn(1);
        when(docRepo.findById(docId)).thenReturn(Optional.of(entity));
        Optional<DocumentIngestionContext> started = stateService.tryStartProcessing(docId, 1);
        assertThat(started).isPresent();
        verify(chunkRepo).deleteByDocumentIdAndVersion(docId, 1);

        DocumentIngestionContext ctx = started.get();
        when(docRepo.markCompleted(docId, 1, 12)).thenReturn(1);
        assertThat(stateService.markCompleted(ctx, 12)).isTrue();
        InOrder completedOrder = inOrder(docRepo, chunkRepo, deptRepo);
        completedOrder.verify(docRepo).markCompleted(docId, 1, 12);
        completedOrder.verify(chunkRepo).deleteByDocumentIdAndVersionFromIndex(docId, 1, 12);
        completedOrder.verify(deptRepo).incrementAllCacheVersions();

        entity.setAccessLevel(AccessLevel.DEPARTMENT);
        assertThat(stateService.markCompleted(ctx, 12)).isTrue();
        verify(deptRepo).incrementCacheVersion(deptId);

        assertThat(stateService.markFailed(docId, 1, "[EMPTY_DOCUMENT] blank")).isFalse();
        InOrder failedOrder = inOrder(docRepo, chunkRepo);
        failedOrder.verify(docRepo).markFailed(docId, 1, "[EMPTY_DOCUMENT] blank");
        failedOrder.verify(chunkRepo).deleteByDocumentIdAndVersion(docId, 1);
    }

    @Test
    void marksFailedAndRethrowsWhenJvmErrorOccursAfterClaimingDocument() {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        DocumentUploadedEvent event = sampleEvent(docId, deptId, AccessLevel.DEPARTMENT);
        DocumentIngestionContext ctx = sampleContext(event);

        DocumentIngestionStateService stateService = mock(DocumentIngestionStateService.class);
        ChunkBatchEmbeddingService batchService = mock(ChunkBatchEmbeddingService.class);
        SmartChunkingService chunkingService = mock(SmartChunkingService.class);

        when(stateService.tryStartProcessing(docId, 1)).thenReturn(Optional.of(ctx));
        when(batchService.extractWithRetry(ctx)).thenThrow(new StackOverflowError("deep pdf object graph"));
        when(stateService.markFailed(eq(docId), eq(1), anyString())).thenReturn(true);

        DocumentIngestionWorker worker = new DocumentIngestionWorker(
            stateService,
            batchService,
            chunkingService,
            workerProperties(50)
        );

        assertThatThrownBy(() -> worker.onDocumentUploaded(event))
            .isInstanceOf(StackOverflowError.class);

        verify(stateService).markFailed(
            docId,
            1,
            "[INGESTION_ERROR] Unexpected error while processing document."
        );
        verify(stateService, never()).markCompleted(any(), anyInt());
    }

    @Test
    void capsConcurrentDocumentAdmissionsWithSemaphoreAndCompletesQueuedUploads() throws Exception {
        IngestionProperties boundedProps = new IngestionProperties(
            IngestionProperties.ExtractionProperties.defaults(),
            IngestionProperties.ChunkingProperties.defaults(),
            new IngestionProperties.WorkerProperties(
                true,
                2,
                50,
                10,
                3,
                Duration.ofMillis(10),
                2.0d,
                Duration.ofMillis(50),
                Duration.ofMinutes(15)
            )
        );

        DocumentIngestionStateService stateService = mock(DocumentIngestionStateService.class);
        ChunkBatchEmbeddingService batchService = mock(ChunkBatchEmbeddingService.class);
        SmartChunkingService chunkingService = mock(SmartChunkingService.class);

        AtomicInteger activeExtractions = new AtomicInteger();
        AtomicInteger peakExtractions = new AtomicInteger();
        CountDownLatch twoEntered = new CountDownLatch(2);
        CountDownLatch releaseGate = new CountDownLatch(1);

        List<ExtractedSegment> segments = List.of(new ExtractedSegment("Nội dung", null, null));
        List<Document> chunks = generateChunks(5);

        when(stateService.tryStartProcessing(any(), eq(1))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(new DocumentIngestionContext(
                id,
                UUID.randomUUID(),
                "departments/it/" + id + "/v1/doc.txt",
                "doc.txt",
                "txt",
                AccessLevel.DEPARTMENT,
                1
            ));
        });
        when(batchService.extractWithRetry(any())).thenAnswer(inv -> {
            int current = activeExtractions.incrementAndGet();
            peakExtractions.accumulateAndGet(current, Math::max);
            twoEntered.countDown();
            try {
                assertThat(releaseGate.await(5, TimeUnit.SECONDS)).isTrue();
                return segments;
            } finally {
                activeExtractions.decrementAndGet();
            }
        });
        when(chunkingService.chunk(any(), eq(segments))).thenReturn(chunks);
        when(stateService.isActiveProcessingVersion(any(), eq(1))).thenReturn(true);
        when(stateService.markCompleted(any(), eq(5))).thenReturn(true);

        DocumentIngestionWorker worker = new DocumentIngestionWorker(
            stateService,
            batchService,
            chunkingService,
            boundedProps
        );

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                DocumentUploadedEvent event = sampleEvent(UUID.randomUUID(), UUID.randomUUID(), AccessLevel.DEPARTMENT);
                futures.add(executor.submit(() -> worker.onDocumentUploaded(event)));
            }
            assertThat(twoEntered.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(40L);
            assertThat(peakExtractions.get()).isEqualTo(2);

            releaseGate.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(peakExtractions.get()).isEqualTo(2);
        assertThat(worker.activeWorkerCount()).isZero();
        verify(stateService, times(6)).markCompleted(any(), eq(5));
    }

    @Test
    void recoversStaleProcessingAsInterruptedFailedAndRedrivesStalePendingOnVirtualThreads() throws Exception {
        UUID staleProcessingId = UUID.randomUUID();
        UUID stalePendingId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(15);

        DocumentRepository docRepo = mock(DocumentRepository.class);
        DepartmentRepository deptRepo = mock(DepartmentRepository.class);
        DocumentChunkVectorRepository chunkRepo = mock(DocumentChunkVectorRepository.class);
        TransactionTemplate txTemplate = new TransactionTemplate(new NoOpTransactionManager());

        DocumentEntity staleProc = new DocumentEntity();
        staleProc.setId(staleProcessingId);
        staleProc.setCurrentVersion(2);
        staleProc.setStatus(DocumentStatus.PROCESSING);

        var dept = new com.documind.domain.entity.DepartmentEntity();
        dept.setId(deptId);

        DocumentEntity stalePend = new DocumentEntity();
        stalePend.setId(stalePendingId);
        stalePend.setDepartment(dept);
        stalePend.setStorageKey("departments/" + deptId + "/" + stalePendingId + "/v1/pending.md");
        stalePend.setOriginalName("pending.md");
        stalePend.setFileExtension("md");
        stalePend.setAccessLevel(AccessLevel.DEPARTMENT);
        stalePend.setCurrentVersion(1);
        stalePend.setStatus(DocumentStatus.PENDING);

        when(docRepo.findStaleProcessingBefore(cutoff)).thenReturn(List.of(staleProc));
        when(docRepo.markStaleProcessingFailed(
            staleProcessingId,
            2,
            cutoff,
            IngestionFailureFormatter.INTERRUPTED_ERROR_MESSAGE
        )).thenReturn(1);
        when(docRepo.findStalePendingBefore(cutoff)).thenReturn(List.of(stalePend));

        DocumentIngestionStateService realStateService = new DocumentIngestionStateService(
            docRepo,
            deptRepo,
            chunkRepo,
            txTemplate
        );

        CountDownLatch redrivenLatch = new CountDownLatch(1);
        when(docRepo.tryMarkProcessing(stalePendingId, 1)).thenAnswer(inv -> {
            redrivenLatch.countDown();
            return 0;
        });

        ChunkBatchEmbeddingService batchService = mock(ChunkBatchEmbeddingService.class);
        SmartChunkingService chunkingService = mock(SmartChunkingService.class);
        DocumentIngestionWorker worker = new DocumentIngestionWorker(
            realStateService,
            batchService,
            chunkingService,
            workerProperties(50)
        );

        DocumentIngestionStateService.RecoverySummary summary = worker.recoverStaleDocuments(cutoff);
        assertThat(summary.failedProcessingCount()).isEqualTo(1);
        assertThat(summary.pendingEventsToRedrive()).hasSize(1);

        InOrder recoveryOrder = inOrder(docRepo, chunkRepo);
        recoveryOrder.verify(docRepo).markStaleProcessingFailed(
            staleProcessingId,
            2,
            cutoff,
            IngestionFailureFormatter.INTERRUPTED_ERROR_MESSAGE
        );
        recoveryOrder.verify(chunkRepo).deleteByDocumentIdAndVersion(staleProcessingId, 2);

        assertThat(redrivenLatch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
