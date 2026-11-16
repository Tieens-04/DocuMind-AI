package com.documind.service.document;

import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.controller.dto.PageResponse;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.event.DocumentUploadedEvent;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.DocumentNotFoundException;
import com.documind.exception.DuplicateDocumentException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.storage.HashingBoundedInputStream;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Coordinates two-phase non-transactional MinIO S3 streaming and short atomic DB commits for document CRUD.
 */
@Service
public class DocumentService {

    private static final int INITIAL_DOCUMENT_VERSION = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_IP_LENGTH = 45;
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_NO_CONTENT = 204;

    private final Supplier<DocumentRepository> documentRepositorySupplier;
    private final Supplier<DepartmentRepository> departmentRepositorySupplier;
    private final Supplier<UserRepository> userRepositorySupplier;
    private final Supplier<AuditLogRepository> auditLogRepositorySupplier;
    private final FileValidationService fileValidationService;
    private final MinioStorageService minioStorageService;
    private final Supplier<TransactionTemplate> transactionTemplateSupplier;
    private final ApplicationEventPublisher eventPublisher;
    private final Environment environment;

    @Autowired
    public DocumentService(
        ObjectProvider<DocumentRepository> documentRepositoryProvider,
        ObjectProvider<DepartmentRepository> departmentRepositoryProvider,
        ObjectProvider<UserRepository> userRepositoryProvider,
        ObjectProvider<AuditLogRepository> auditLogRepositoryProvider,
        FileValidationService fileValidationService,
        MinioStorageService minioStorageService,
        ObjectProvider<TransactionTemplate> transactionTemplateProvider,
        ApplicationEventPublisher eventPublisher,
        Environment environment
    ) {
        this(
            () -> Objects.requireNonNull(documentRepositoryProvider, "documentRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(departmentRepositoryProvider, "departmentRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(userRepositoryProvider, "userRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(auditLogRepositoryProvider, "auditLogRepositoryProvider must not be null").getObject(),
            fileValidationService,
            minioStorageService,
            () -> Objects.requireNonNull(transactionTemplateProvider, "transactionTemplateProvider must not be null").getObject(),
            eventPublisher,
            environment
        );
    }

    public DocumentService(
        DocumentRepository documentRepository,
        DepartmentRepository departmentRepository,
        UserRepository userRepository,
        AuditLogRepository auditLogRepository,
        FileValidationService fileValidationService,
        MinioStorageService minioStorageService,
        TransactionTemplate transactionTemplate,
        ApplicationEventPublisher eventPublisher,
        Environment environment
    ) {
        this(
            () -> Objects.requireNonNull(documentRepository, "documentRepository must not be null"),
            () -> Objects.requireNonNull(departmentRepository, "departmentRepository must not be null"),
            () -> Objects.requireNonNull(userRepository, "userRepository must not be null"),
            () -> Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null"),
            fileValidationService,
            minioStorageService,
            () -> Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null"),
            eventPublisher,
            environment
        );
    }

    private DocumentService(
        Supplier<DocumentRepository> documentRepositorySupplier,
        Supplier<DepartmentRepository> departmentRepositorySupplier,
        Supplier<UserRepository> userRepositorySupplier,
        Supplier<AuditLogRepository> auditLogRepositorySupplier,
        FileValidationService fileValidationService,
        MinioStorageService minioStorageService,
        Supplier<TransactionTemplate> transactionTemplateSupplier,
        ApplicationEventPublisher eventPublisher,
        Environment environment
    ) {
        this.documentRepositorySupplier = documentRepositorySupplier;
        this.departmentRepositorySupplier = departmentRepositorySupplier;
        this.userRepositorySupplier = userRepositorySupplier;
        this.auditLogRepositorySupplier = auditLogRepositorySupplier;
        this.fileValidationService = Objects.requireNonNull(fileValidationService, "fileValidationService must not be null");
        this.minioStorageService = Objects.requireNonNull(minioStorageService, "minioStorageService must not be null");
        this.transactionTemplateSupplier = transactionTemplateSupplier;
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    /**
     * Validates and streams an uploaded document to MinIO outside any DB transaction,
     * then commits metadata, cache invalidation, audit log, and {@link DocumentUploadedEvent} atomically.
     */
    public DocumentResponse uploadDocument(MultipartFile file, AccessLevel accessLevel, CallerContext caller) {
        long startedAtNanos = System.nanoTime();
        requireCallerWithDepartment(caller);
        if (file == null) {
            throw new InvalidDocumentRequestException("Uploaded file must not be null");
        }
        AccessLevel effectiveAccessLevel = accessLevel != null ? accessLevel : AccessLevel.DEPARTMENT;

        // Pre-check outside Phase 2 transaction so zero DB connections are held during S3 I/O
        DepartmentEntity department = departmentRepository().findById(caller.departmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + caller.departmentId()));
        UserEntity uploadedBy = resolveOptionalUploader(caller.userId());

        // Phase 1: Validate bounded 64 KiB header and stream once through SHA-256 + 50 MiB guard into MinIO
        StagedUpload stagedUpload = streamToStorage(file, department.getId());

        // Phase 2: Short atomic DB commit with compensating S3 deletion on duplicate or failure
        try {
            return transactionTemplate().execute(status -> persistDocumentMetadata(
                department,
                uploadedBy,
                stagedUpload,
                effectiveAccessLevel,
                caller.ipAddress(),
                startedAtNanos
            ));
        } catch (DataIntegrityViolationException ex) {
            minioStorageService.deleteObjectQuietly(stagedUpload.storageKey());
            // Query winner outside the rolled-back transaction (PostgreSQL aborts statements in failed tx)
            Optional<DocumentEntity> winner = documentRepository()
                .findByDepartmentIdAndFileHash(department.getId(), stagedUpload.fileHash());
            if (winner.isPresent()) {
                throw new DuplicateDocumentException(
                    winner.get().getId(),
                    stagedUpload.fileHash(),
                    "Duplicate document already exists in department " + department.getId()
                );
            }
            throw ex;
        } catch (RuntimeException ex) {
            minioStorageService.deleteObjectQuietly(stagedUpload.storageKey());
            throw ex;
        }
    }

    /**
     * Returns paginated documents filtered by optional department, status, and access level.
     */
    public PageResponse<DocumentResponse> listDocuments(
        UUID filterDepartmentId,
        DocumentStatus status,
        AccessLevel accessLevel,
        int page,
        int size,
        CallerContext caller
    ) {
        requireCaller(caller);
        if (page < 0) {
            throw new InvalidDocumentRequestException("Page index must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidDocumentRequestException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }

        UUID effectiveDepartmentId = filterDepartmentId != null
            ? filterDepartmentId
            : (caller.explicitDepartment() ? caller.departmentId() : null);

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentResponse> mapped = documentRepository()
            .findAllFiltered(effectiveDepartmentId, status, accessLevel, pageable)
            .map(DocumentResponse::from);
        return PageResponse.from(mapped);
    }

    /**
     * Returns ingestion status for a document, enforcing cross-department isolation for non-{@code PUBLIC} files.
     */
    public DocumentStatusResponse getDocumentStatus(UUID documentId, CallerContext caller) {
        if (documentId == null) {
            throw new InvalidDocumentRequestException("Document ID must not be null");
        }
        requireCaller(caller);

        DocumentEntity document = documentRepository().findById(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (isCrossDepartmentCaller(document, caller) && document.getAccessLevel() != AccessLevel.PUBLIC) {
            throw new DocumentAccessDeniedException(
                "Access denied to document " + documentId + " from department " + caller.departmentId()
            );
        }
        return DocumentStatusResponse.from(document);
    }

    /**
     * Hard-deletes a document (cascading {@code document_chunks}), bumps cache versions, logs {@code DOC_DELETE},
     * and deletes the MinIO object strictly in {@link TransactionSynchronization#afterCommit()}.
     */
    public void deleteDocument(UUID documentId, CallerContext caller) {
        long startedAtNanos = System.nanoTime();
        if (documentId == null) {
            throw new InvalidDocumentRequestException("Document ID must not be null");
        }
        requireCaller(caller);

        transactionTemplate().executeWithoutResult(status -> {
            DocumentEntity document = documentRepository().findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
            if (isCrossDepartmentCaller(document, caller)) {
                throw new DocumentAccessDeniedException(
                    "Cannot delete document " + documentId + " owned by another department"
                );
            }

            String storageKey = document.getStorageKey();
            UUID documentDepartmentId = document.getDepartmentId();
            DepartmentEntity department = document.getDepartment();
            AccessLevel accessLevel = document.getAccessLevel();
            String originalName = document.getOriginalName();
            UserEntity auditUser = caller.userId() != null
                ? userRepository().findById(caller.userId()).orElse(null)
                : null;

            documentRepository().delete(document);
            saveAuditLog(
                auditUser,
                department,
                AuditActionType.DOC_DELETE,
                originalName,
                HTTP_NO_CONTENT,
                caller.ipAddress(),
                startedAtNanos
            );
            // Increment after delete/audit while entities remain attached (query clears persistence context)
            incrementCacheVersion(accessLevel, documentDepartmentId);

            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException(
                    "Transaction synchronization must be active when scheduling post-commit storage deletion"
                );
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    minioStorageService.deleteObjectQuietly(storageKey);
                }
            });
        });
    }

    private StagedUpload streamToStorage(MultipartFile file, UUID departmentId) {
        UUID uploadUuid = UUID.randomUUID();
        String storageKey = null;
        boolean uploadedToStorage = false;

        try {
            try (InputStream rawStream = file.getInputStream()) {
                FileValidationResult validation = fileValidationService.validateAndPrepareStream(
                    file.getOriginalFilename(),
                    file.getSize(),
                    rawStream
                );
                storageKey = minioStorageService.buildStorageKey(
                    departmentId,
                    uploadUuid,
                    INITIAL_DOCUMENT_VERSION,
                    validation.sanitizedFileName()
                );

                try (HashingBoundedInputStream hashingStream = new HashingBoundedInputStream(
                    validation.rewindedStream(),
                    HashingBoundedInputStream.MAX_FILE_SIZE_BYTES
                )) {
                    minioStorageService.uploadStream(
                        storageKey,
                        hashingStream,
                        validation.declaredSizeBytes(),
                        validation.canonicalMimeType()
                    );
                    uploadedToStorage = true;
                    long actualBytesRead = hashingStream.bytesRead();
                    String fileHash = hashingStream.sha256Hex();
                    return new StagedUpload(
                        storageKey,
                        validation.originalName(),
                        validation.fileExtension(),
                        validation.canonicalMimeType(),
                        actualBytesRead,
                        fileHash
                    );
                }
            } catch (IOException ex) {
                throw new InvalidDocumentRequestException("Failed to read uploaded file stream: " + ex.getMessage());
            }
        } catch (RuntimeException ex) {
            if (uploadedToStorage && storageKey != null) {
                minioStorageService.deleteObjectQuietly(storageKey);
            }
            throw ex;
        }
    }

    private DocumentResponse persistDocumentMetadata(
        DepartmentEntity department,
        UserEntity uploadedBy,
        StagedUpload stagedUpload,
        AccessLevel accessLevel,
        String ipAddress,
        long startedAtNanos
    ) {
        Optional<DocumentEntity> existing = documentRepository()
            .findByDepartmentIdAndFileHash(department.getId(), stagedUpload.fileHash());
        if (existing.isPresent()) {
            throw new DuplicateDocumentException(
                existing.get().getId(),
                stagedUpload.fileHash(),
                "Duplicate document already exists in department " + department.getId()
            );
        }

        DocumentEntity entity = new DocumentEntity();
        entity.setDepartment(department);
        entity.setUploadedBy(uploadedBy);
        entity.setOriginalName(stagedUpload.originalName());
        entity.setStorageKey(stagedUpload.storageKey());
        entity.setFileExtension(stagedUpload.fileExtension());
        entity.setMimeType(stagedUpload.canonicalMimeType());
        entity.setFileSizeBytes(stagedUpload.fileSizeBytes());
        entity.setFileHash(stagedUpload.fileHash());
        entity.setAccessLevel(accessLevel);
        entity.setStatus(DocumentStatus.PENDING);
        entity.setCurrentVersion(INITIAL_DOCUMENT_VERSION);
        entity.setChunkCount(0);

        DocumentEntity saved = documentRepository().saveAndFlush(entity);
        incrementCacheVersion(accessLevel, department.getId());
        saveAuditLog(
            uploadedBy,
            department,
            AuditActionType.DOC_UPLOAD,
            saved.getOriginalName(),
            HTTP_ACCEPTED,
            ipAddress,
            startedAtNanos
        );
        eventPublisher.publishEvent(new DocumentUploadedEvent(
            saved.getId(),
            department.getId(),
            saved.getStorageKey(),
            saved.getOriginalName(),
            saved.getFileExtension(),
            saved.getAccessLevel(),
            saved.getCurrentVersion()
        ));
        return DocumentResponse.from(saved);
    }

    private UserEntity resolveOptionalUploader(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository().findById(userId)
            .orElseGet(() -> {
                if (!environment.acceptsProfiles(Profiles.of("prod"))
                    && DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID.equals(userId)) {
                    return null;
                }
                throw new ResourceNotFoundException("User not found: " + userId);
            });
    }

    private void incrementCacheVersion(AccessLevel accessLevel, UUID departmentId) {
        if (accessLevel == AccessLevel.PUBLIC) {
            departmentRepository().incrementAllCacheVersions();
        } else {
            departmentRepository().incrementCacheVersion(departmentId);
        }
    }

    private void saveAuditLog(
        UserEntity user,
        DepartmentEntity department,
        AuditActionType actionType,
        String queryText,
        int statusCode,
        String ipAddress,
        long startedAtNanos
    ) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setUser(user);
        auditLog.setDepartment(department);
        auditLog.setActionType(actionType);
        auditLog.setQueryText(queryText);
        auditLog.setStatusCode(statusCode);
        auditLog.setIpAddress(truncateIp(ipAddress));
        auditLog.setLatencyMs(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)));
        auditLogRepository().save(auditLog);
    }

    private static String truncateIp(String ipAddress) {
        if (ipAddress == null) {
            return null;
        }
        String trimmed = ipAddress.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_IP_LENGTH ? trimmed.substring(0, MAX_IP_LENGTH) : trimmed;
    }

    private static boolean isCrossDepartmentCaller(DocumentEntity document, CallerContext caller) {
        return caller.explicitDepartment() && !Objects.equals(caller.departmentId(), document.getDepartmentId());
    }

    private static void requireCaller(CallerContext caller) {
        if (caller == null) {
            throw new InvalidDocumentRequestException("Caller context must not be null");
        }
    }

    private static void requireCallerWithDepartment(CallerContext caller) {
        requireCaller(caller);
        if (caller.departmentId() == null) {
            throw new InvalidDocumentRequestException("Caller departmentId must not be null");
        }
    }

    private DocumentRepository documentRepository() {
        return documentRepositorySupplier.get();
    }

    private DepartmentRepository departmentRepository() {
        return departmentRepositorySupplier.get();
    }

    private UserRepository userRepository() {
        return userRepositorySupplier.get();
    }

    private AuditLogRepository auditLogRepository() {
        return auditLogRepositorySupplier.get();
    }

    private TransactionTemplate transactionTemplate() {
        return transactionTemplateSupplier.get();
    }

    private record StagedUpload(
        String storageKey,
        String originalName,
        String fileExtension,
        String canonicalMimeType,
        long fileSizeBytes,
        String fileHash
    ) {
    }
}
