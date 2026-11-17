package com.documind.service.ingestion;

import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.event.DocumentUploadedEvent;
import com.documind.infrastructure.vector.DocumentChunkVectorRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Coordinates short atomic PostgreSQL transactions for document ingestion state transitions
 * ({@code PENDING -> PROCESSING -> COMPLETED / FAILED}), stale/partial chunk purging, startup crash recovery,
 * and scope-aware cache version increments.
 *
 * <p>Uses {@link ObjectProvider} lazy lookups so container-less smoke profiles ({@code @ActiveProfiles("test")},
 * which exclude {@code DataSourceAutoConfiguration}) can load the Spring context without eager JPA beans, and
 * consistently locks the parent {@code documents} row before mutating {@code document_chunks} to prevent deadlocks
 * with concurrent {@code DELETE ... ON DELETE CASCADE} operations.
 */
@Service
public class DocumentIngestionStateService {

    public record RecoverySummary(int failedProcessingCount, List<DocumentUploadedEvent> pendingEventsToRedrive) {
    }

    private final Supplier<DocumentRepository> documentRepositoryLookup;
    private final Supplier<DepartmentRepository> departmentRepositorySupplier;
    private final Supplier<DocumentChunkVectorRepository> chunkVectorRepositorySupplier;
    private final Supplier<TransactionTemplate> transactionTemplateSupplier;

    @Autowired
    public DocumentIngestionStateService(
        ObjectProvider<DocumentRepository> documentRepositoryProvider,
        ObjectProvider<DepartmentRepository> departmentRepositoryProvider,
        ObjectProvider<DocumentChunkVectorRepository> chunkVectorRepositoryProvider,
        ObjectProvider<TransactionTemplate> transactionTemplateProvider
    ) {
        Objects.requireNonNull(documentRepositoryProvider, "documentRepositoryProvider must not be null");
        Objects.requireNonNull(departmentRepositoryProvider, "departmentRepositoryProvider must not be null");
        Objects.requireNonNull(chunkVectorRepositoryProvider, "chunkVectorRepositoryProvider must not be null");
        Objects.requireNonNull(transactionTemplateProvider, "transactionTemplateProvider must not be null");
        this.documentRepositoryLookup = documentRepositoryProvider::getIfAvailable;
        this.departmentRepositorySupplier = departmentRepositoryProvider::getObject;
        this.chunkVectorRepositorySupplier = chunkVectorRepositoryProvider::getObject;
        this.transactionTemplateSupplier = transactionTemplateProvider::getObject;
    }

    public DocumentIngestionStateService(
        DocumentRepository documentRepository,
        DepartmentRepository departmentRepository,
        DocumentChunkVectorRepository chunkVectorRepository,
        TransactionTemplate transactionTemplate
    ) {
        Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        Objects.requireNonNull(departmentRepository, "departmentRepository must not be null");
        Objects.requireNonNull(chunkVectorRepository, "chunkVectorRepository must not be null");
        Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.documentRepositoryLookup = () -> documentRepository;
        this.departmentRepositorySupplier = () -> departmentRepository;
        this.chunkVectorRepositorySupplier = () -> chunkVectorRepository;
        this.transactionTemplateSupplier = () -> transactionTemplate;
    }

    public boolean isPersistenceAvailable() {
        return documentRepositoryLookup.get() != null;
    }

    public Optional<DocumentIngestionContext> tryStartProcessing(UUID documentId, int docVersion) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (docVersion < 1) {
            throw new IllegalArgumentException("docVersion must be >= 1");
        }
        return transactionTemplateSupplier.get().execute(status -> {
            DocumentRepository docRepo = documentRepository();
            int updated = docRepo.tryMarkProcessing(documentId, docVersion);
            if (updated == 0) {
                return Optional.empty();
            }
            DocumentEntity doc = docRepo.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Claimed document missing in same transaction: " + documentId));
            chunkVectorRepositorySupplier.get().deleteByDocumentIdAndVersion(documentId, docVersion);
            return Optional.of(new DocumentIngestionContext(
                doc.getId(),
                doc.getDepartmentId(),
                doc.getStorageKey(),
                doc.getOriginalName(),
                doc.getFileExtension(),
                doc.getAccessLevel(),
                doc.getCurrentVersion()
            ));
        });
    }

    public boolean isActiveProcessingVersion(UUID documentId, int docVersion) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (docVersion < 1) {
            return false;
        }
        return documentRepository().existsByIdAndCurrentVersionAndStatus(documentId, docVersion, DocumentStatus.PROCESSING);
    }

    public void purgeVersionChunks(UUID documentId, int docVersion) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (docVersion < 1) {
            return;
        }
        transactionTemplateSupplier.get().executeWithoutResult(status ->
            chunkVectorRepositorySupplier.get().deleteByDocumentIdAndVersion(documentId, docVersion)
        );
    }

    public boolean markCompleted(DocumentIngestionContext context, int chunkCount) {
        Objects.requireNonNull(context, "context must not be null");
        if (chunkCount < 1) {
            throw new IllegalArgumentException("chunkCount must be >= 1");
        }
        Boolean completed = transactionTemplateSupplier.get().execute(status -> {
            DocumentRepository docRepo = documentRepository();
            DocumentChunkVectorRepository chunkRepo = chunkVectorRepositorySupplier.get();

            int updated = docRepo.markCompleted(context.documentId(), context.docVersion(), chunkCount);
            if (updated == 0) {
                chunkRepo.deleteByDocumentIdAndVersion(context.documentId(), context.docVersion());
                return false;
            }
            chunkRepo.deleteByDocumentIdAndVersionFromIndex(context.documentId(), context.docVersion(), chunkCount);
            DocumentEntity current = docRepo.findById(context.documentId()).orElse(null);
            AccessLevel effectiveAccess = current != null ? current.getAccessLevel() : context.accessLevel();
            UUID effectiveDeptId = current != null ? current.getDepartmentId() : context.departmentId();
            if (effectiveAccess == AccessLevel.PUBLIC) {
                departmentRepositorySupplier.get().incrementAllCacheVersions();
            } else {
                departmentRepositorySupplier.get().incrementCacheVersion(effectiveDeptId);
            }
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    public boolean markFailed(UUID documentId, int docVersion, String errorMessage) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (docVersion < 1) {
            return false;
        }
        Boolean failed = transactionTemplateSupplier.get().execute(status -> {
            int updated = documentRepository().markFailed(documentId, docVersion, errorMessage);
            chunkVectorRepositorySupplier.get().deleteByDocumentIdAndVersion(documentId, docVersion);
            return updated > 0;
        });
        return Boolean.TRUE.equals(failed);
    }

    public RecoverySummary recoverStaleDocuments(OffsetDateTime cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        if (!isPersistenceAvailable()) {
            return new RecoverySummary(0, List.of());
        }
        DocumentRepository docRepo = documentRepository();
        List<DocumentEntity> staleProcessing = docRepo.findStaleProcessingBefore(cutoff);
        int failedCount = 0;
        for (DocumentEntity staleDoc : staleProcessing) {
            UUID docId = staleDoc.getId();
            int version = staleDoc.getCurrentVersion();
            Boolean markedFailed = transactionTemplateSupplier.get().execute(status -> {
                int updated = docRepo.markStaleProcessingFailed(
                    docId,
                    version,
                    cutoff,
                    IngestionFailureFormatter.INTERRUPTED_ERROR_MESSAGE
                );
                if (updated > 0) {
                    chunkVectorRepositorySupplier.get().deleteByDocumentIdAndVersion(docId, version);
                    return true;
                }
                return false;
            });
            if (Boolean.TRUE.equals(markedFailed)) {
                failedCount++;
            }
        }

        List<DocumentEntity> stalePending = docRepo.findStalePendingBefore(cutoff);
        List<DocumentUploadedEvent> redriveEvents = new ArrayList<>(stalePending.size());
        for (DocumentEntity pendingDoc : stalePending) {
            redriveEvents.add(new DocumentUploadedEvent(
                pendingDoc.getId(),
                pendingDoc.getDepartmentId(),
                pendingDoc.getStorageKey(),
                pendingDoc.getOriginalName(),
                pendingDoc.getFileExtension(),
                pendingDoc.getAccessLevel(),
                pendingDoc.getCurrentVersion()
            ));
        }
        return new RecoverySummary(failedCount, List.copyOf(redriveEvents));
    }

    private DocumentRepository documentRepository() {
        DocumentRepository repo = documentRepositoryLookup.get();
        if (repo == null) {
            throw new IllegalStateException("DocumentRepository bean is unavailable in current Spring context");
        }
        return repo;
    }
}
