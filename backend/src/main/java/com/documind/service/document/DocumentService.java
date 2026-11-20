package com.documind.service.document;

import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.UpdateDocumentMetadataRequest;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Coordinates two-phase non-transactional MinIO S3 streaming and short atomic DB commits for document CRUD
 * with server-enforced department and role authorization.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
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
     * Validates RBAC permissions and streams an uploaded document to MinIO outside any DB transaction,
     * then commits metadata, cache invalidation, audit log, and {@link DocumentUploadedEvent} atomically.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER')")
    public DocumentResponse uploadDocument(MultipartFile file, AccessLevel accessLevel, CallerContext caller) {
        return uploadDocument(file, accessLevel, null, caller);
    }

    /**
     * Validates RBAC permissions (including optional target department for {@code SYSTEM_ADMIN})
     * and executes two-phase streaming upload + atomic metadata persistence.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER')")
    public DocumentResponse uploadDocument(
        MultipartFile file,
        AccessLevel accessLevel,
        UUID targetDepartmentId,
        CallerContext caller
    ) {
        long startedAtNanos = System.nanoTime();
        requireCaller(caller);
        if (!caller.canManageDocuments()) {
            throw new DocumentAccessDeniedException("Insufficient permissions to upload documents");
        }
        UUID effectiveDepartmentId = resolveUploadDepartmentId(targetDepartmentId, caller);
        if (file == null) {
            throw new InvalidDocumentRequestException("Uploaded file must not be null");
        }
        AccessLevel effectiveAccessLevel = accessLevel != null ? accessLevel : AccessLevel.DEPARTMENT;

        // Pre-check outside Phase 2 transaction so zero DB connections are held during S3 I/O
        DepartmentEntity department = departmentRepository().findById(effectiveDepartmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + effectiveDepartmentId));
        UserEntity uploadedBy = resolveVerifiedActor(caller, department.getId());

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
                    "A document with identical content already exists in this department"
                );
            }
            throw ex;
        } catch (RuntimeException ex) {
            minioStorageService.deleteObjectQuietly(stagedUpload.storageKey());
            throw ex;
        }
    }

    /**
     * Returns paginated documents scoped by caller role visibility ({@code SYSTEM_ADMIN}: all;
     * {@code DEPARTMENT_MANAGER}: {@code PUBLIC} or own department; {@code STANDARD_EMPLOYEE}: {@code PUBLIC}
     * or own-department {@code DEPARTMENT}, never {@code CONFIDENTIAL}) intersected with optional filters.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public PageResponse<DocumentResponse> listDocuments(
        UUID filterDepartmentId,
        DocumentStatus status,
        AccessLevel accessLevel,
        int page,
        int size,
        CallerContext caller
    ) {
        requireCaller(caller);
        requireCallerDepartment(caller);
        if (page < 0) {
            throw new InvalidDocumentRequestException("Page index must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidDocumentRequestException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        resolveVerifiedActor(caller, caller.isSystemAdmin() ? null : caller.departmentId());

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentResponse> mapped = documentRepository()
            .findAllVisible(
                caller.departmentId(),
                caller.role(),
                filterDepartmentId,
                status,
                accessLevel,
                pageable
            )
            .map(DocumentResponse::from);
        return PageResponse.from(mapped);
    }

    /**
     * Returns full metadata for a single document, enforcing role and classification isolation via {@link DocumentVisibilityPolicy}.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public DocumentResponse getDocument(UUID documentId, CallerContext caller) {
        return DocumentResponse.from(loadReadableDocument(documentId, caller));
    }

    /**
     * Returns ingestion status for a document, enforcing role and classification isolation via {@link DocumentVisibilityPolicy}.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public DocumentStatusResponse getDocumentStatus(UUID documentId, CallerContext caller) {
        return DocumentStatusResponse.from(loadReadableDocument(documentId, caller));
    }

    private DocumentEntity loadReadableDocument(UUID documentId, CallerContext caller) {
        if (documentId == null) {
            throw new InvalidDocumentRequestException("Document ID must not be null");
        }
        requireCaller(caller);
        requireCallerDepartment(caller);
        resolveVerifiedActor(caller, caller.isSystemAdmin() ? null : caller.departmentId());

        DocumentEntity document = documentRepository().findById(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!DocumentVisibilityPolicy.canRead(caller, document.getDepartmentId(), document.getAccessLevel())) {
            throw new DocumentAccessDeniedException("You do not have permission to access this document");
        }
        return document;
    }

    /**
     * Updates document metadata ({@code originalName}, {@code accessLevel}, and {@code departmentId} for {@code SYSTEM_ADMIN}).
     * Relies on the PostgreSQL V6 trigger {@code trg_propagate_document_rbac_to_chunks} to atomically sync
     * {@code document_chunks.metadata} and increment {@code departments.cache_version} with deadlock-free lock ordering.
     * If ingestion is actively {@code PROCESSING} when chunk-synced metadata changes, increments {@code currentVersion},
     * resets status to {@code PENDING}, and republishes {@link DocumentUploadedEvent} so in-flight workers fence cleanly.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER')")
    public DocumentResponse updateDocumentMetadata(
        UUID documentId,
        UpdateDocumentMetadataRequest request,
        CallerContext caller
    ) {
        if (documentId == null) {
            throw new InvalidDocumentRequestException("Document ID must not be null");
        }
        if (request == null
            || (request.originalName() == null
                && request.accessLevel() == null
                && request.departmentId() == null)) {
            throw new InvalidDocumentRequestException(
                "At least one updatable field (originalName, accessLevel, departmentId) must be provided"
            );
        }
        requireCaller(caller);
        if (!caller.canManageDocuments()) {
            throw new DocumentAccessDeniedException("Insufficient permissions to update documents");
        }
        requireCallerDepartment(caller);

        AtomicReference<TransferTarget> transferTargetRef = new AtomicReference<>();

        try {
            DocumentResponse updated = transactionTemplate().execute(status -> {
                resolveVerifiedActor(caller, caller.isSystemAdmin() ? null : caller.departmentId());
                DocumentEntity document = documentRepository().findByIdForUpdate(documentId)
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));

                if (!caller.isSystemAdmin() && !Objects.equals(caller.departmentId(), document.getDepartmentId())) {
                    throw new DocumentAccessDeniedException("You do not have permission to update this document");
                }

                boolean chunkSyncedMetadataChanged = false;

                if (request.departmentId() != null && !Objects.equals(request.departmentId(), document.getDepartmentId())) {
                    if (!caller.isSystemAdmin()) {
                        throw new DocumentAccessDeniedException("Only SYSTEM_ADMIN can transfer document department ownership");
                    }
                    DepartmentEntity targetDepartment = departmentRepository().findById(request.departmentId())
                        .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));
                    Optional<DocumentEntity> existingInTarget = documentRepository()
                        .findByDepartmentIdAndFileHash(targetDepartment.getId(), document.getFileHash());
                    if (existingInTarget.isPresent()) {
                        throw new DuplicateDocumentException(
                            existingInTarget.get().getId(),
                            document.getFileHash(),
                            "A document with identical content already exists in the target department"
                        );
                    }
                    transferTargetRef.set(new TransferTarget(targetDepartment.getId(), document.getFileHash()));
                    document.setDepartment(targetDepartment);
                    chunkSyncedMetadataChanged = true;
                }

                if (request.originalName() != null) {
                    String normalizedName = normalizeAndValidateOriginalName(
                        request.originalName(),
                        document.getFileExtension()
                    );
                    if (!Objects.equals(normalizedName, document.getOriginalName())) {
                        document.setOriginalName(normalizedName);
                        chunkSyncedMetadataChanged = true;
                    }
                }

                if (request.accessLevel() != null && request.accessLevel() != document.getAccessLevel()) {
                    document.setAccessLevel(request.accessLevel());
                    chunkSyncedMetadataChanged = true;
                }

                boolean requeueActiveIngestion = document.getStatus() == DocumentStatus.PROCESSING
                    && chunkSyncedMetadataChanged;
                if (requeueActiveIngestion) {
                    int nextVersion = (document.getCurrentVersion() != null ? document.getCurrentVersion() : 1) + 1;
                    document.setCurrentVersion(nextVersion);
                    document.setStatus(DocumentStatus.PENDING);
                    document.setChunkCount(0);
                    document.setErrorMessage(null);
                }

                DocumentEntity saved = documentRepository().saveAndFlush(document);
                if (requeueActiveIngestion) {
                    eventPublisher.publishEvent(new DocumentUploadedEvent(
                        saved.getId(),
                        saved.getDepartmentId(),
                        saved.getStorageKey(),
                        saved.getOriginalName(),
                        saved.getFileExtension(),
                        saved.getAccessLevel(),
                        saved.getCurrentVersion()
                    ));
                }
                return DocumentResponse.from(saved);
            });

            log.info(
                "SECURITY_AUDIT action=DOC_METADATA_UPDATE actorUserId={} documentId={} departmentId={} accessLevel={}",
                caller.userId(),
                updated.id(),
                updated.departmentId(),
                updated.accessLevel()
            );
            return updated;
        } catch (DataIntegrityViolationException ex) {
            TransferTarget transferTarget = transferTargetRef.get();
            if (transferTarget != null) {
                Optional<DocumentEntity> winner = documentRepository()
                    .findByDepartmentIdAndFileHash(transferTarget.departmentId(), transferTarget.fileHash());
                if (winner.isPresent()) {
                    throw new DuplicateDocumentException(
                        winner.get().getId(),
                        transferTarget.fileHash(),
                        "A document with identical content already exists in the target department"
                    );
                }
            }
            throw ex;
        }
    }

    /**
     * Hard-deletes a document (cascading {@code document_chunks}), bumps cache versions, logs {@code DOC_DELETE},
     * and deletes the MinIO object strictly in {@link TransactionSynchronization#afterCommit()}.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER')")
    public void deleteDocument(UUID documentId, CallerContext caller) {
        long startedAtNanos = System.nanoTime();
        if (documentId == null) {
            throw new InvalidDocumentRequestException("Document ID must not be null");
        }
        requireCaller(caller);
        if (!caller.canManageDocuments()) {
            throw new DocumentAccessDeniedException("Insufficient permissions to delete documents");
        }
        requireCallerDepartment(caller);

        transactionTemplate().executeWithoutResult(status -> {
            UserEntity auditUser = resolveVerifiedActor(caller, caller.isSystemAdmin() ? null : caller.departmentId());
            DocumentEntity document = documentRepository().findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
            if (!caller.isSystemAdmin() && !Objects.equals(caller.departmentId(), document.getDepartmentId())) {
                throw new DocumentAccessDeniedException("You do not have permission to delete this document");
            }

            String storageKey = document.getStorageKey();
            UUID documentDepartmentId = document.getDepartmentId();
            DepartmentEntity department = document.getDepartment();
            AccessLevel accessLevel = document.getAccessLevel();
            String originalName = document.getOriginalName();

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

    private UUID resolveUploadDepartmentId(UUID targetDepartmentId, CallerContext caller) {
        if (targetDepartmentId != null) {
            if (!caller.isSystemAdmin() && !Objects.equals(targetDepartmentId, caller.departmentId())) {
                throw new DocumentAccessDeniedException("Cannot upload documents into another department");
            }
            return targetDepartmentId;
        }
        if (caller.departmentId() == null) {
            throw new InvalidDocumentRequestException("Caller departmentId must not be null");
        }
        return caller.departmentId();
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
                log.warn("Failed to read uploaded multipart stream", ex);
                throw new InvalidDocumentRequestException("Failed to read uploaded document stream");
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
                "A document with identical content already exists in this department"
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

    private UserEntity resolveVerifiedActor(CallerContext caller, UUID targetDepartmentId) {
        UUID userId = caller.userId();
        if (userId == null) {
            return null;
        }
        UserEntity user = userRepository().findById(userId)
            .orElseGet(() -> {
                if (DevCallerFallbackEnabledCondition.isDevFallbackActive(environment)
                    && DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID.equals(userId)) {
                    return null;
                }
                throw new ResourceNotFoundException("User not found: " + userId);
            });
        if (user == null) {
            return null;
        }
        if (!user.isActive()) {
            throw new DocumentAccessDeniedException("User account is inactive");
        }
        if (!user.getRole().canAssume(caller.role())) {
            throw new DocumentAccessDeniedException("Caller role exceeds authenticated user privileges");
        }
        if (!caller.isSystemAdmin()
            && user.getRole() != Role.SYSTEM_ADMIN
            && targetDepartmentId != null
            && !Objects.equals(user.getDepartmentId(), targetDepartmentId)) {
            throw new DocumentAccessDeniedException("Caller user does not belong to target department");
        }
        return user;
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

    private static void requireCaller(CallerContext caller) {
        if (caller == null || caller.role() == null) {
            throw new InvalidDocumentRequestException("Caller context and role must not be null");
        }
    }

    private static void requireCallerDepartment(CallerContext caller) {
        if (!caller.isSystemAdmin() && caller.departmentId() == null) {
            throw new InvalidDocumentRequestException("Caller departmentId must not be null");
        }
    }

    private static String normalizeAndValidateOriginalName(String rawName, String requiredExtension) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidDocumentRequestException("Document originalName must not be blank");
        }
        String normalized = Normalizer.normalize(rawName.trim(), Normalizer.Form.NFC);
        if (normalized.length() > 255) {
            throw new InvalidDocumentRequestException("Document originalName must not exceed 255 characters");
        }
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")) {
            throw new InvalidDocumentRequestException("Document originalName contains disallowed path characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch <= 0x1F || ch == 0x7F) {
                throw new InvalidDocumentRequestException("Document originalName contains control characters");
            }
        }
        String expectedSuffix = "." + Objects.requireNonNull(requiredExtension, "fileExtension must not be null")
            .toLowerCase(Locale.ROOT);
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(expectedSuffix)
            || normalized.length() <= expectedSuffix.length()) {
            throw new InvalidDocumentRequestException(
                "Document originalName must preserve the original '" + expectedSuffix + "' file extension"
            );
        }
        return normalized;
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

    private record TransferTarget(UUID departmentId, String fileHash) {
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
