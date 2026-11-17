package com.documind.service.ingestion;

import com.documind.domain.enums.AccessLevel;
import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import com.documind.exception.DocumentStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkBatchEmbeddingServiceTest {

    private static IngestionProperties fastProperties(int maxConcurrentBatches) {
        return new IngestionProperties(
            IngestionProperties.ExtractionProperties.defaults(),
            IngestionProperties.ChunkingProperties.defaults(),
            new IngestionProperties.WorkerProperties(
                true,
                50,
                maxConcurrentBatches,
                3,
                Duration.ofMillis(15),
                2.0d,
                Duration.ofMillis(60)
            )
        );
    }

    private static DocumentIngestionContext sampleContext() {
        return new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/it/doc/v1/policy.pdf",
            "policy.pdf",
            "pdf",
            AccessLevel.DEPARTMENT,
            1
        );
    }

    private static List<Document> sampleBatch() {
        return List.of(new Document(UUID.randomUUID().toString(), "Nội dung chunk tiếng Việt", Map.of()));
    }

    @Test
    void embedAndStoreBatchRetriesTransientExceptionsWithExponentialBackoffAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingVectorStore vectorStore = new RecordingVectorStore(batch -> {
            if (attempts.incrementAndGet() < 3) {
                throw new TransientAiException("Ollama temporarily busy");
            }
        });
        CompositeDocumentExtractor extractor = mock(CompositeDocumentExtractor.class);

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(IngestionProperties.class, () -> fastProperties(4));
            ctx.registerBean(CompositeDocumentExtractor.class, () -> extractor);
            ctx.registerBean(VectorStore.class, () -> vectorStore);
            ctx.register(RetryTestConfig.class);
            ctx.refresh();

            ChunkBatchEmbeddingService service = ctx.getBean(ChunkBatchEmbeddingService.class);
            long startedNanos = System.nanoTime();
            service.embedAndStoreBatch(sampleBatch());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertThat(attempts.get()).isEqualTo(3);
            assertThat(elapsedMs).isGreaterThanOrEqualTo(30L);
        }
    }

    @Test
    void embedAndStoreBatchExhaustsRetriesOnPersistent503AndFailsFastOnNonTransientErrors() {
        AtomicInteger attempts503 = new AtomicInteger();
        RecordingVectorStore serverErrorStore = new RecordingVectorStore(batch -> {
            attempts503.incrementAndGet();
            throw HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "503", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8
            );
        });
        CompositeDocumentExtractor extractor = mock(CompositeDocumentExtractor.class);

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(IngestionProperties.class, () -> fastProperties(4));
            ctx.registerBean(CompositeDocumentExtractor.class, () -> extractor);
            ctx.registerBean(VectorStore.class, () -> serverErrorStore);
            ctx.register(RetryTestConfig.class);
            ctx.refresh();

            ChunkBatchEmbeddingService service = ctx.getBean(ChunkBatchEmbeddingService.class);
            assertThatThrownBy(() -> service.embedAndStoreBatch(sampleBatch()))
                .isInstanceOf(HttpServerErrorException.class);
            assertThat(attempts503.get()).isEqualTo(3);
        }

        AtomicInteger nonTransientAttempts = new AtomicInteger();
        RecordingVectorStore nonTransientStore = new RecordingVectorStore(batch -> {
            int call = nonTransientAttempts.incrementAndGet();
            if (call == 1) {
                throw new NonTransientAiException("Invalid embedding model");
            }
            throw new DataIntegrityViolationException("FK constraint 23503");
        });

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(IngestionProperties.class, () -> fastProperties(4));
            ctx.registerBean(CompositeDocumentExtractor.class, () -> extractor);
            ctx.registerBean(VectorStore.class, () -> nonTransientStore);
            ctx.register(RetryTestConfig.class);
            ctx.refresh();

            ChunkBatchEmbeddingService service = ctx.getBean(ChunkBatchEmbeddingService.class);
            assertThatThrownBy(() -> service.embedAndStoreBatch(sampleBatch()))
                .isInstanceOf(NonTransientAiException.class);
            assertThatThrownBy(() -> service.embedAndStoreBatch(sampleBatch()))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(nonTransientAttempts.get()).isEqualTo(2);
        }
    }

    @Test
    void extractWithRetryRetriesTransientStorageOnlyAndSkipsExtractionOrMissingKeyErrors() {
        CompositeDocumentExtractor extractor = mock(CompositeDocumentExtractor.class);
        RecordingVectorStore vectorStore = new RecordingVectorStore(batch -> {});
        DocumentIngestionContext docCtx = sampleContext();
        List<ExtractedSegment> expectedSegments = List.of(new ExtractedSegment("Nội dung hợp lệ", 1, null));

        when(extractor.extract(any()))
            .thenThrow(new DocumentStorageException("Transient S3 reset", new IOException("reset")))
            .thenThrow(new DocumentStorageException("Transient S3 reset 2", new IOException("reset")))
            .thenReturn(expectedSegments)
            .thenThrow(new DocumentStorageException(
                "Missing S3 key",
                software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder().message("missing").build()
            ))
            .thenThrow(new DocumentExtractionException(DocumentExtractionErrorCode.EMPTY_DOCUMENT, "Empty file"));

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(IngestionProperties.class, () -> fastProperties(4));
            ctx.registerBean(CompositeDocumentExtractor.class, () -> extractor);
            ctx.registerBean(VectorStore.class, () -> vectorStore);
            ctx.register(RetryTestConfig.class);
            ctx.refresh();

            ChunkBatchEmbeddingService service = ctx.getBean(ChunkBatchEmbeddingService.class);
            assertThat(service.extractWithRetry(docCtx)).isEqualTo(expectedSegments);
            assertThatThrownBy(() -> service.extractWithRetry(docCtx))
                .isInstanceOf(DocumentStorageException.class);
            assertThatThrownBy(() -> service.extractWithRetry(docCtx))
                .isInstanceOf(DocumentExtractionException.class);
            verify(extractor, times(5)).extract(docCtx);
        }
    }

    @Test
    void embedAndStoreBatchCapsConcurrentVirtualThreadsAndRestoresInterruptFlag() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();
        CountDownLatch twoEntered = new CountDownLatch(2);
        CountDownLatch releaseGate = new CountDownLatch(1);

        RecordingVectorStore concurrencyStore = new RecordingVectorStore(batch -> {
            int current = active.incrementAndGet();
            peakActive.accumulateAndGet(current, Math::max);
            twoEntered.countDown();
            try {
                if (!releaseGate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting on concurrency gate");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            } finally {
                active.decrementAndGet();
            }
        });
        CompositeDocumentExtractor extractor = mock(CompositeDocumentExtractor.class);

        ChunkBatchEmbeddingService directService = new ChunkBatchEmbeddingService(
            extractor,
            concurrencyStore,
            fastProperties(2)
        );

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> directService.embedAndStoreBatch(sampleBatch())));
            }
            assertThat(twoEntered.await(3, TimeUnit.SECONDS)).isTrue();

            // Interrupt a waiting caller while both permits are occupied
            Thread waitingCaller = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> directService.embedAndStoreBatch(sampleBatch()))
                    .isInstanceOf(IllegalStateException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            });
            waitingCaller.join(3_000L);
            assertThat(waitingCaller.isAlive()).isFalse();

            releaseGate.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(peakActive.get()).isEqualTo(2);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRetry
    static class RetryTestConfig {

        @Bean("ingestionRetryClassifier")
        IngestionRetryClassifier ingestionRetryClassifier(IngestionProperties properties) {
            return new IngestionRetryClassifier(properties);
        }

        @Bean
        ChunkBatchEmbeddingService chunkBatchEmbeddingService(
            CompositeDocumentExtractor extractor,
            VectorStore vectorStore,
            IngestionProperties properties
        ) {
            return new ChunkBatchEmbeddingService(extractor, vectorStore, properties);
        }
    }

    private static final class RecordingVectorStore implements VectorStore {

        @FunctionalInterface
        interface AddHandler {
            void onAdd(List<Document> documents);
        }

        private final AddHandler addHandler;

        private RecordingVectorStore(AddHandler addHandler) {
            this.addHandler = addHandler;
        }

        @Override
        public void add(List<Document> documents) {
            addHandler.onAdd(documents);
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }
}
