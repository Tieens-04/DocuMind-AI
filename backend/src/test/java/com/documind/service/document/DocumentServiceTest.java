package com.documind.service.document;

import com.documind.controller.dto.DocumentPreviewUrlResponse;
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
import com.documind.exception.DocumentStorageException;
import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.DuplicateDocumentException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.exception.RateLimitExceededException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.exception.UnsupportedDocumentTypeException;
import com.documind.infrastructure.ratelimit.RateLimitOperation;
import com.documind.infrastructure.ratelimit.RateLimitService;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.infrastructure.security.DocumentPreviewTokenService;
import com.documind.infrastructure.security.JwtProperties;
import com.documind.infrastructure.security.PreviewBaseUrlResolver;
import com.documind.infrastructure.storage.HashingBoundedInputStream;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.auth.TokenBlacklistService;
import com.documind.service.rag.RagCacheService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
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
    private DocumentPreviewTokenService previewTokenService;
    private TokenBlacklistService tokenBlacklistService;
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
        environment.setActiveProfiles("dev");
        environment.setProperty("documind.security.dev-caller-fallback.enabled", "true");
        SecurityContextHolder.clearContext();

        previewTokenService = new DocumentPreviewTokenService(
            new JwtProperties("document-service-test-hmac-secret-key-min-32-bytes", "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
            environment,
            PreviewBaseUrlResolver.fixed("http://localhost:8081"),
            Duration.ofMinutes(15),
            Clock.systemUTC()
        );
        tokenBlacklistService = mock(TokenBlacklistService.class);

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
            previewTokenService,
            tokenBlacklistService,
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
        CallerContext caller = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "203.0.113.10");

        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        UserEntity admin = user(ADMIN_USER_ID, dept, Role.SYSTEM_ADMIN, true);
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
    void uploadDocumentEnforcesRoleAndDepartmentPermissionsBeforeIo() {
        byte[] txtBytes = "Protected content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", txtBytes);

        // 1. STANDARD_EMPLOYEE cannot upload
        CallerContext employeeCaller = new CallerContext(HR_USER_ID, IT_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, employeeCaller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // 2. DEPARTMENT_MANAGER cannot upload into another department via targetDepartmentId
        CallerContext itManager = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, HR_DEPT_ID, itManager))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // 3. Caller userId belonging to a different department is rejected before S3 I/O
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        UserEntity hrManagerUser = user(HR_USER_ID, hrDept, Role.DEPARTMENT_MANAGER, true);
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(itDept));
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(hrManagerUser));

        CallerContext spoofedItCaller = new CallerContext(HR_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, spoofedItCaller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // 4. Inactive user account is rejected
        UUID inactiveUserId = UUID.randomUUID();
        UserEntity inactiveItUser = user(inactiveUserId, itDept, Role.DEPARTMENT_MANAGER, false);
        when(userRepository.findById(inactiveUserId)).thenReturn(Optional.of(inactiveItUser));
        CallerContext inactiveCaller = new CallerContext(inactiveUserId, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        assertThatThrownBy(() -> documentService.uploadDocument(file, AccessLevel.DEPARTMENT, inactiveCaller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        verifyNoInteractions(minioStorageService);

        // 5. SYSTEM_ADMIN can upload directly into HR department
        UserEntity sysAdminUser = user(ADMIN_USER_ID, itDept, Role.SYSTEM_ADMIN, true);
        when(departmentRepository.findById(HR_DEPT_ID)).thenReturn(Optional.of(hrDept));
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(sysAdminUser));
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(eq(HR_DEPT_ID), anyString())).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> {
            DocumentEntity entity = inv.getArgument(0);
            entity.setId(DOC_ID);
            return entity;
        });

        CallerContext sysAdminCaller = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");
        DocumentResponse adminUpload = documentService.uploadDocument(file, AccessLevel.DEPARTMENT, HR_DEPT_ID, sysAdminCaller);
        assertThat(adminUpload.departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(adminUpload.uploadedBy()).isEqualTo(ADMIN_USER_ID);
    }

    @Test
    void uploadDocumentDefaultsAccessLevelToDepartmentAndIncrementsAllVersionsForPublic() throws Exception {
        byte[] txtBytes = "Tai lieu noi bo".getBytes(StandardCharsets.UTF_8);
        CallerContext caller = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
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
    void uploadDocumentHandlesMissingDepartmentAndDevFallbackVsProdMissingUser() {
        byte[] txtBytes = "Hello".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", txtBytes);

        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.uploadDocument(
            file, AccessLevel.DEPARTMENT, new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(minioStorageService);

        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.uploadDocument(
            file, AccessLevel.DEPARTMENT, new CallerContext(HR_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(minioStorageService);

        // Non-prod default dev admin missing in custom DB nulls out uploadedBy instead of failing
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.empty());
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(eq(IT_DEPT_ID), anyString())).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> {
            DocumentEntity entity = inv.getArgument(0);
            entity.setId(DOC_ID);
            return entity;
        });

        DocumentResponse response = documentService.uploadDocument(
            file, AccessLevel.DEPARTMENT, new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        );
        assertThat(response.uploadedBy()).isNull();

        // In prod profile or unconfigured staging profile, missing user must throw ResourceNotFoundException even for default dev UUID
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
            file, AccessLevel.DEPARTMENT, new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);

        MockEnvironment stagingEnv = new MockEnvironment();
        stagingEnv.setActiveProfiles("staging");
        DocumentService stagingService = new DocumentService(
            documentRepository,
            departmentRepository,
            userRepository,
            auditLogRepository,
            fileValidationService,
            minioStorageService,
            transactionTemplate,
            eventPublisher,
            stagingEnv
        );
        assertThatThrownBy(() -> stagingService.uploadDocument(
            file, AccessLevel.DEPARTMENT, new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadDocumentRejectsInvalidFilesBeforeCallingMinioStorage() {
        DepartmentEntity dept = department(IT_DEPT_ID, "IT");
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(dept));
        CallerContext caller = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

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
        CallerContext caller = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

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
                assertThat(dup.getMessage()).doesNotContain(IT_DEPT_ID.toString());
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
        CallerContext caller = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

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
                assertThat(dup.getMessage()).doesNotContain(IT_DEPT_ID.toString());
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
        CallerContext caller = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
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
        CallerContext caller = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

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
    void listDocumentsValidatesPaginationBoundsAndDelegatesServerSideVisibilityScope() {
        CallerContext itManager = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext employee = new CallerContext(null, IT_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        CallerContext sysAdmin = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");

        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, -1, 20, itManager))
            .isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, 0, 0, itManager))
            .isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, 0, 101, itManager))
            .isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, 0, 20, null))
            .isInstanceOf(InvalidDocumentRequestException.class);
        verifyNoInteractions(documentRepository);

        DocumentEntity entity = document(DOC_ID, department(HR_DEPT_ID, "HR"), AccessLevel.PUBLIC, DocumentStatus.PENDING);
        Page<DocumentEntity> pageResult = new PageImpl<>(
            List.of(entity),
            PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")),
            1L
        );
        when(documentRepository.findAllVisible(any(), any(Role.class), any(), any(), any(), any(Pageable.class)))
            .thenReturn(pageResult);

        // 1. DEPARTMENT_MANAGER passing filterDepartmentId keeps Role.DEPARTMENT_MANAGER and callerDepartmentId=IT_DEPT_ID
        PageResponse<DocumentResponse> narrowed = documentService.listDocuments(
            HR_DEPT_ID, DocumentStatus.PENDING, AccessLevel.DEPARTMENT, 0, 10, itManager
        );
        assertThat(narrowed.items()).hasSize(1);
        assertThat(narrowed.items().getFirst().id()).isEqualTo(DOC_ID);
        verify(documentRepository).findAllVisible(
            eq(IT_DEPT_ID),
            eq(Role.DEPARTMENT_MANAGER),
            eq(HR_DEPT_ID),
            eq(DocumentStatus.PENDING),
            eq(AccessLevel.DEPARTMENT),
            eq(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
        );

        // 2. STANDARD_EMPLOYEE passes Role.STANDARD_EMPLOYEE with callerDepartmentId=IT_DEPT_ID
        documentService.listDocuments(null, DocumentStatus.COMPLETED, null, 1, 100, employee);
        verify(documentRepository).findAllVisible(
            eq(IT_DEPT_ID),
            eq(Role.STANDARD_EMPLOYEE),
            eq(null),
            eq(DocumentStatus.COMPLETED),
            eq(null),
            eq(PageRequest.of(1, 100, Sort.by(Sort.Direction.DESC, "createdAt")))
        );

        // 3. SYSTEM_ADMIN passes Role.SYSTEM_ADMIN
        documentService.listDocuments(null, null, null, 0, 20, sysAdmin);
        verify(documentRepository).findAllVisible(
            eq(IT_DEPT_ID),
            eq(Role.SYSTEM_ADMIN),
            eq(null),
            eq(null),
            eq(null),
            eq(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentRepositoryFindAllVisibleEnforcesRoleScopedPredicates() {
        DocumentRepository repo = mock(DocumentRepository.class, CALLS_REAL_METHODS);
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        Pageable pageable = PageRequest.of(0, 20);
        assertThatThrownBy(() -> repo.findAllVisible(null, Role.DEPARTMENT_MANAGER, null, null, null, pageable))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repo.findAllVisible(IT_DEPT_ID, null, null, null, null, pageable))
            .isInstanceOf(NullPointerException.class);

        repo.findAllVisible(IT_DEPT_ID, Role.STANDARD_EMPLOYEE, null, null, null, pageable);
        repo.findAllVisible(IT_DEPT_ID, Role.DEPARTMENT_MANAGER, null, null, null, pageable);
        repo.findAllVisible(IT_DEPT_ID, Role.SYSTEM_ADMIN, HR_DEPT_ID, DocumentStatus.PENDING, AccessLevel.CONFIDENTIAL, pageable);

        ArgumentCaptor<Specification<DocumentEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(repo, org.mockito.Mockito.times(3)).findAll(specCaptor.capture(), eq(pageable));

        CriteriaQuery<?> query = mock(CriteriaQuery.class);

        // 1. STANDARD_EMPLOYEE: PUBLIC OR (departmentId == caller && accessLevel == DEPARTMENT)
        Root<DocumentEntity> employeeRoot = mock(Root.class);
        CriteriaBuilder employeeCb = mock(CriteriaBuilder.class);
        when(employeeRoot.get(anyString())).thenReturn(mock(Path.class));
        when(employeeCb.equal(any(Expression.class), (Object) any())).thenReturn(mock(Predicate.class));
        when(employeeCb.and(any(Predicate.class), any(Predicate.class))).thenReturn(mock(Predicate.class));
        when(employeeCb.or(any(Predicate.class), any(Predicate.class))).thenReturn(mock(Predicate.class));
        when(employeeCb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        Predicate employeePredicate = specCaptor.getAllValues().get(0).toPredicate(employeeRoot, query, employeeCb);
        assertThat(employeePredicate).isNotNull();
        verify(employeeCb).equal(any(Expression.class), eq(AccessLevel.PUBLIC));
        verify(employeeCb).equal(any(Expression.class), eq(IT_DEPT_ID));
        verify(employeeCb).equal(any(Expression.class), eq(AccessLevel.DEPARTMENT));
        verify(employeeCb).and(any(Predicate.class), any(Predicate.class));
        verify(employeeCb).or(any(Predicate.class), any(Predicate.class));

        // 2. DEPARTMENT_MANAGER: PUBLIC OR departmentId == caller
        Root<DocumentEntity> managerRoot = mock(Root.class);
        CriteriaBuilder managerCb = mock(CriteriaBuilder.class);
        when(managerRoot.get(anyString())).thenReturn(mock(Path.class));
        when(managerCb.equal(any(Expression.class), (Object) any())).thenReturn(mock(Predicate.class));
        when(managerCb.or(any(Predicate.class), any(Predicate.class))).thenReturn(mock(Predicate.class));
        when(managerCb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        Predicate managerPredicate = specCaptor.getAllValues().get(1).toPredicate(managerRoot, query, managerCb);
        assertThat(managerPredicate).isNotNull();
        verify(managerRoot).get("accessLevel");
        verify(managerRoot).get("departmentId");
        verify(managerCb).equal(any(Expression.class), eq(AccessLevel.PUBLIC));
        verify(managerCb).equal(any(Expression.class), eq(IT_DEPT_ID));
        verify(managerCb).or(any(Predicate.class), any(Predicate.class));

        // 3. SYSTEM_ADMIN: no visibility OR predicate; optional filters applied
        Root<DocumentEntity> adminRoot = mock(Root.class);
        CriteriaBuilder adminCb = mock(CriteriaBuilder.class);
        when(adminRoot.get(anyString())).thenReturn(mock(Path.class));
        when(adminCb.equal(any(Expression.class), (Object) any())).thenReturn(mock(Predicate.class));
        when(adminCb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        Predicate adminPredicate = specCaptor.getAllValues().get(2).toPredicate(adminRoot, query, adminCb);
        assertThat(adminPredicate).isNotNull();
        verify(adminCb, never()).or(any(Predicate.class), any(Predicate.class));
        verify(adminCb).equal(any(Expression.class), eq(HR_DEPT_ID));
        verify(adminCb).equal(any(Expression.class), eq(DocumentStatus.PENDING));
        verify(adminCb).equal(any(Expression.class), eq(AccessLevel.CONFIDENTIAL));
    }

    @Test
    void getDocumentStatusEnforcesNotFoundRoleAndSanitizedCrossDepartmentRules() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DocumentEntity deptDoc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.PROCESSING);
        deptDoc.setChunkCount(12);
        DocumentEntity confidentialDoc = document(DOC_ID, itDept, AccessLevel.CONFIDENTIAL, DocumentStatus.COMPLETED);
        DocumentEntity publicDoc = document(DOC_ID, itDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.getDocumentStatus(
            DOC_ID, new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(DocumentNotFoundException.class);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(deptDoc));
        DocumentStatusResponse sameDeptEmployee = documentService.getDocumentStatus(
            DOC_ID, new CallerContext(null, IT_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1")
        );
        assertThat(sameDeptEmployee.id()).isEqualTo(DOC_ID);
        assertThat(sameDeptEmployee.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(sameDeptEmployee.chunkCount()).isEqualTo(12);

        CallerContext crossDeptCaller = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        assertThatThrownBy(() -> documentService.getDocumentStatus(DOC_ID, crossDeptCaller))
            .isInstanceOf(DocumentAccessDeniedException.class)
            .satisfies(ex -> assertThat(ex.getMessage())
                .doesNotContain(IT_DEPT_ID.toString())
                .doesNotContain(HR_DEPT_ID.toString())
                .doesNotContain(DOC_ID.toString()));

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(confidentialDoc));
        // Same-department STANDARD_EMPLOYEE must NEVER see CONFIDENTIAL status
        CallerContext sameDeptEmployeeCaller = new CallerContext(null, IT_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        assertThatThrownBy(() -> documentService.getDocumentStatus(DOC_ID, sameDeptEmployeeCaller))
            .isInstanceOf(DocumentAccessDeniedException.class)
            .satisfies(ex -> assertThat(ex.getMessage())
                .doesNotContain(IT_DEPT_ID.toString())
                .doesNotContain(DOC_ID.toString()));

        // Same-department DEPARTMENT_MANAGER CAN inspect own-department CONFIDENTIAL status
        assertThat(documentService.getDocumentStatus(
            DOC_ID, new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        ).status()).isEqualTo(DocumentStatus.COMPLETED);

        assertThatThrownBy(() -> documentService.getDocumentStatus(DOC_ID, crossDeptCaller))
            .isInstanceOf(DocumentAccessDeniedException.class)
            .satisfies(ex -> assertThat(ex.getMessage())
                .doesNotContain(IT_DEPT_ID.toString())
                .doesNotContain(HR_DEPT_ID.toString())
                .doesNotContain(DOC_ID.toString()));

        // SYSTEM_ADMIN can inspect cross-department CONFIDENTIAL document status
        CallerContext sysAdminCaller = new CallerContext(ADMIN_USER_ID, HR_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");
        assertThat(documentService.getDocumentStatus(DOC_ID, sysAdminCaller).status())
            .isEqualTo(DocumentStatus.COMPLETED);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(publicDoc));
        DocumentStatusResponse crossDeptPublic = documentService.getDocumentStatus(DOC_ID, crossDeptCaller);
        assertThat(crossDeptPublic.status()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    void deleteDocumentEnforcesRoleAndOwnershipGuardsAndDeletesMinioObjectStrictlyAfterCommit() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DocumentEntity deptDoc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        String storageKey = deptDoc.getStorageKey();

        // STANDARD_EMPLOYEE cannot delete even within own department
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(null, IT_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1")
        )).isInstanceOf(DocumentAccessDeniedException.class);
        verifyNoInteractions(documentRepository, minioStorageService);

        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(DocumentNotFoundException.class);
        verifyNoInteractions(minioStorageService);

        // Cross-department manager delete is forbidden even on PUBLIC documents (sanitized message)
        DocumentEntity publicDoc = document(DOC_ID, itDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(publicDoc));
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        ))
            .isInstanceOf(DocumentAccessDeniedException.class)
            .satisfies(ex -> assertThat(ex.getMessage())
                .doesNotContain(IT_DEPT_ID.toString())
                .doesNotContain(HR_DEPT_ID.toString())
                .doesNotContain(DOC_ID.toString()));
        verify(documentRepository, never()).delete(any(DocumentEntity.class));
        verifyNoInteractions(minioStorageService);

        // Happy path: MinIO delete must NOT run before commit, and MUST run after commit
        UserEntity adminUser = user(ADMIN_USER_ID, itDept, Role.SYSTEM_ADMIN, true);
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(adminUser));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(deptDoc));
        AtomicBoolean s3DeletedDuringDbDelete = new AtomicBoolean(false);
        doAnswer(inv -> {
            try {
                verify(minioStorageService, never()).deleteObjectQuietly(anyString());
            } catch (AssertionError err) {
                s3DeletedDuringDbDelete.set(true);
                throw err;
            }
            return null;
        }).when(documentRepository).delete(deptDoc);

        documentService.deleteDocument(DOC_ID, new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "10.0.0.5"));

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
        verify(documentRepository, times(3)).findByIdForUpdate(DOC_ID);
        verify(documentRepository, never()).findById(any());
        verify(documentRepository).delete(deptDoc);
        verify(minioStorageService).deleteObjectQuietly(storageKey);

        // SYSTEM_ADMIN can delete cross-department PUBLIC documents and increments all cache versions
        clearInvocations(minioStorageService, departmentRepository);
        UUID publicDocId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        DocumentEntity anotherPublicDoc = document(publicDocId, itDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);
        when(documentRepository.findByIdForUpdate(publicDocId)).thenReturn(Optional.of(anotherPublicDoc));
        documentService.deleteDocument(publicDocId, new CallerContext(ADMIN_USER_ID, HR_DEPT_ID, Role.SYSTEM_ADMIN, "10.0.0.5"));
        verify(departmentRepository).incrementAllCacheVersions();
        verify(minioStorageService).deleteObjectQuietly(anotherPublicDoc.getStorageKey());
    }

    @Test
    void deleteDocumentHandlesDevFallbackVsProdMissingUserAndActorOwnership() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity deptDoc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        UserEntity hrManager = user(HR_USER_ID, hrDept, Role.DEPARTMENT_MANAGER, true);
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(deptDoc));
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(hrManager));

        // 1. Spoofing HR userId inside IT caller context fails before deleting
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(HR_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(DocumentAccessDeniedException.class);
        verify(documentRepository, never()).delete(any(DocumentEntity.class));
        verifyNoInteractions(minioStorageService);

        // 2. Nonexistent explicit userId throws ResourceNotFoundException before any DB/S3 mutation
        UUID missingUserId = UUID.randomUUID();
        when(userRepository.findById(missingUserId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(missingUserId, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);
        verify(documentRepository, never()).delete(any(DocumentEntity.class));
        verify(auditLogRepository, never()).save(any(AuditLogEntity.class));
        verifyNoInteractions(minioStorageService);

        // 3. Prod profile throws ResourceNotFoundException even when default dev UUID is absent
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
        assertThatThrownBy(() -> prodService.deleteDocument(
            DOC_ID, new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(ResourceNotFoundException.class);
        verify(documentRepository, never()).delete(any(DocumentEntity.class));
        verify(auditLogRepository, never()).save(any(AuditLogEntity.class));
        verifyNoInteractions(minioStorageService);

        // 4. Non-prod dev fallback admin ID absent in a custom DB resolves cleanly to null
        documentService.deleteDocument(
            DOC_ID, new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        );
        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getUser()).isNull();
        assertThat(auditCaptor.getValue().getActionType()).isEqualTo(AuditActionType.DOC_DELETE);
        verify(documentRepository).delete(deptDoc);
        verify(minioStorageService).deleteObjectQuietly(deptDoc.getStorageKey());
        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isEqualTo(3);
    }

    @Test
    void deleteDocumentNeverDeletesMinioObjectWhenTransactionRollsBack() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DocumentEntity deptDoc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(deptDoc));

        // Case 1: Runtime exception during transaction body rolls back
        doThrow(new RuntimeException("DB constraint failure")).when(documentRepository).delete(deptDoc);
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(RuntimeException.class);
        verify(minioStorageService, never()).deleteObjectQuietly(anyString());
        assertThat(transactionManager.rollbackCount).isEqualTo(1);

        // Case 2: Transaction body finishes, synchronization registered, then commit itself fails
        doAnswer(inv -> null).when(documentRepository).delete(deptDoc);
        transactionManager.setRollbackOnCommitFailure(true);
        transactionManager.failOnCommit = true;
        assertThatThrownBy(() -> documentService.deleteDocument(
            DOC_ID, new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        )).isInstanceOf(TransactionSystemException.class);
        verify(minioStorageService, never()).deleteObjectQuietly(anyString());
        assertThat(transactionManager.commitCount).isZero();
        assertThat(transactionManager.rollbackCount).isEqualTo(2);
    }

    @Test
    void rejectsRoleEscalationAndSanitizesUploadStreamIoExceptionMessages() throws Exception {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        UUID itEmployeeId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UserEntity itEmployee = user(itEmployeeId, itDept, Role.STANDARD_EMPLOYEE, true);
        UserEntity hrManager = user(HR_USER_ID, hrDept, Role.DEPARTMENT_MANAGER, true);
        DocumentEntity doc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);

        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(itDept));
        when(departmentRepository.findById(HR_DEPT_ID)).thenReturn(Optional.of(hrDept));
        when(userRepository.findById(itEmployeeId)).thenReturn(Optional.of(itEmployee));
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(hrManager));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));

        MockMultipartFile txtFile = new MockMultipartFile("file", "notes.txt", "text/plain", "Safe content".getBytes(StandardCharsets.UTF_8));

        // 1. STANDARD_EMPLOYEE claiming DEPARTMENT_MANAGER or SYSTEM_ADMIN is rejected across upload, list, status, delete
        CallerContext escalatedToManager = new CallerContext(itEmployeeId, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext escalatedToAdmin = new CallerContext(itEmployeeId, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");
        assertThatThrownBy(() -> documentService.uploadDocument(txtFile, AccessLevel.DEPARTMENT, escalatedToManager))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, 0, 20, escalatedToAdmin))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> documentService.getDocumentStatus(DOC_ID, escalatedToAdmin))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> documentService.deleteDocument(DOC_ID, escalatedToManager))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // 2. DEPARTMENT_MANAGER claiming SYSTEM_ADMIN is rejected
        CallerContext managerEscalatedToAdmin = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");
        assertThatThrownBy(() -> documentService.uploadDocument(txtFile, AccessLevel.DEPARTMENT, HR_DEPT_ID, managerEscalatedToAdmin))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> documentService.listDocuments(null, null, null, 0, 20, managerEscalatedToAdmin))
            .isInstanceOf(DocumentAccessDeniedException.class);

        verifyNoInteractions(minioStorageService);

        // 3. Failing MultipartFile.getInputStream() with sensitive local path must NOT leak path in 400 message
        MockMultipartFile brokenFile = new MockMultipartFile("file", "broken.txt", "text/plain", "ok".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("/tmp/tomcat-secret/upload_123.tmp (Permission denied)");
            }
        };
        assertThatThrownBy(() -> documentService.uploadDocument(
            brokenFile, AccessLevel.DEPARTMENT, new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        ))
            .isInstanceOf(InvalidDocumentRequestException.class)
            .satisfies(ex -> assertThat(ex.getMessage())
                .doesNotContain("/tmp/tomcat-secret")
                .doesNotContain("upload_123.tmp"));
    }

    @Test
    void getDocumentEnforcesRoleAndDepartmentVisibilityPolicy() {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity hrDeptDoc = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        DocumentEntity hrConfidentialDoc = document(UUID.randomUUID(), hrDept, AccessLevel.CONFIDENTIAL, DocumentStatus.COMPLETED);
        DocumentEntity hrPublicDoc = document(UUID.randomUUID(), hrDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);

        when(documentRepository.findById(hrDeptDoc.getId())).thenReturn(Optional.of(hrDeptDoc));
        when(documentRepository.findById(hrConfidentialDoc.getId())).thenReturn(Optional.of(hrConfidentialDoc));
        when(documentRepository.findById(hrPublicDoc.getId())).thenReturn(Optional.of(hrPublicDoc));

        CallerContext itManager = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext hrEmployee = new CallerContext(null, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        CallerContext sysAdmin = new CallerContext(null, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");

        // IT Manager blocked on HR DEPARTMENT & HR CONFIDENTIAL, allowed on HR PUBLIC
        assertThatThrownBy(() -> documentService.getDocument(hrDeptDoc.getId(), itManager))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> documentService.getDocument(hrConfidentialDoc.getId(), itManager))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThat(documentService.getDocument(hrPublicDoc.getId(), itManager).id()).isEqualTo(hrPublicDoc.getId());

        // HR Manager sees all HR docs including CONFIDENTIAL
        assertThat(documentService.getDocument(hrConfidentialDoc.getId(), hrManager).accessLevel())
            .isEqualTo(AccessLevel.CONFIDENTIAL);

        // HR Employee sees HR DEPARTMENT & PUBLIC, blocked on HR CONFIDENTIAL
        assertThat(documentService.getDocument(hrDeptDoc.getId(), hrEmployee).id()).isEqualTo(hrDeptDoc.getId());
        assertThatThrownBy(() -> documentService.getDocument(hrConfidentialDoc.getId(), hrEmployee))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // SYSTEM_ADMIN sees everything
        assertThat(documentService.getDocument(hrConfidentialDoc.getId(), sysAdmin).id())
            .isEqualTo(hrConfidentialDoc.getId());
    }

    @Test
    void updateDocumentMetadataEnforcesOwnershipExtensionParityAndAdminDepartmentTransfer() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity hrPublicDoc = document(DOC_ID, hrDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);

        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(hrPublicDoc));
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CallerContext itManager = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext hrEmployee = new CallerContext(null, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        CallerContext sysAdmin = new CallerContext(null, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");

        // 1. Empty payload rejected (400)
        assertThatThrownBy(() -> documentService.updateDocumentMetadata(
            DOC_ID, new UpdateDocumentMetadataRequest(null, null, null), hrManager
        )).isInstanceOf(InvalidDocumentRequestException.class);

        // 2. STANDARD_EMPLOYEE cannot update (403)
        assertThatThrownBy(() -> documentService.updateDocumentMetadata(
            DOC_ID, new UpdateDocumentMetadataRequest(null, AccessLevel.DEPARTMENT, null), hrEmployee
        )).isInstanceOf(DocumentAccessDeniedException.class);

        // 3. IT Manager cannot update HR document even when it is PUBLIC (403)
        assertThatThrownBy(() -> documentService.updateDocumentMetadata(
            DOC_ID, new UpdateDocumentMetadataRequest(null, AccessLevel.DEPARTMENT, null), itManager
        )).isInstanceOf(DocumentAccessDeniedException.class);

        // 4. HR Manager cannot transfer document to another department (403)
        assertThatThrownBy(() -> documentService.updateDocumentMetadata(
            DOC_ID, new UpdateDocumentMetadataRequest(null, null, IT_DEPT_ID), hrManager
        )).isInstanceOf(DocumentAccessDeniedException.class);

        // 5. Rejects mismatched file extension and path traversal characters (400)
        assertThatThrownBy(() -> documentService.updateDocumentMetadata(
            DOC_ID, new UpdateDocumentMetadataRequest("renamed.docx", null, null), hrManager
        )).isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.updateDocumentMetadata(
            DOC_ID, new UpdateDocumentMetadataRequest("../evil.pdf", null, null), hrManager
        )).isInstanceOf(InvalidDocumentRequestException.class);

        // 6. HR Manager updates own doc accessLevel to CONFIDENTIAL & valid PDF name (V6 trigger owns cache bump)
        DocumentResponse updatedByManager = documentService.updateDocumentMetadata(
            DOC_ID,
            new UpdateDocumentMetadataRequest("Quy chế nhân sự mới.pdf", AccessLevel.CONFIDENTIAL, null),
            hrManager
        );
        assertThat(updatedByManager.accessLevel()).isEqualTo(AccessLevel.CONFIDENTIAL);
        assertThat(updatedByManager.originalName()).isEqualTo("Quy chế nhân sự mới.pdf");
        verify(departmentRepository, never()).incrementCacheVersion(any());
        verify(departmentRepository, never()).incrementAllCacheVersions();

        // 7. SYSTEM_ADMIN transferring department checks duplicate hash conflict (409)
        DocumentEntity duplicateInIt = document(UUID.randomUUID(), itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(itDept));
        when(documentRepository.findByDepartmentIdAndFileHash(IT_DEPT_ID, hrPublicDoc.getFileHash()))
            .thenReturn(Optional.of(duplicateInIt));

        assertThatThrownBy(() -> documentService.updateDocumentMetadata(
            DOC_ID, new UpdateDocumentMetadataRequest(null, null, IT_DEPT_ID), sysAdmin
        )).isInstanceOf(DuplicateDocumentException.class);

        // 8. SYSTEM_ADMIN transfers department cleanly when target hash is absent
        when(documentRepository.findByDepartmentIdAndFileHash(IT_DEPT_ID, hrPublicDoc.getFileHash()))
            .thenReturn(Optional.empty());
        DocumentResponse transferred = documentService.updateDocumentMetadata(
            DOC_ID, new UpdateDocumentMetadataRequest(null, AccessLevel.DEPARTMENT, IT_DEPT_ID), sysAdmin
        );
        assertThat(transferred.departmentId()).isEqualTo(IT_DEPT_ID);
        assertThat(transferred.accessLevel()).isEqualTo(AccessLevel.DEPARTMENT);
    }

    @Test
    void updateDocumentMetadataDuringProcessingBumpsVersionResetsPendingAndRepublishesEvent() {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity processingDoc = document(DOC_ID, hrDept, AccessLevel.PUBLIC, DocumentStatus.PROCESSING);
        processingDoc.setCurrentVersion(1);
        processingDoc.setChunkCount(12);

        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(processingDoc));
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

        DocumentResponse updated = documentService.updateDocumentMetadata(
            DOC_ID,
            new UpdateDocumentMetadataRequest("Locked Down.pdf", AccessLevel.CONFIDENTIAL, null),
            hrManager
        );

        assertThat(updated.accessLevel()).isEqualTo(AccessLevel.CONFIDENTIAL);
        assertThat(updated.originalName()).isEqualTo("Locked Down.pdf");
        assertThat(updated.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(updated.currentVersion()).isEqualTo(2);
        assertThat(updated.chunkCount()).isEqualTo(0);

        ArgumentCaptor<DocumentUploadedEvent> eventCaptor = ArgumentCaptor.forClass(DocumentUploadedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().documentId()).isEqualTo(DOC_ID);
        assertThat(eventCaptor.getValue().version()).isEqualTo(2);
        assertThat(eventCaptor.getValue().accessLevel()).isEqualTo(AccessLevel.CONFIDENTIAL);
        assertThat(eventCaptor.getValue().originalName()).isEqualTo("Locked Down.pdf");
    }

    @Test
    void replaceDocumentUploadsNewVersionBumpsVersionRecordsAuditAndDeletesOldObjectOnlyAfterCommit() throws Exception {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        UserEntity hrManagerEntity = user(HR_USER_ID, hrDept, Role.DEPARTMENT_MANAGER, true);
        DocumentEntity existingDoc = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        String oldStorageKey = "departments/" + HR_DEPT_ID + "/old-upload/v1/draft-v1.docx";
        existingDoc.setOriginalName("draft-v1.docx");
        existingDoc.setFileExtension("docx");
        existingDoc.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        existingDoc.setStorageKey(oldStorageKey);
        existingDoc.setCurrentVersion(1);
        existingDoc.setChunkCount(14);
        existingDoc.setErrorMessage("old warning");

        byte[] newPdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(newPdfBytes));
        MockMultipartFile newFile = new MockMultipartFile("file", "policy-v2.pdf", "application/pdf", newPdfBytes);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(existingDoc));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(existingDoc));
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(hrManagerEntity));
        when(minioStorageService.buildStorageKey(eq(HR_DEPT_ID), any(UUID.class), eq(2), eq("policy-v2.pdf")))
            .thenReturn("departments/" + HR_DEPT_ID + "/new-upload/v2/policy-v2.pdf");
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(HR_DEPT_ID, expectedHash)).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CallerContext hrManager = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "10.0.0.5");

        DocumentResponse replaced = documentService.replaceDocument(DOC_ID, newFile, AccessLevel.PUBLIC, hrManager);

        assertThat(replaced.id()).isEqualTo(DOC_ID);
        assertThat(replaced.originalName()).isEqualTo("policy-v2.pdf");
        assertThat(replaced.fileExtension()).isEqualTo("pdf");
        assertThat(replaced.mimeType()).isEqualTo("application/pdf");
        assertThat(replaced.fileHash()).isEqualTo(expectedHash);
        assertThat(replaced.fileSizeBytes()).isEqualTo(newPdfBytes.length);
        assertThat(replaced.accessLevel()).isEqualTo(AccessLevel.PUBLIC);
        assertThat(replaced.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(replaced.currentVersion()).isEqualTo(2);
        assertThat(replaced.chunkCount()).isEqualTo(0);
        assertThat(replaced.errorMessage()).isNull();

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getActionType()).isEqualTo(AuditActionType.DOC_REINDEX);
        assertThat(auditCaptor.getValue().getStatusCode()).isEqualTo(202);
        assertThat(auditCaptor.getValue().getQueryText()).isEqualTo("policy-v2.pdf");

        ArgumentCaptor<DocumentUploadedEvent> eventCaptor = ArgumentCaptor.forClass(DocumentUploadedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().documentId()).isEqualTo(DOC_ID);
        assertThat(eventCaptor.getValue().version()).isEqualTo(2);
        assertThat(eventCaptor.getValue().fileExtension()).isEqualTo("pdf");
        assertThat(eventCaptor.getValue().accessLevel()).isEqualTo(AccessLevel.PUBLIC);
        assertThat(eventCaptor.getValue().storageKey()).isEqualTo("departments/" + HR_DEPT_ID + "/new-upload/v2/policy-v2.pdf");

        // originalName and accessLevel changed -> PostgreSQL V6 trigger increments cache_version (no double bump in Java)
        verify(departmentRepository, never()).incrementCacheVersion(any());
        verify(departmentRepository, never()).incrementAllCacheVersions();

        InOrder postCommitOrder = inOrder(documentRepository, minioStorageService);
        postCommitOrder.verify(documentRepository).deleteChunksBeforeVersion(DOC_ID, 2);
        postCommitOrder.verify(documentRepository).saveAndFlush(existingDoc);
        postCommitOrder.verify(minioStorageService).deleteObjectQuietly(oldStorageKey);
    }

    @Test
    void replaceDocumentIncrementsCacheVersionExplicitlyWhenOriginalNameAndAccessLevelAreUnchanged() {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity deptDoc = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        deptDoc.setOriginalName("doc.pdf");
        deptDoc.setCurrentVersion(1);

        byte[] newPdfBytes = "%PDF-1.4\n%Updated content\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile sameNameFile = new MockMultipartFile("file", "doc.pdf", "application/pdf", newPdfBytes);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(deptDoc));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(deptDoc));
        when(minioStorageService.buildStorageKey(eq(HR_DEPT_ID), any(UUID.class), eq(2), eq("doc.pdf")))
            .thenReturn("departments/" + HR_DEPT_ID + "/new-upload/v2/doc.pdf");
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(eq(HR_DEPT_ID), anyString())).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        DocumentResponse response = documentService.replaceDocument(DOC_ID, sameNameFile, null, hrManager);

        assertThat(response.currentVersion()).isEqualTo(2);
        assertThat(response.accessLevel()).isEqualTo(AccessLevel.DEPARTMENT);
        verify(departmentRepository).incrementCacheVersion(HR_DEPT_ID);
        verify(departmentRepository, never()).incrementAllCacheVersions();

        // PUBLIC doc with unchanged name & accessLevel -> increments ALL departments' cache versions
        clearInvocations(departmentRepository);
        DocumentEntity publicDoc = document(DOC_ID, hrDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);
        publicDoc.setOriginalName("doc.pdf");
        publicDoc.setCurrentVersion(2);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(publicDoc));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(publicDoc));
        when(minioStorageService.buildStorageKey(eq(HR_DEPT_ID), any(UUID.class), eq(3), eq("doc.pdf")))
            .thenReturn("departments/" + HR_DEPT_ID + "/new-upload/v3/doc.pdf");

        documentService.replaceDocument(DOC_ID, sameNameFile, AccessLevel.PUBLIC, hrManager);
        verify(departmentRepository).incrementAllCacheVersions();
    }

    @Test
    void replaceDocumentAllowsSameFileHashOnSameDocumentForFailedReindexRetry() throws Exception {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        String sameHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdfBytes));

        DocumentEntity failedDoc = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.FAILED);
        failedDoc.setFileHash(sameHash);
        failedDoc.setErrorMessage("[EMBEDDING_ERROR] Ollama offline");
        failedDoc.setCurrentVersion(2);

        MockMultipartFile retryFile = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(failedDoc));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(failedDoc));
        when(minioStorageService.buildStorageKey(eq(HR_DEPT_ID), any(UUID.class), eq(3), eq("doc.pdf")))
            .thenReturn("departments/" + HR_DEPT_ID + "/retry/v3/doc.pdf");
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(HR_DEPT_ID, sameHash)).thenReturn(Optional.of(failedDoc));
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        DocumentResponse retried = documentService.replaceDocument(DOC_ID, retryFile, null, hrManager);

        assertThat(retried.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(retried.currentVersion()).isEqualTo(3);
        assertThat(retried.errorMessage()).isNull();
        verify(eventPublisher).publishEvent(any(DocumentUploadedEvent.class));
    }

    @Test
    void replaceDocumentRejectsDuplicateFileHashOfAnotherDocumentAndDeletesStagedObject() throws Exception {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity targetDoc = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        String oldKey = targetDoc.getStorageKey();

        UUID otherDocId = UUID.randomUUID();
        DocumentEntity otherDoc = document(otherDocId, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        byte[] dupBytes = "%PDF-1.4\n%Duplicate payload\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        String dupHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dupBytes));
        otherDoc.setFileHash(dupHash);

        String stagedKey = "departments/" + HR_DEPT_ID + "/staged/v2/other.pdf";
        MockMultipartFile dupFile = new MockMultipartFile("file", "other.pdf", "application/pdf", dupBytes);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(targetDoc));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(targetDoc));
        when(minioStorageService.buildStorageKey(eq(HR_DEPT_ID), any(UUID.class), eq(2), eq("other.pdf")))
            .thenReturn(stagedKey);
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(HR_DEPT_ID, dupHash)).thenReturn(Optional.of(otherDoc));

        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, dupFile, null, hrManager))
            .isInstanceOf(DuplicateDocumentException.class)
            .satisfies(ex -> {
                DuplicateDocumentException dupEx = (DuplicateDocumentException) ex;
                assertThat(dupEx.existingDocumentId()).isEqualTo(otherDocId);
                assertThat(dupEx.fileHash()).isEqualTo(dupHash);
            });

        verify(minioStorageService).deleteObjectQuietly(stagedKey);
        verify(minioStorageService, never()).deleteObjectQuietly(oldKey);
        verify(eventPublisher, never()).publishEvent(any());

        // Concurrent DataIntegrityViolationException race also resolves to DuplicateDocumentException and cleans staged key
        clearInvocations(minioStorageService);
        when(documentRepository.findByDepartmentIdAndFileHash(HR_DEPT_ID, dupHash))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(otherDoc));
        when(documentRepository.saveAndFlush(any(DocumentEntity.class)))
            .thenThrow(new DataIntegrityViolationException("uq_documents_department_hash"));

        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, dupFile, null, hrManager))
            .isInstanceOf(DuplicateDocumentException.class)
            .satisfies(ex -> assertThat(((DuplicateDocumentException) ex).existingDocumentId()).isEqualTo(otherDocId));
        verify(minioStorageService).deleteObjectQuietly(stagedKey);
        verify(minioStorageService, never()).deleteObjectQuietly(oldKey);
    }

    @Test
    void replaceDocumentEnforcesRbacAndNotFoundBeforeStreamingAndKeepsOldObjectOnCommitFailure() {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        DocumentEntity hrPublicDoc = document(DOC_ID, hrDept, AccessLevel.PUBLIC, DocumentStatus.COMPLETED);
        String oldKey = hrPublicDoc.getStorageKey();
        byte[] pdfBytes = "%PDF-1.4\n%Valid\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "v2.pdf", "application/pdf", pdfBytes);

        CallerContext employee = new CallerContext(null, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        CallerContext itManager = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

        assertThatThrownBy(() -> documentService.replaceDocument(null, file, null, hrManager))
            .isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, null, null, hrManager))
            .isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, file, null, employee))
            .isInstanceOf(DocumentAccessDeniedException.class);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, file, null, hrManager))
            .isInstanceOf(DocumentNotFoundException.class);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(hrPublicDoc));
        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, file, null, itManager))
            .isInstanceOf(DocumentAccessDeniedException.class);
        verifyNoInteractions(minioStorageService);

        // Concurrent department transfer between Phase 1 and Phase 2 lock -> denies and deletes staged key
        DocumentEntity transferredToIt = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        String stagedKey = "departments/" + HR_DEPT_ID + "/race/v2/v2.pdf";
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(transferredToIt));
        when(minioStorageService.buildStorageKey(eq(HR_DEPT_ID), any(UUID.class), eq(2), eq("v2.pdf")))
            .thenReturn(stagedKey);
        drainUploadStream();

        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, file, null, hrManager))
            .isInstanceOf(DocumentAccessDeniedException.class);
        verify(minioStorageService).deleteObjectQuietly(stagedKey);
        verify(minioStorageService, never()).deleteObjectQuietly(oldKey);

        // DB commit failure -> deletes staged key, keeps oldKey intact
        clearInvocations(minioStorageService);
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(hrPublicDoc));
        when(documentRepository.findByDepartmentIdAndFileHash(eq(HR_DEPT_ID), anyString())).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        transactionManager.failOnCommit = true;

        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, file, null, hrManager))
            .isInstanceOf(TransactionSystemException.class);
        verify(minioStorageService).deleteObjectQuietly(stagedKey);
        verify(minioStorageService, never()).deleteObjectQuietly(oldKey);
    }

    @Test
    void replaceDocumentRejectsValidationSizeMimeAndStorageErrorsWithoutMutatingExistingDocument() {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity existingDoc = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        String oldKey = existingDoc.getStorageKey();
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(existingDoc));

        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

        // 400: 0-byte empty file
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, emptyFile, null, hrManager))
            .isInstanceOf(InvalidDocumentRequestException.class);

        // 413: declared size > 50 MiB
        MockMultipartFile oversizedFile = new MockMultipartFile("file", "huge.pdf", "application/pdf", "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public long getSize() {
                return HashingBoundedInputStream.MAX_FILE_SIZE_BYTES + 1L;
            }
        };
        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, oversizedFile, null, hrManager))
            .isInstanceOf(DocumentTooLargeException.class);

        // 415: PNG magic bytes disguised as .pdf
        byte[] pngHeader = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
        MockMultipartFile spoofedPdf = new MockMultipartFile("file", "spoof.pdf", "application/pdf", pngHeader);
        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, spoofedPdf, null, hrManager))
            .isInstanceOf(UnsupportedDocumentTypeException.class);

        verifyNoInteractions(minioStorageService);

        // 502: MinIO uploadStream failure leaves existing document and oldStorageKey untouched
        byte[] validPdf = "%PDF-1.4\n%Valid\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile validFile = new MockMultipartFile("file", "v2.pdf", "application/pdf", validPdf);
        when(minioStorageService.buildStorageKey(eq(HR_DEPT_ID), any(UUID.class), eq(2), eq("v2.pdf")))
            .thenReturn("departments/" + HR_DEPT_ID + "/failed-stage/v2/v2.pdf");
        doThrow(new DocumentStorageException("MinIO unavailable", null))
            .when(minioStorageService).uploadStream(anyString(), any(InputStream.class), anyLong(), anyString());

        assertThatThrownBy(() -> documentService.replaceDocument(DOC_ID, validFile, null, hrManager))
            .isInstanceOf(DocumentStorageException.class);

        verify(documentRepository, never()).findByIdForUpdate(any());
        verify(documentRepository, never()).saveAndFlush(any());
        verify(minioStorageService, never()).deleteObjectQuietly(oldKey);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void getDocumentPreviewUrlEnforcesRbacAppendsPdfPageFragmentAndValidatesPageParameter(CapturedOutput output) throws Exception {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity hrPdf = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        hrPdf.setOriginalName("hr-policy-v2.pdf");
        hrPdf.setFileExtension("pdf");
        hrPdf.setMimeType("application/pdf");
        hrPdf.setCurrentVersion(2);

        CallerContext hrEmployee = new CallerContext(null, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        CallerContext itEmployee = new CallerContext(null, IT_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        CallerContext itManager = new CallerContext(null, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext sysAdmin = new CallerContext(null, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");

        // Invalid page < 1 fails fast before DB or MinIO
        assertThatThrownBy(() -> documentService.getDocumentPreviewUrl(DOC_ID, 0, hrEmployee))
            .isInstanceOf(InvalidDocumentRequestException.class);
        assertThatThrownBy(() -> documentService.getDocumentPreviewUrl(DOC_ID, -3, hrEmployee))
            .isInstanceOf(InvalidDocumentRequestException.class);
        verifyNoInteractions(documentRepository, minioStorageService);

        // Missing doc -> 404
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> documentService.getDocumentPreviewUrl(DOC_ID, 2, hrEmployee))
            .isInstanceOf(DocumentNotFoundException.class);

        // Cross-department access -> 403 before touching MinIO
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(hrPdf));
        assertThatThrownBy(() -> documentService.getDocumentPreviewUrl(DOC_ID, 2, itEmployee))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> documentService.getDocumentPreviewUrl(DOC_ID, 2, itManager))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // Same-department STANDARD_EMPLOYEE on CONFIDENTIAL -> 403
        hrPdf.setAccessLevel(AccessLevel.CONFIDENTIAL);
        assertThatThrownBy(() -> documentService.getDocumentPreviewUrl(DOC_ID, 2, hrEmployee))
            .isInstanceOf(DocumentAccessDeniedException.class);
        verifyNoInteractions(minioStorageService);

        // Authorized PDF with ?page=4 -> issues download-gated URL with #page=4 and returns expiresAt
        OffsetDateTime beforeCall = OffsetDateTime.now(ZoneOffset.UTC);
        DocumentPreviewUrlResponse adminPreview = documentService.getDocumentPreviewUrl(DOC_ID, 4, sysAdmin);
        OffsetDateTime afterCall = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(adminPreview.documentId()).isEqualTo(DOC_ID);
        assertThat(adminPreview.originalName()).isEqualTo("hr-policy-v2.pdf");
        assertThat(adminPreview.mimeType()).isEqualTo("application/pdf");
        assertThat(adminPreview.fileExtension()).isEqualTo("pdf");
        assertThat(adminPreview.currentVersion()).isEqualTo(2);
        assertThat(adminPreview.pageNumber()).isEqualTo(4);
        assertThat(adminPreview.previewUrl())
            .startsWith("http://localhost:8081/api/v1/documents/" + DOC_ID + "/content?token=")
            .endsWith("#page=4");
        assertThat(adminPreview.expiresAt())
            .isAfterOrEqualTo(beforeCall.plusMinutes(15).minusSeconds(2))
            .isBeforeOrEqualTo(afterCall.plusMinutes(15).plusSeconds(2));

        // PDF without page -> omits #page fragment
        DocumentPreviewUrlResponse pdfNoPage = documentService.getDocumentPreviewUrl(DOC_ID, null, sysAdmin);
        assertThat(pdfNoPage.pageNumber()).isNull();
        assertThat(pdfNoPage.previewUrl()).doesNotContain("#page=");

        // Non-PDF (DOCX) with ?page=4 -> omits #page fragment and sets pageNumber=null; PUBLIC accessible cross-dept
        hrPdf.setAccessLevel(AccessLevel.PUBLIC);
        hrPdf.setOriginalName("guide.docx");
        hrPdf.setFileExtension("docx");
        hrPdf.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        DocumentPreviewUrlResponse docxPreview = documentService.getDocumentPreviewUrl(DOC_ID, 4, itEmployee);
        assertThat(docxPreview.pageNumber()).isNull();
        assertThat(docxPreview.fileExtension()).isEqualTo("docx");
        assertThat(docxPreview.previewUrl()).doesNotContain("#page=");

        // Download-time RBAC check: extract issued token for itEmployee and tighten ACL to CONFIDENTIAL -> 403 immediately!
        String docxToken = URI.create(docxPreview.previewUrl()).getQuery().substring("token=".length());
        byte[] docxPayload = "DOCX-BYTES".getBytes(StandardCharsets.UTF_8);
        hrPdf.setFileSizeBytes((long) docxPayload.length);
        when(minioStorageService.openStream(eq(hrPdf.getStorageKey()), eq(null))).thenAnswer(inv -> new ResponseInputStream<>(
            GetObjectResponse.builder().contentLength((long) docxPayload.length).build(),
            AbortableInputStream.create(new ByteArrayInputStream(docxPayload))
        ));
        MockHttpServletResponse okResp = new MockHttpServletResponse();
        documentService.streamDocumentContent(DOC_ID, docxToken, null, "127.0.0.1", okResp);
        assertThat(okResp.getStatus()).isEqualTo(200);
        assertThat(okResp.getContentAsByteArray()).isEqualTo(docxPayload);
        assertThat(okResp.getHeader("Cache-Control")).contains("no-store");

        // Range request out-of-bounds -> 416, valid range -> 206
        MockHttpServletResponse invalidRangeResp = new MockHttpServletResponse();
        documentService.streamDocumentContent(DOC_ID, docxToken, "bytes=999-1000", "127.0.0.1", invalidRangeResp);
        assertThat(invalidRangeResp.getStatus()).isEqualTo(416);

        when(minioStorageService.openStream(eq(hrPdf.getStorageKey()), eq("bytes=0-3"))).thenAnswer(inv -> new ResponseInputStream<>(
            GetObjectResponse.builder().contentLength(4L).build(),
            AbortableInputStream.create(new ByteArrayInputStream(docxPayload, 0, 4))
        ));
        MockHttpServletResponse partialResp = new MockHttpServletResponse();
        documentService.streamDocumentContent(DOC_ID, docxToken, "bytes=0-3", "127.0.0.1", partialResp);
        assertThat(partialResp.getStatus()).isEqualTo(206);
        assertThat(partialResp.getHeader("Content-Range")).isEqualTo("bytes 0-3/10");

        // Missing S3 object -> 404 DocumentNotFoundException
        when(minioStorageService.openStream(eq(hrPdf.getStorageKey()), eq(null)))
            .thenThrow(new DocumentStorageException("missing", NoSuchKeyException.builder().message("missing").build()));
        assertThatThrownBy(() -> documentService.streamDocumentContent(DOC_ID, docxToken, null, "127.0.0.1", new MockHttpServletResponse()))
            .isInstanceOf(DocumentNotFoundException.class);

        // ACL tightened to CONFIDENTIAL -> already-issued docxToken immediately throws DocumentAccessDeniedException (403)!
        hrPdf.setAccessLevel(AccessLevel.CONFIDENTIAL);
        assertThatThrownBy(() -> documentService.streamDocumentContent(DOC_ID, docxToken, null, "127.0.0.1", new MockHttpServletResponse()))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // Revoked JWT session on download -> 403 DocumentAccessDeniedException
        CustomUserDetails jwtPrincipal = new CustomUserDetails(
            HR_USER_ID, HR_DEPT_ID, "mgr@documind.ai", "HR Manager", Role.DEPARTMENT_MANAGER, "revoked-jti", Instant.now().plusSeconds(900)
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(jwtPrincipal, null, jwtPrincipal.getAuthorities())
        );
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(user(HR_USER_ID, hrDept, Role.DEPARTMENT_MANAGER, true)));
        DocumentPreviewUrlResponse jwtPreview = documentService.getDocumentPreviewUrl(
            DOC_ID, null, new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1")
        );
        String jwtPreviewToken = URI.create(jwtPreview.previewUrl()).getQuery().substring("token=".length());
        when(tokenBlacklistService.isTokenRevoked(eq("revoked-jti"), eq(HR_USER_ID), any(Instant.class))).thenReturn(true);
        assertThatThrownBy(() -> documentService.streamDocumentContent(DOC_ID, jwtPreviewToken, null, "127.0.0.1", new MockHttpServletResponse()))
            .isInstanceOf(DocumentAccessDeniedException.class);
        SecurityContextHolder.clearContext();

        // Verify SECURITY_AUDIT log hygiene: records both allow & deny actions and NEVER logs preview token query string
        assertThat(output.getOut())
            .contains("SECURITY_AUDIT action=DOC_PREVIEW_URL_DENIED")
            .contains("SECURITY_AUDIT action=DOC_PREVIEW_URL")
            .contains("SECURITY_AUDIT action=DOC_PREVIEW_DOWNLOAD_DENIED")
            .doesNotContain(docxToken)
            .doesNotContain(jwtPreviewToken);

        // Verify method-level @PreAuthorize Defense-in-Depth annotations exist on both DA-17 service methods
        Method replaceMethod = DocumentService.class.getMethod(
            "replaceDocument", UUID.class, org.springframework.web.multipart.MultipartFile.class, AccessLevel.class, CallerContext.class
        );
        assertThat(replaceMethod.isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class)).isTrue();
        Method previewMethod = DocumentService.class.getMethod(
            "getDocumentPreviewUrl", UUID.class, Integer.class, CallerContext.class
        );
        assertThat(previewMethod.isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class)).isTrue();
    }

    @Test
    void documentMutationsScheduleScopeAwareCacheVersionSyncAndSkipOnNoOpOrRollback() throws Exception {
        RagCacheService ragCacheService = mock(RagCacheService.class);
        DocumentService cacheAwareService = new DocumentService(
            documentRepository,
            departmentRepository,
            userRepository,
            auditLogRepository,
            fileValidationService,
            minioStorageService,
            previewTokenService,
            tokenBlacklistService,
            transactionTemplate,
            ragCacheService,
            eventPublisher,
            environment
        );

        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        CallerContext hrManager = new CallerContext(null, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        CallerContext sysAdmin = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");
        when(departmentRepository.findById(HR_DEPT_ID)).thenReturn(Optional.of(hrDept));
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(itDept));
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(user(ADMIN_USER_ID, itDept, Role.SYSTEM_ADMIN, true)));
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(inv -> {
            DocumentEntity doc = inv.getArgument(0);
            if (doc.getId() == null) {
                doc.setId(DOC_ID);
            }
            return doc;
        });

        // 1. Upload DEPARTMENT -> schedules sync for owning department only
        drainUploadStream();
        when(documentRepository.findByDepartmentIdAndFileHash(eq(HR_DEPT_ID), anyString())).thenReturn(Optional.empty());
        cacheAwareService.uploadDocument(
            new MockMultipartFile("file", "hr.txt", "text/plain", "HR policy".getBytes(StandardCharsets.UTF_8)),
            AccessLevel.DEPARTMENT,
            hrManager
        );
        verify(ragCacheService).schedulePostCommitSyncForDepartments(java.util.Set.of(HR_DEPT_ID));
        verify(ragCacheService, never()).schedulePostCommitSyncForAllDepartments();

        // 2. Upload PUBLIC -> schedules sync for ALL departments
        clearInvocations(ragCacheService);
        cacheAwareService.uploadDocument(
            new MockMultipartFile("file", "pub.txt", "text/plain", "Public policy".getBytes(StandardCharsets.UTF_8)),
            AccessLevel.PUBLIC,
            hrManager
        );
        verify(ragCacheService).schedulePostCommitSyncForAllDepartments();

        // 3. PATCH metadata: rename DEPARTMENT -> syncs HR only
        clearInvocations(ragCacheService);
        DocumentEntity hrDoc = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(hrDoc));
        cacheAwareService.updateDocumentMetadata(DOC_ID, new UpdateDocumentMetadataRequest("renamed.pdf", null, null), hrManager);
        verify(ragCacheService).schedulePostCommitSyncForDepartments(java.util.Set.of(HR_DEPT_ID));

        // 4. PATCH metadata: transfer HR -> IT (DEPARTMENT) -> syncs BOTH HR and IT
        clearInvocations(ragCacheService);
        when(documentRepository.findByDepartmentIdAndFileHash(eq(IT_DEPT_ID), anyString())).thenReturn(Optional.empty());
        cacheAwareService.updateDocumentMetadata(DOC_ID, new UpdateDocumentMetadataRequest(null, null, IT_DEPT_ID), sysAdmin);
        verify(ragCacheService).schedulePostCommitSyncForDepartments(java.util.Set.of(HR_DEPT_ID, IT_DEPT_ID));

        // 5. PATCH metadata: reclassify DEPARTMENT -> PUBLIC -> syncs ALL departments
        clearInvocations(ragCacheService);
        cacheAwareService.updateDocumentMetadata(DOC_ID, new UpdateDocumentMetadataRequest(null, AccessLevel.PUBLIC, null), sysAdmin);
        verify(ragCacheService).schedulePostCommitSyncForAllDepartments();

        // 6. PATCH metadata: no-op (same values) -> skips cache sync
        clearInvocations(ragCacheService);
        cacheAwareService.updateDocumentMetadata(DOC_ID, new UpdateDocumentMetadataRequest("renamed.pdf", AccessLevel.PUBLIC, IT_DEPT_ID), sysAdmin);
        verifyNoInteractions(ragCacheService);

        // 7. PUT replace (trigger-handled accessLevel change) -> syncs ALL because old/new includes PUBLIC
        clearInvocations(ragCacheService);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(hrDoc));
        when(minioStorageService.buildStorageKey(eq(IT_DEPT_ID), any(UUID.class), anyInt(), anyString()))
            .thenReturn("departments/" + IT_DEPT_ID + "/v2/v2.pdf");
        cacheAwareService.replaceDocument(
            DOC_ID,
            new MockMultipartFile("file", "v2.pdf", "application/pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8)),
            AccessLevel.DEPARTMENT,
            sysAdmin
        );
        verify(ragCacheService).schedulePostCommitSyncForAllDepartments();

        // 8. DELETE DEPARTMENT doc -> syncs owning department
        clearInvocations(ragCacheService);
        hrDoc.setAccessLevel(AccessLevel.DEPARTMENT);
        cacheAwareService.deleteDocument(DOC_ID, sysAdmin);
        verify(ragCacheService).schedulePostCommitSyncForDepartments(java.util.Set.of(IT_DEPT_ID));

        // 9. Rollback during delete with REAL RagCacheService -> afterCommit never runs, Redis untouched
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
            mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        RagCacheService realCacheService = new RagCacheService(
            redisTemplate,
            departmentRepository,
            documentRepository,
            null,
            null,
            previewTokenService,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            com.documind.service.rag.RagProperties.defaults(),
            Clock.systemUTC()
        );
        DocumentService rollbackService = new DocumentService(
            documentRepository,
            departmentRepository,
            userRepository,
            auditLogRepository,
            fileValidationService,
            minioStorageService,
            previewTokenService,
            tokenBlacklistService,
            transactionTemplate,
            realCacheService,
            eventPublisher,
            environment
        );
        transactionManager.setRollbackOnCommitFailure(true);
        transactionManager.failOnCommit = true;
        assertThatThrownBy(() -> rollbackService.deleteDocument(DOC_ID, sysAdmin))
            .isInstanceOf(TransactionSystemException.class);
        verifyNoInteractions(redisTemplate);
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

    private static UserEntity user(UUID id, DepartmentEntity dept, Role role, boolean active) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDepartment(dept);
        user.setRole(role);
        user.setIsActive(active);
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

    @Test
    void uploadAndReplaceEnforceRateLimitAfterRbacAndBeforeMinioStreaming() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        DocumentService limitedService = new DocumentService(
            documentRepository,
            departmentRepository,
            userRepository,
            auditLogRepository,
            fileValidationService,
            minioStorageService,
            previewTokenService,
            tokenBlacklistService,
            transactionTemplate,
            null,
            rateLimitService,
            eventPublisher,
            environment
        );

        byte[] txtBytes = "Rate limited document".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "policy.txt", "text/plain", txtBytes);

        // 1. Viewer rejected by RBAC before touching rate limiter or MinIO
        CallerContext viewer = new CallerContext(HR_USER_ID, IT_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        assertThatThrownBy(() -> limitedService.uploadDocument(file, AccessLevel.DEPARTMENT, viewer))
            .isInstanceOf(DocumentAccessDeniedException.class);
        verifyNoInteractions(rateLimitService, minioStorageService);

        // 2. Manager upload calls rateLimitService AFTER dept/actor checks and BEFORE MinIO streaming
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        UserEntity itManagerUser = user(HR_USER_ID, itDept, Role.DEPARTMENT_MANAGER, true);
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(itDept));
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(itManagerUser));
        CallerContext itManager = new CallerContext(HR_USER_ID, IT_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

        doThrow(new RateLimitExceededException(30L, "Rate limit exceeded for upload. Retry after 30 seconds"))
            .when(rateLimitService).consume(RateLimitOperation.UPLOAD, itManager, IT_DEPT_ID, "/api/v1/documents/upload");

        assertThatThrownBy(() -> limitedService.uploadDocument(file, AccessLevel.DEPARTMENT, itManager))
            .isInstanceOf(RateLimitExceededException.class);
        verify(rateLimitService).consume(RateLimitOperation.UPLOAD, itManager, IT_DEPT_ID, "/api/v1/documents/upload");
        verifyNoInteractions(minioStorageService);

        // 3. Cross-dept manager replace rejected before limiter; same-dept replace checks limiter before MinIO
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DocumentEntity hrDoc = document(DOC_ID, hrDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(hrDoc));
        assertThatThrownBy(() -> limitedService.replaceDocument(DOC_ID, file, AccessLevel.DEPARTMENT, itManager))
            .isInstanceOf(DocumentAccessDeniedException.class);

        DocumentEntity itDoc = document(DOC_ID, itDept, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(itDoc));
        doThrow(new RateLimitExceededException(15L, "Rate limit exceeded for upload. Retry after 15 seconds"))
            .when(rateLimitService).consume(RateLimitOperation.UPLOAD, itManager, IT_DEPT_ID, "/api/v1/documents/" + DOC_ID);

        assertThatThrownBy(() -> limitedService.replaceDocument(DOC_ID, file, AccessLevel.DEPARTMENT, itManager))
            .isInstanceOf(RateLimitExceededException.class);
        verify(rateLimitService).consume(RateLimitOperation.UPLOAD, itManager, IT_DEPT_ID, "/api/v1/documents/" + DOC_ID);
        verifyNoInteractions(minioStorageService);
    }
}
