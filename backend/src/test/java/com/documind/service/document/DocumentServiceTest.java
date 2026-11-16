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
import com.documind.exception.DocumentStorageException;
import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.DuplicateDocumentException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.exception.UnsupportedDocumentTypeException;
import com.documind.infrastructure.storage.HashingBoundedInputStream;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentServiceTest {

    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_USER_ID = DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID;
    private static final UUID HR_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DOC_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private DocumentRepository documentRepository;
    private DepartmentRepository departmentRepository;
    private UserRepository userRepository;
    private AuditLogRepository auditLogRepository;
    private FileValidationService fileValidationService;
    private MinioStorageService minioStorageService;
    private RecordingTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private ApplicationEventPublisher eventPublisher;
    private MockEnvironment environment;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        userRepository = mock(UserRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        fileValidationService = new FileValidationService();
        minioStorageService = mock(MinioStorageService.class);
        transactionManager = new RecordingTransactionManager();
        transactionTemplate = spy(new TransactionTemplate(transactionManager));
        eventPublisher = mock(ApplicationEventPublisher.class);
        environment = new MockEnvironment();

        when(minioStorageService.buildStorageKey(any(UUID.class), any(UUID.class), anyInt(), anyString()))
            .thenAnswer(inv -> "departments/" + inv.getArgument(0) + "/" + inv.getArgument(1)
                + "/v" + inv.getArgument(2) + "/" + inv.getArgument(3));

        documentService = new DocumentService(
            documentRepository,
            departmentRepository,
            userRepository,
            auditLogRepository,
            fileValidationService,
            minioStorageService,
            transactionTemplate,
            eventPublisher,
            environment
        );
    }

    @Test
    void uploadDocumentStreamsToS3BeforeTransactionPersistsPendingEntityBumpsCacheAndPublishesEvent() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
        String expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdfBytes));
        MockMultipartFile file = new MockMultipartFile("file", "Quy che nhan su.pdf", "application/pdf", pdfBytes);
        CallerContext caller = new CallerContext(IT_DEPT_ID, ADMIN_USER_ID, true, "203.0.113.10");

        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        UserEntity admin = user(ADMIN_USER_ID, dept);
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(admin));

        AtomicBoolean txActiveDuringS3Upload = new AtomicBoolean(true);
        doAnswer(inv -> {
            txActiveDuringS3Upload.set(TransactionSynchronizationManager.isActualTransactionActive());
            InputStream stream = inv.getArgument(1);
            stream.readAllBytes();
            return null;
        }).when(minioStorageService).uploadStream(anyString(), any(InputStream.class), anyLong(), anyString());

        when(documentRepository.findByDepartmentIdAndFileHash(IT_DEPT_ID, expectedSha256)).thenReturn(Optional.empty());
        AtomicBoolean txActiveDuringSave = new AtomicBoolean(false);
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> {
            txActiveDuringSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            DocumentEntity entity = inv.getArgument(0);
            entity.setId(DOC_ID);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            return entity;
        });

        DocumentResponse response = documentService.uploadDocument(file, AccessLevel.CONFIDENTIAL, caller);

        assertThat(txActiveDuringS3Upload.get()).isFalse();
        assertThat(txActiveDuringSave.get()).isTrue();
        assertThat(response.id()).isEqualTo(DOC_ID);
        assertThat(response.departmentId()).isEqualTo(IT_DEPT_ID);
        assertThat(response.uploadedBy()).isEqualTo(ADMIN_USER_ID);
        assertThat(response.originalName()).isEqualTo("Quy che nhan su.pdf");
        assertThat(response.fileExtension()).isEqualTo("pdf");
        assertThat(response.mimeType()).isEqualTo("application/pdf");
        assertThat(response.fileSizeBytes()).isEqualTo(pdfBytes.length);
        assertThat(response.fileHash()).isEqualTo(expectedSha256);
        assertThat(response.accessLevel()).isEqualTo(AccessLevel.CONFIDENTIAL);
        assertThat(response.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.chunkCount()).isZero();

        InOrder order = inOrder(
            departmentRepository,
            minioStorageService,
            transactionTemplate,
            documentRepository,
            auditLogRepository,
            eventPublisher
        );
        order.verify(departmentRepository).findById(IT_DEPT_ID);
        order.verify(minioStorageService).uploadStream(anyString(), any(InputStream.class), eq((long) pdfBytes.length), eq("application/pdf"));
        order.verify(transactionTemplate).execute(any());
        order.verify(documentRepository).findByDepartmentIdAndFileHash(IT_DEPT_ID, expectedSha256);
        order.verify(documentRepository).saveAndFlush(any(DocumentEntity.class));
        order.verify(departmentRepository).incrementCacheVersion(IT_DEPT_ID);
        order.verify(auditLogRepository).save(any(AuditLogEntity.class));
        order.verify(eventPublisher).publishEvent(any(DocumentUploadedEvent.class));

        verify(departmentRepository, never()).incrementAllCacheVersions();

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLogEntity savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getActionType()).isEqualTo(AuditActionType.DOC_UPLOAD);
        assertThat(savedAudit.getDepartment()).isSameAs(dept);
        assertThat(savedAudit.getUser()).isSameAs(admin);
        assertThat(savedAudit.getIpAddress()).isEqualTo("203.0.113.10");
        assertThat(savedAudit.getStatusCode()).isEqualTo(202);

        ArgumentCaptor<DocumentUploadedEvent> eventCaptor = ArgumentCaptor.forClass(DocumentUploadedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        DocumentUploadedEvent published = eventCaptor.getValue();
        assertThat(published.documentId()).isEqualTo(DOC_ID);
        assertThat(published.departmentId()).isEqualTo(IT_DEPT_ID);
        assertThat(published.storageKey()).startsWith("departments/" + IT_DEPT_ID + "/").endsWith("/v1/Quy_che_nhan_su.pdf");
        assertThat(published.originalName()).isEqualTo("Quy che nhan su.pdf");
        assertThat(published.fileExtension()).isEqualTo("pdf");
        assertThat(published.accessLevel()).isEqualTo(AccessLevel.CONFIDENTIAL);
        assertThat(published.version()).isEqualTo(1);
    }

    @Test
    void uploadDocumentDefaultsAccessLevelToDepartmentAndIncrementsAllVersionsForPublic() throws Exception {
        byte[] txtBytes = "Tai lieu noi bo".getBytes(StandardCharsets.UTF_8);
        CallerContext caller = new CallerContext(IT_DEPT_ID, null, false, "127.0.0.1");
        DepartmentEntity dept = department(IT_DEPT_ID, "IT");

        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(eq(IT_DEPT_ID), anyString())).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> {
            DocumentEntity entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });

        DocumentResponse defaultAccessResponse = documentService.uploadDocument(
            new MockMultipartFile("file", "notes.txt", "text/plain", txtBytes),
            null,
            caller
        );
        assertThat(defaultAccessResponse.accessLevel()).isEqualTo(AccessLevel.DEPARTMENT);
        verify(departmentRepository).incrementCacheVersion(IT_DEPT_ID);

        DocumentResponse publicResponse = documentService.uploadDocument(
            new MockMultipartFile("file", "public.md", "text/markdown", "# Public guide".getBytes(StandardCharsets.UTF_8)),
            AccessLevel.PUBLIC,
            caller
        );
        assertThat(publicResponse.accessLevel()).isEqualTo(AccessLevel.PUBLIC);
        verify(departmentRepository).incrementAllCacheVersions();
    }

    @Test
    void uploadDocumentHandlesMissingDepartmentAndDevFallbackVsExplicitUser() {
        byte[] txtBytes = "Hello".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", txtBytes);

        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.uploadDocument(
            file, AccessLevel.DEPARTMENT, new CallerContext(IT_DEPT_ID, ADMIN_USER_ID, false, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(minioStorageService);

        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.uploadDocument(
            file, AccessLevel.DEPARTMENT, new CallerContext(IT_DEPT_ID, HR_USER_ID, true, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(minioStorageService);

        // Non-prod dev fallback user missing in custom DB nulls out uploadedBy instead of failing
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.empty());
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(eq(IT_DEPT_ID), anyString())).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> {
            DocumentEntity entity = inv.getArgument(0);
            entity.setId(DOC_ID);
            return entity;
        });

        DocumentResponse response = documentService.uploadDocument(
            file, AccessLevel.DEPARTMENT, new CallerContext(IT_DEPT_ID, ADMIN_USER_ID, false, "127.0.0.1")
        );
        assertThat(response.uploadedBy()).isNull();

        // In prod profile, missing user must throw ResourceNotFoundException even for default dev UUID
        DocumentService prodService = new DocumentService(
            documentRepository,
            departmentRepository,
            userRepository,
            auditLogRepository,
            fileValidationService,
            minioStorageService,
            transactionTemplate,
            eventPublisher,
            new MockEnvironment().withProperty("spring.profiles.active", "prod")
        );
        assertThatThrownBy(() -> prodService.uploadDocument(
            file, AccessLevel.DEPARTMENT, new CallerContext(IT_DEPT_ID, ADMIN_USER_ID, true, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadDocumentRejectsInvalidFilesBeforeCallingMinioStorage() {
        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        CallerContext caller = new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1");

        assertThatThrownBy(() -> documentService.uploadDocument(null, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(InvalidDocumentRequestException.class);

        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> documentService.uploadDocument(emptyFile, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(InvalidDocumentRequestException.class);

        byte[] pngHeader = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
        MockMultipartFile spoofedPdf = new MockMultipartFile("file", "fake.pdf", "application/pdf", pngHeader);
        assertThatThrownBy(() -> documentService.uploadDocument(spoofedPdf, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(UnsupportedDocumentTypeException.class);

        MockMultipartFile oversized = new MockMultipartFile("file", "big.pdf", "application/pdf", new byte[]{1}) {
            @Override
            public long getSize() {
                return HashingBoundedInputStream.MAX_FILE_SIZE_BYTES + 1L;
            }
        };
        assertThatThrownBy(() -> documentService.uploadDocument(oversized, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(DocumentTooLargeException.class);

        verifyNoInteractions(minioStorageService);
    }

    @Test
    void uploadDocumentDeletesStagedObjectOnPreSaveDuplicate() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdfBytes));
        UUID existingId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", pdfBytes);
        CallerContext caller = new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1");

        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        DocumentEntity winner = new DocumentEntity();
        winner.setId(existingId);
        winner.setDepartment(dept);
        winner.setFileHash(expectedHash);

        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(IT_DEPT_ID, expectedHash)).thenReturn(Optional.of(winner));

        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(DuplicateDocumentException.class)
            .satisfies(ex -> {
                DuplicateDocumentException dup = (DuplicateDocumentException) ex;
                assertThat(dup.existingDocumentId()).isEqualTo(existingId);
                assertThat(dup.fileHash()).isEqualTo(expectedHash);
            });

        verify(minioStorageService).deleteObjectQuietly(anyString());
        verify(documentRepository, never()).saveAndFlush(any());
        verify(auditLogRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void uploadDocumentResolvesRaceConditionDataIntegrityViolationIntoDuplicateAndDeletesLoserObject() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\nrace-content\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdfBytes));
        UUID winnerId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        MockMultipartFile file = new MockMultipartFile("file", "race.pdf", "application/pdf", pdfBytes);
        CallerContext caller = new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1");

        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        DocumentEntity winner = new DocumentEntity();
        winner.setId(winnerId);
        winner.setDepartment(dept);
        winner.setFileHash(expectedHash);

        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        ArgumentCaptor<String> stagedKeyCaptor = ArgumentCaptor.forClass(String.class);
        drainUploadStream();
        // First lookup (inside Phase 2 tx) finds nothing; second lookup (post-rollback) finds winner
        when(documentRepository.findByDepartmentIdAndFileHash(IT_DEPT_ID, expectedHash))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));
        when(documentRepository.saveAndFlush(any(DocumentEntity.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint uq_documents_department_hash"));

        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(DuplicateDocumentException.class)
            .satisfies(ex -> {
                DuplicateDocumentException dup = (DuplicateDocumentException) ex;
                assertThat(dup.existingDocumentId()).isEqualTo(winnerId);
                assertThat(dup.fileHash()).isEqualTo(expectedHash);
            });

        verify(minioStorageService).uploadStream(stagedKeyCaptor.capture(), any(), anyLong(), anyString());
        verify(minioStorageService).deleteObjectQuietly(stagedKeyCaptor.getValue());
        assertThat(transactionManager.rollbackCount).isEqualTo(1);
        assertThat(transactionManager.commitCount).isZero();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void uploadDocumentRethrowsNonDuplicateDataIntegrityViolationAndPropagatesStorageFailure() {
        byte[] pdfBytes = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile("file", "nondup.pdf", "application/pdf", pdfBytes);
        CallerContext caller = new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1");
        DepartmentEntity dept = department(IT_DEPT_ID, "IT");

        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(eq(IT_DEPT_ID), anyString())).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class)))
            .thenThrow(new DataIntegrityViolationException("chk_documents_size violation"));

        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(DataIntegrityViolationException.class);
        verify(minioStorageService).deleteObjectQuietly(anyString());

        clearInvocations(minioStorageService);
        doThrow(new DocumentStorageException("MinIO down", new RuntimeException("connect")))
            .when(minioStorageService).uploadStream(anyString(), any(), anyLong(), anyString());
        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(DocumentStorageException.class);
        verify(minioStorageService, never()).deleteObjectQuietly(anyString());
    }

    @Test
    void uploadDocumentDeletesStagedObjectOnUnexpectedPhase2RuntimeException() {
        byte[] txtBytes = "Unexpected DB error test".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", txtBytes);
        CallerContext caller = new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1");

        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(eq(IT_DEPT_ID), anyString())).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> {
            DocumentEntity entity = inv.getArgument(0);
            entity.setId(DOC_ID);
            return entity;
        });
        when(auditLogRepository.save(any(AuditLogEntity.class))).thenThrow(new IllegalStateException("DB connection lost"));

        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, caller))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DB connection lost");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(minioStorageService).uploadStream(keyCaptor.capture(), any(), anyLong(), anyString());
        verify(minioStorageService).deleteObjectQuietly(keyCaptor.getValue());
        assertThat(transactionManager.rollbackCount).isEqualTo(1);
        assertThat(transactionManager.commitCount).isZero();
    }

    @Test
    void listDocumentsValidatesPaginationBoundsAndResolvesDepartmentFilterPrecedence() {
        CallerContext explicitItCaller = new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1");
        CallerContext fallbackCaller = new CallerContext(IT_DEPT_ID, null, false, "127.0.0.1");

        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, -1, 20, explicitItCaller))
            .isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, 0, 0, explicitItCaller))
            .isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, 0, 101, explicitItCaller))
            .isInstanceOf(InvalidDocumentRequestException.class);
        verifyNoInteractions(documentRepository);

        DocumentEntity entity = document(DOC_ID, department(HR_DEPT_ID, "HR"), AccessLevel.DEPARTMENT, DocumentStatus.PENDING);
        Page<DocumentEntity> pageResult = new PageImpl<>(
            List.of(entity),
            PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")),
            1L
        );
        when(documentRepository.findAllFiltered(any(), any(), any(), any(Pageable.class))).thenReturn(pageResult);

        // 1. filterDepartmentId overrides explicit caller departmentId
        PageResponse<DocumentResponse> overridden = documentService.listDocuments(
            HR_DEPT_ID, DocumentStatus.PENDING, AccessLevel.DEPARTMENT, 0, 10, explicitItCaller
        );
        assertThat(overridden.items()).hasSize(1);
        assertThat(overridden.items().getFirst().id()).isEqualTo(DOC_ID);
        assertThat(overridden.page()).isZero();
        assertThat(overridden.size()).isEqualTo(10);
        assertThat(overridden.totalElements()).isEqualTo(1L);
        assertThat(overridden.totalPages()).isEqualTo(1);
        verify(documentRepository).findAllFiltered(
            eq(HR_DEPT_ID),
            eq(DocumentStatus.PENDING),
            eq(AccessLevel.DEPARTMENT),
            eq(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
        );

        // 2. null filterDepartmentId uses caller.departmentId() when explicitDepartment == true
        documentService.listDocuments(null, null, null, 1, 100, explicitItCaller);
        verify(documentRepository).findAllFiltered(
            eq(IT_DEPT_ID),
            eq(null),
            eq(null),
            eq(PageRequest.of(1, 100, Sort.by(Sort.Direction.DESC, "createdAt")))
        );

        // 3. null filterDepartmentId passes null when explicitDepartment == false
        documentService.listDocuments(null, DocumentStatus.COMPLETED, null, 0, 1, fallbackCaller);
        verify(documentRepository).findAllFiltered(
            eq(null),
            eq(DocumentStatus.COMPLETED),
            eq(null),
            eq(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt")))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentRepositoryFindAllFilteredBuildsPredicatesOnlyForNonNullFilters() {
        DocumentRepository repo = mock(DocumentRepository.class, CALLS_REAL_METHODS);
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        Pageable pageable = PageRequest.of(0, 20);
        repo.findAllFiltered(null, null, null, pageable);
        repo.findAllFiltered(IT_DEPT_ID, DocumentStatus.PENDING, null, pageable);

        ArgumentCaptor<Specification<DocumentEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(repo, org.mockito.Mockito.times(2)).findAll(specCaptor.capture(), eq(pageable));

        Root<DocumentEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(root.get(anyString())).thenReturn(mock(Path.class));
        when(cb.equal(any(), any())).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        Predicate emptyPredicate = specCaptor.getAllValues().get(0).toPredicate(root, query, cb);
        assertThat(emptyPredicate).isNull();
        verify(cb, never()).equal(any(), any());

        Predicate filteredPredicate = specCaptor.getAllValues().get(1).toPredicate(root, query, cb);
        assertThat(filteredPredicate).isNotNull();
        verify(root).get("departmentId");
        verify(root).get("status");
        verify(root, never()).get("accessLevel");
    }

    @Test
    void getDocumentStatusEnforcesNotFoundAndCrossDepartmentPublicVsRestrictedRules() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DocumentEntity deptDoc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.PROCESSING);
        deptDoc.setChunkCount(12);
        DocumentEntity confidentialDoc = document(DOC_ID, itDept, AccessLevel.CONFIDENTIAL, DocumentStatus.COMPLETED);
        DocumentEntity publicDoc = document(DOC_ID, itDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.getDocumentStatus(
            DOC_ID, new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1")
        )).isInstanceOf(DocumentNotFoundException.class);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(deptDoc));
        DocumentStatusResponse sameDept = documentService.getDocumentStatus(
            DOC_ID, new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1")
        );
        assertThat(sameDept.id()).isEqualTo(DOC_ID);
        assertThat(sameDept.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(sameDept.chunkCount()).isEqualTo(12);

        CallerContext crossDeptCaller = new CallerContext(HR_DEPT_ID, null, true, "127.0.0.1");
        assertThatThrownBy(() -> documentService.getDocumentStatus(DOC_ID, crossDeptCaller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(confidentialDoc));
        assertThatThrownBy(() -> documentService.getDocumentStatus(DOC_ID, crossDeptCaller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(publicDoc));
        DocumentStatusResponse crossDeptPublic = documentService.getDocumentStatus(DOC_ID, crossDeptCaller);
        assertThat(crossDeptPublic.status()).isEqualTo(DocumentStatus.COMPLETED);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(confidentialDoc));
        CallerContext fallbackCaller = new CallerContext(HR_DEPT_ID, null, false, "127.0.0.1");
        assertThat(documentService.getDocumentStatus(DOC_ID, fallbackCaller).status()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    void deleteDocumentEnforcesGuardsAndDeletesMinioObjectStrictlyAfterCommit() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DocumentEntity deptDoc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        String storageKey = deptDoc.getStorageKey();

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1")
        )).isInstanceOf(DocumentNotFoundException.class);
        verifyNoInteractions(minioStorageService);

        // Cross-department delete is forbidden even on PUBLIC documents
        DocumentEntity publicDoc = document(DOC_ID, itDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(publicDoc));
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(HR_DEPT_ID, null, true, "127.0.0.1")
        )).isInstanceOf(DocumentAccessDeniedException.class);
        verify(documentRepository, never()).delete(any(DocumentEntity.class));
        verifyNoInteractions(minioStorageService);

        // Happy path: MinIO delete must NOT run before commit, and MUST run after commit
        UserEntity adminUser = user(ADMIN_USER_ID, itDept);
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(adminUser));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(deptDoc));
        AtomicBoolean s3DeletedDuringDbDelete = new AtomicBoolean(false);
        doAnswer(inv -> {
            // At delete time inside active tx, deleteObjectQuietly must not have fired yet
            try {
                verify(minioStorageService, never()).deleteObjectQuietly(anyString());
            } catch (AssertionError err) {
                s3DeletedDuringDbDelete.set(true);
                throw err;
            }
            return null;
        }).when(documentRepository).delete(deptDoc);

        documentService.deleteDocument(DOC_ID, new CallerContext(IT_DEPT_ID, ADMIN_USER_ID, true, "10.0.0.5"));

        assertThat(s3DeletedDuringDbDelete.get()).isFalse();
        assertThat(transactionManager.commitCount).isEqualTo(1);
        verify(departmentRepository).incrementCacheVersion(IT_DEPT_ID);
        ArgumentCaptor<AuditLogEntity> deleteAuditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(deleteAuditCaptor.capture());
        assertThat(deleteAuditCaptor.getValue().getActionType()).isEqualTo(AuditActionType.DOC_DELETE);
        assertThat(deleteAuditCaptor.getValue().getUser()).isSameAs(adminUser);
        assertThat(deleteAuditCaptor.getValue().getDepartment()).isSameAs(itDept);
        assertThat(deleteAuditCaptor.getValue().getStatusCode()).isEqualTo(204);
        assertThat(deleteAuditCaptor.getValue().getIpAddress()).isEqualTo("10.0.0.5");
        verify(documentRepository).delete(deptDoc);
        verify(minioStorageService).deleteObjectQuietly(storageKey);

        // PUBLIC document delete increments all cache versions (use distinct ID & clear previous invocations)
        clearInvocations(minioStorageService, departmentRepository);
        UUID publicDocId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        DocumentEntity anotherPublicDoc = document(publicDocId, itDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);
        when(documentRepository.findById(publicDocId)).thenReturn(Optional.of(anotherPublicDoc));
        documentService.deleteDocument(publicDocId, new CallerContext(IT_DEPT_ID, null, true, "10.0.0.5"));
        verify(departmentRepository).incrementAllCacheVersions();
        verify(minioStorageService).deleteObjectQuietly(anotherPublicDoc.getStorageKey());
    }

    @Test
    void deleteDocumentNeverDeletesMinioObjectWhenTransactionRollsBack() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DocumentEntity deptDoc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(deptDoc));

        // Case 1: Runtime exception during transaction body rolls back
        doThrow(new RuntimeException("DB constraint failure")).when(documentRepository).delete(deptDoc);
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1")
        )).isInstanceOf(RuntimeException.class);
        verify(minioStorageService, never()).deleteObjectQuietly(anyString());
        assertThat(transactionManager.rollbackCount).isEqualTo(1);

        // Case 2: Transaction body finishes, synchronization registered, then commit itself fails
        doAnswer(inv -> null).when(documentRepository).delete(deptDoc);
        transactionManager.setRollbackOnCommitFailure(true);
        transactionManager.failOnCommit = true;
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(IT_DEPT_ID, null, true, "127.0.0.1")
        )).isInstanceOf(TransactionSystemException.class);
        verify(minioStorageService, never()).deleteObjectQuietly(anyString());
        assertThat(transactionManager.commitCount).isZero();
        assertThat(transactionManager.rollbackCount).isEqualTo(2);
    }

    private void drainUploadStream() {
        doAnswer(inv -> {
            InputStream stream = inv.getArgument(1);
            stream.readAllBytes();
            return null;
        }).when(minioStorageService).uploadStream(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    private static DepartmentEntity department(UUID id, String code) {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(id);
        dept.setCode(code);
        dept.setName(code + " Department");
        dept.setCacheVersion(1L);
        return dept;
    }

    private static UserEntity user(UUID id, DepartmentEntity dept) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDepartment(dept);
        user.setEmail("user@documind.ai");
        user.setFullName("Test User");
        return user;
    }

    private static DocumentEntity document(UUID id, DepartmentEntity dept, AccessLevel accessLevel, DocumentStatus status) {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(id);
        doc.setDepartment(dept);
        doc.setOriginalName("doc.pdf");
        doc.setStorageKey("departments/" + dept.getId() + "/upload-1/v1/doc.pdf");
        doc.setFileExtension("pdf");
        doc.setMimeType("application/pdf");
        doc.setFileSizeBytes(1024L);
        doc.setFileHash("a".repeat(64));
        doc.setAccessLevel(accessLevel);
        doc.setStatus(status);
        doc.setCurrentVersion(1);
        doc.setChunkCount(0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        return doc;
    }

    /**
     * Minimal {@link AbstractPlatformTransactionManager} that drives Spring's real
     * {@link TransactionSynchronizationManager} lifecycle (begin, commit, afterCommit, rollback)
     * without requiring a JDBC DataSource.
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private int commitCount;
        private int rollbackCount;
        private boolean failOnCommit;

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
            // No-op: AbstractPlatformTransactionManager initializes TransactionSynchronizationManager
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            if (failOnCommit) {
                throw new TransactionSystemException("Simulated commit failure");
            }
            commitCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            rollbackCount++;
        }
    }
}
