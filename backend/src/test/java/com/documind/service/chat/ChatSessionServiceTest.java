package com.documind.service.chat;

import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.chat.ChatMessageResponse;
import com.documind.controller.dto.chat.ChatSessionResponse;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.entity.ChatSessionEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.enums.Role;
import com.documind.domain.model.CitationPayload;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.infrastructure.security.DocumentPreviewTokenService;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.ChatMessageRepository;
import com.documind.repository.ChatSessionRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.document.CallerContext;
import com.documind.service.rag.RagConstants;
import com.documind.service.rag.RagProperties;
import com.documind.service.rag.RbacVectorFilterFactory;
import com.documind.service.rag.RbacVectorScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatSessionServiceTest {

    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADMIN_USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID HR_EMPLOYEE_USER_ID = UUID.fromString("22222222-2222-3333-4444-555555555553");

    private ChatSessionRepository chatSessionRepository;
    private ChatMessageRepository chatMessageRepository;
    private UserRepository userRepository;
    private DepartmentRepository departmentRepository;
    private DocumentRepository documentRepository;
    private AuditLogRepository auditLogRepository;
    private DocumentPreviewTokenService previewTokenService;
    private RbacVectorFilterFactory rbacVectorFilterFactory;
    private TransactionTemplate transactionTemplate;
    private MockEnvironment environment;
    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        chatSessionRepository = mock(ChatSessionRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        userRepository = mock(UserRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        documentRepository = mock(DocumentRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        previewTokenService = mock(DocumentPreviewTokenService.class);
        environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("documind.security.dev-caller-fallback.enabled", "false");
        rbacVectorFilterFactory = new RbacVectorFilterFactory(environment);
        transactionTemplate = new TransactionTemplate(new RecordingTransactionManager());

        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        when(userRepository.findById(HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.of(user(HR_EMPLOYEE_USER_ID, hrDept, Role.STANDARD_EMPLOYEE, true)));
        when(userRepository.findById(ADMIN_USER_ID))
            .thenReturn(Optional.of(user(ADMIN_USER_ID, itDept, Role.SYSTEM_ADMIN, true)));
        when(departmentRepository.getReferenceById(any(UUID.class)))
            .thenAnswer(inv -> department(inv.getArgument(0), "DEPT"));

        when(chatSessionRepository.save(any(ChatSessionEntity.class))).thenAnswer(inv -> {
            ChatSessionEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(now);
            }
            if (entity.getUpdatedAt() == null) {
                entity.setUpdatedAt(now);
            }
            return entity;
        });
        when(chatMessageRepository.save(any(ChatMessageEntity.class))).thenAnswer(inv -> {
            ChatMessageEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });

        service = new ChatSessionService(
            providerOf(chatSessionRepository),
            providerOf(chatMessageRepository),
            providerOf(userRepository),
            providerOf(departmentRepository),
            providerOf(documentRepository),
            providerOf(auditLogRepository),
            previewTokenService,
            rbacVectorFilterFactory,
            providerOf(transactionTemplate),
            RagProperties.defaults()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createSessionDefaultsBlankTitleAndNormalizesExplicitTitleToNfc() {
        CallerContext employee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        setJwtPrincipal(employee);

        ChatSessionResponse defaultTitled = service.createSession("   ", employee);
        assertThat(defaultTitled.id()).isNotNull();
        assertThat(defaultTitled.title()).isEqualTo(ChatSessionService.DEFAULT_SESSION_TITLE);

        String nfdTitle = Normalizer.normalize("  Chính sách nghỉ phép năm 2026  ", Normalizer.Form.NFD);
        ChatSessionResponse customTitled = service.createSession(nfdTitle, employee);
        assertThat(customTitled.title()).isEqualTo("Chính sách nghỉ phép năm 2026");
        assertThat(Normalizer.isNormalized(customTitled.title(), Normalizer.Form.NFC)).isTrue();

        assertThatThrownBy(() -> service.createSession("bad\0title", employee))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> service.createSession("x".repeat(256), employee))
            .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void listSessionsValidatesPagingAndSortsByUpdatedAtDescIdDesc() {
        CallerContext employee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        setJwtPrincipal(employee);

        assertThatThrownBy(() -> service.listSessions(-1, 20, employee))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> service.listSessions(0, 0, employee))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> service.listSessions(0, 101, employee))
            .isInstanceOf(InvalidChatRequestException.class);

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(UUID.randomUUID());
        session.setTitle("Hỏi đáp HR");
        session.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(chatSessionRepository.findByUserId(eq(HR_EMPLOYEE_USER_ID), pageableCaptor.capture()))
            .thenReturn(new PageImpl<>(List.of(session)));

        PageResponse<ChatSessionResponse> result = service.listSessions(0, 20, employee);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Hỏi đáp HR");
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getSort().getOrderFor("updatedAt")).isEqualTo(Sort.Order.desc("updatedAt"));
        assertThat(captured.getSort().getOrderFor("id")).isEqualTo(Sort.Order.desc("id"));
    }

    @Test
    void getRenameDeleteEnforceStrictOwnerOnly404EvenForSystemAdmin() {
        CallerContext admin = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN, null);
        setJwtPrincipal(admin);
        UUID someoneElsesSessionId = UUID.randomUUID();
        when(chatSessionRepository.findByIdAndUserId(someoneElsesSessionId, ADMIN_USER_ID))
            .thenReturn(Optional.empty());
        when(chatSessionRepository.findByIdAndUserIdForUpdate(someoneElsesSessionId, ADMIN_USER_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSession(someoneElsesSessionId, admin))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.renameSession(someoneElsesSessionId, "Đổi tên trái phép", admin))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deleteSession(someoneElsesSessionId, admin))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listMessages(someoneElsesSessionId, admin))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listMessagesHydratesCitationsWithSingleBatchQueryReverifiesRbacAndRedactsRevokedOrDeletedDocs() throws Exception {
        CallerContext employee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        setJwtPrincipal(employee);

        UUID sessionId = UUID.randomUUID();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        session.setTitle("Phiên HR");
        when(chatSessionRepository.findByIdAndUserId(sessionId, HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.of(session));

        UUID allowedDocId = UUID.randomUUID();
        UUID revokedConfidentialDocId = UUID.randomUUID();
        UUID crossDeptDocId = UUID.randomUUID();
        UUID processingDocId = UUID.randomUUID();
        UUID deletedDocId = UUID.randomUUID();
        UUID storageErrorDocId = UUID.randomUUID();

        DocumentEntity allowedDoc = document(allowedDocId, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, "hr-handbook-v2.pdf", "hr/handbook.pdf");
        DocumentEntity revokedDoc = document(revokedConfidentialDocId, HR_DEPT_ID, AccessLevel.CONFIDENTIAL, DocumentStatus.COMPLETED, "hr-salary.pdf", "hr/salary.pdf");
        DocumentEntity crossDeptDoc = document(crossDeptDocId, SALES_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, "sales-bonus.pdf", "sales/bonus.pdf");
        DocumentEntity processingDoc = document(processingDocId, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.PROCESSING, "hr-draft.pdf", "hr/draft.pdf");
        DocumentEntity storageErrDoc = document(storageErrorDocId, SALES_DEPT_ID, AccessLevel.PUBLIC, DocumentStatus.COMPLETED, "public-guide.md", "sales/public.md");

        ChatMessageEntity userMsg1 = new ChatMessageEntity();
        userMsg1.setId(UUID.randomUUID());
        userMsg1.setSession(session);
        userMsg1.setRole(MessageRole.USER);
        userMsg1.setContent("Chính sách nghỉ phép và hướng dẫn chung?");
        userMsg1.setCreatedAt(OffsetDateTime.of(2026, 11, 21, 10, 0, 0, 0, ZoneOffset.UTC));

        ChatMessageEntity authorizedAssistantMsg = new ChatMessageEntity();
        authorizedAssistantMsg.setId(UUID.randomUUID());
        authorizedAssistantMsg.setSession(session);
        authorizedAssistantMsg.setRole(MessageRole.ASSISTANT);
        authorizedAssistantMsg.setContent("Nhân viên được 14 ngày phép.");
        authorizedAssistantMsg.setRewrittenQuery("Chính sách nghỉ phép công ty?");
        authorizedAssistantMsg.setCitations(List.of(
            new CitationPayload(allowedDocId, 1, "hr-handbook-old.pdf", 3, null, 0.91d, "Đoạn trích hợp lệ trang 3."),
            new CitationPayload(allowedDocId, 1, "hr-handbook-old.pdf", 4, null, 0.85d, "Đoạn trích hợp lệ trang 4."),
            new CitationPayload(storageErrorDocId, 1, "public-guide.md", null, "Tổng quan", 0.77d, "Hướng dẫn chung.")
        ));
        authorizedAssistantMsg.setCreatedAt(OffsetDateTime.of(2026, 11, 21, 10, 0, 1, 0, ZoneOffset.UTC));

        ChatMessageEntity revokedAssistantMsg = new ChatMessageEntity();
        revokedAssistantMsg.setId(UUID.randomUUID());
        revokedAssistantMsg.setSession(session);
        revokedAssistantMsg.setRole(MessageRole.ASSISTANT);
        revokedAssistantMsg.setContent("Trần lương bí mật là 100 triệu.");
        revokedAssistantMsg.setRewrittenQuery("Trần lương bí mật M3?");
        revokedAssistantMsg.setCitations(List.of(
            new CitationPayload(revokedConfidentialDocId, 1, "hr-salary.pdf", 1, "Mục lương mật", 0.88d, "Lương bí mật 100 triệu."),
            new CitationPayload(crossDeptDocId, 1, "sales-bonus.pdf", 2, "Thưởng kinh doanh", 0.84d, "Thưởng phòng Sales."),
            new CitationPayload(processingDocId, 1, "hr-draft.pdf", 1, null, 0.81d, "Bản nháp chưa hoàn tất."),
            new CitationPayload(deletedDocId, 1, "deleted-memo.pdf", 2, null, 0.79d, "Thông báo cũ đã xóa."),
            new CitationPayload(allowedDocId, 1, "hr-handbook-old.pdf", 3, null, 0.91d, "Đoạn trích hợp lệ còn quyền.")
        ));
        revokedAssistantMsg.setCreatedAt(OffsetDateTime.of(2026, 11, 21, 10, 0, 2, 0, ZoneOffset.UTC));

        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId))
            .thenReturn(List.of(userMsg1, authorizedAssistantMsg, revokedAssistantMsg));
        when(documentRepository.findAllById(anyIterable()))
            .thenReturn(List.of(allowedDoc, revokedDoc, crossDeptDoc, processingDoc, storageErrDoc));
        String allowedPreviewBase = "http://localhost:8081/api/v1/documents/" + allowedDocId + "/content?token=live";
        when(previewTokenService.issuePreviewUrl(eq(allowedDocId), eq(1), any(CallerContext.class)))
            .thenReturn(new DocumentPreviewTokenService.IssuedPreviewUrl(
                allowedPreviewBase,
                "live",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)
            ));
        when(previewTokenService.issuePreviewUrl(eq(storageErrorDocId), eq(1), any(CallerContext.class)))
            .thenThrow(new IllegalStateException("Temporary token signing glitch"));

        List<ChatMessageResponse> responses = service.listMessages(sessionId, employee);

        assertThat(responses).hasSize(3);
        verify(documentRepository, times(1)).findAllById(anyIterable());
        // Memoized: allowedDoc appeared twice but previewTokenService invoked only once
        verify(previewTokenService, times(1)).issuePreviewUrl(eq(allowedDocId), eq(1), any(CallerContext.class));
        verify(previewTokenService, never()).issuePreviewUrl(eq(revokedConfidentialDocId), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(previewTokenService, never()).issuePreviewUrl(eq(crossDeptDocId), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(previewTokenService, never()).issuePreviewUrl(eq(processingDocId), org.mockito.ArgumentMatchers.anyInt(), any());

        // Authorized turn preserves assistant prose, rewrittenQuery, and fresh preview URLs
        ChatMessageResponse authorizedResponse = responses.get(1);
        assertThat(authorizedResponse.content()).isEqualTo("Nhân viên được 14 ngày phép.");
        assertThat(authorizedResponse.rewrittenQuery()).isEqualTo("Chính sách nghỉ phép công ty?");
        List<CitationDto> authorizedCitations = authorizedResponse.citations();
        assertThat(authorizedCitations).hasSize(3);
        assertThat(authorizedCitations.get(0).fileName()).isEqualTo("hr-handbook-v2.pdf");
        assertThat(authorizedCitations.get(0).presignedUrl()).isEqualTo(allowedPreviewBase + "#page=3");
        assertThat(authorizedCitations.get(0).excerpt()).isEqualTo("Đoạn trích hợp lệ trang 3.");
        assertThat(authorizedCitations.get(1).presignedUrl()).isEqualTo(allowedPreviewBase + "#page=4");
        // MinIO storage failure -> graceful degradation (presignedUrl=null, excerpt intact for authorized doc)
        assertThat(authorizedCitations.get(2).presignedUrl()).isNull();
        assertThat(authorizedCitations.get(2).excerpt()).isEqualTo("Hướng dẫn chung.");

        // Revoked / cross-dept / non-COMPLETED / deleted cited turn fails closed:
        // redacts assistant content & rewrittenQuery, strips ALL restricted source metadata, and keeps ONLY still-authorized citation!
        ChatMessageResponse revokedResponse = responses.get(2);
        assertThat(revokedResponse.content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
        assertThat(revokedResponse.rewrittenQuery()).isNull();
        List<CitationDto> mixedHydratedCitations = revokedResponse.citations();
        assertThat(mixedHydratedCitations).hasSize(1);
        assertThat(mixedHydratedCitations.getFirst().documentId()).isEqualTo(allowedDocId);
        assertThat(mixedHydratedCitations.getFirst().docVersion()).isEqualTo(1);
        assertThat(mixedHydratedCitations.getFirst().fileName()).isEqualTo("hr-handbook-v2.pdf");
        assertThat(mixedHydratedCitations.getFirst().excerpt()).isEqualTo("Đoạn trích hợp lệ còn quyền.");
        assertThat(mixedHydratedCitations.getFirst().presignedUrl()).isEqualTo(allowedPreviewBase + "#page=3");
    }

    @Test
    void listMessagesAndLoadHistoryFailClosedOnMissingOrSupersededDocVersionWhileHydratingMatchingNonPdf() {
        CallerContext employee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        setJwtPrincipal(employee);
        RbacVectorScope scope = rbacVectorFilterFactory.resolveScope(employee, null);

        UUID sessionId = UUID.randomUUID();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        session.setTitle("Phiên HR Reindex");
        when(chatSessionRepository.findByIdAndUserId(sessionId, HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.of(session));

        UUID reindexedPublicDocId = UUID.randomUUID();
        DocumentEntity v2PublicDoc = document(
            reindexedPublicDocId,
            HR_DEPT_ID,
            AccessLevel.PUBLIC,
            DocumentStatus.COMPLETED,
            "hr-handbook-v2.docx",
            "hr/handbook-v2.docx"
        );
        v2PublicDoc.setFileExtension("docx");
        v2PublicDoc.setCurrentVersion(2);

        // 1. Turn citing v1 (before reindex to v2 PUBLIC) -> MUST be redacted in listMessages & excluded from loadAuthorizedHistoryForTurn!
        ChatMessageEntity v1UserMsg = new ChatMessageEntity();
        v1UserMsg.setId(UUID.randomUUID());
        v1UserMsg.setSession(session);
        v1UserMsg.setRole(MessageRole.USER);
        v1UserMsg.setContent("Mức thưởng mật ở v1?");
        v1UserMsg.setCreatedAt(OffsetDateTime.of(2026, 11, 22, 9, 59, 0, 0, ZoneOffset.UTC));

        ChatMessageEntity v1AssistantMsg = new ChatMessageEntity();
        v1AssistantMsg.setId(UUID.randomUUID());
        v1AssistantMsg.setSession(session);
        v1AssistantMsg.setRole(MessageRole.ASSISTANT);
        v1AssistantMsg.setContent("Nội dung mật từ v1: 500 triệu.");
        v1AssistantMsg.setCitations(List.of(
            new CitationPayload(reindexedPublicDocId, 1, "hr-secret-v1.pdf", 3, "Phụ lục mật v1", 0.90d, "Trích dẫn mật v1 trang 3.")
        ));
        v1AssistantMsg.setCreatedAt(OffsetDateTime.of(2026, 11, 22, 10, 0, 0, 0, ZoneOffset.UTC));

        // 2. Legacy turn missing docVersion (null) -> MUST also fail closed!
        ChatMessageEntity legacyNoVersionMsg = new ChatMessageEntity();
        legacyNoVersionMsg.setId(UUID.randomUUID());
        legacyNoVersionMsg.setSession(session);
        legacyNoVersionMsg.setRole(MessageRole.ASSISTANT);
        legacyNoVersionMsg.setContent("Nội dung cũ không lưu docVersion.");
        legacyNoVersionMsg.setCitations(List.of(
            new CitationPayload(reindexedPublicDocId, null, "hr-legacy.pdf", 2, "Mục cũ", 0.88d, "Trích dẫn cũ thiếu version.")
        ));
        legacyNoVersionMsg.setCreatedAt(OffsetDateTime.of(2026, 11, 22, 10, 1, 0, 0, ZoneOffset.UTC));

        // 3. Current v2 non-PDF turn -> authorized, preserves docVersion=2 and omits #page= fragment
        ChatMessageEntity v2AssistantMsg = new ChatMessageEntity();
        v2AssistantMsg.setId(UUID.randomUUID());
        v2AssistantMsg.setSession(session);
        v2AssistantMsg.setRole(MessageRole.ASSISTANT);
        v2AssistantMsg.setContent("Chính sách công khai v2.");
        v2AssistantMsg.setCitations(List.of(
            new CitationPayload(reindexedPublicDocId, 2, "hr-handbook-v2.docx", 3, "Chương 1", 0.92d, "Trích dẫn v2 hợp lệ.")
        ));
        v2AssistantMsg.setCreatedAt(OffsetDateTime.of(2026, 11, 22, 10, 2, 0, 0, ZoneOffset.UTC));

        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId))
            .thenReturn(List.of(v1UserMsg, v1AssistantMsg, legacyNoVersionMsg, v2AssistantMsg));
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(eq(sessionId), any(Pageable.class)))
            .thenReturn(List.of(v2AssistantMsg, legacyNoVersionMsg, v1AssistantMsg, v1UserMsg));
        when(documentRepository.findAllById(anyIterable()))
            .thenReturn(List.of(v2PublicDoc));

        String upgradedBaseUrl = "http://localhost:8081/api/v1/documents/" + reindexedPublicDocId + "/content?token=live-v2";
        when(previewTokenService.issuePreviewUrl(eq(reindexedPublicDocId), eq(2), any(CallerContext.class)))
            .thenReturn(new DocumentPreviewTokenService.IssuedPreviewUrl(
                upgradedBaseUrl,
                "live-v2",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)
            ));

        List<ChatMessageResponse> responses = service.listMessages(sessionId, employee);
        assertThat(responses).hasSize(4);

        // v1 turn redacted and restricted/superseded citation metadata completely stripped
        assertThat(responses.get(1).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
        assertThat(responses.get(1).citations()).isEmpty();

        // Legacy missing-docVersion turn redacted and citation stripped
        assertThat(responses.get(2).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
        assertThat(responses.get(2).citations()).isEmpty();

        // Matching v2 non-PDF turn hydrated cleanly without #page=
        assertThat(responses.get(3).content()).isEqualTo("Chính sách công khai v2.");
        assertThat(responses.get(3).citations()).hasSize(1);
        CitationDto hydrated = responses.get(3).citations().getFirst();
        assertThat(hydrated.docVersion()).isEqualTo(2);
        assertThat(hydrated.fileName()).isEqualTo("hr-handbook-v2.docx");
        assertThat(hydrated.pageNumber()).isNull();
        assertThat(hydrated.presignedUrl())
            .isEqualTo(upgradedBaseUrl)
            .doesNotContain("#page=");

        // Follow-up history loader excludes BOTH v1 pair (v1UserMsg + v1AssistantMsg) AND legacyNoVersionMsg!
        List<ChatMessageEntity> authorizedHistory = service.loadAuthorizedHistoryForTurn(sessionId, HR_EMPLOYEE_USER_ID, scope);
        assertThat(authorizedHistory).containsExactly(v2AssistantMsg);
    }

    @Test
    void deriveAutoTitleHandlesVietnameseNfcWhitespaceCollapsingWordBoundaryAndAstralSurrogatePairs() {
        assertThat(service.deriveAutoTitle(null)).isEqualTo(ChatSessionService.DEFAULT_SESSION_TITLE);
        assertThat(service.deriveAutoTitle("   \n\t  ")).isEqualTo(ChatSessionService.DEFAULT_SESSION_TITLE);

        String exact80 = "a".repeat(RagProperties.DEFAULT_AUTO_TITLE_MAX_CHARS);
        assertThat(service.deriveAutoTitle(exact80)).isEqualTo(exact80);

        String spacelessLong = "x".repeat(120);
        String spacelessTruncated = service.deriveAutoTitle(spacelessLong);
        assertThat(spacelessTruncated.length()).isEqualTo(RagProperties.DEFAULT_AUTO_TITLE_MAX_CHARS);
        assertThat(spacelessTruncated).endsWith("...");

        String shortNfd = Normalizer.normalize("Chính sách\n\tnghỉ phép   năm?", Normalizer.Form.NFD);
        String shortTitle = service.deriveAutoTitle(shortNfd);
        assertThat(shortTitle).isEqualTo("Chính sách nghỉ phép năm?");
        assertThat(Normalizer.isNormalized(shortTitle, Normalizer.Form.NFC)).isTrue();

        String longQuestion = "Nhân viên chính thức làm việc tại công ty trên năm năm thâm niên thì được hưởng tổng cộng bao nhiêu ngày nghỉ phép năm có nguyên lương?";
        String truncated = service.deriveAutoTitle(longQuestion);
        assertThat(truncated.length()).isLessThanOrEqualTo(RagProperties.DEFAULT_AUTO_TITLE_MAX_CHARS);
        assertThat(truncated).endsWith("...");
        assertThat(truncated).doesNotContain("\uFFFD");

        // Place an astral surrogate pair ("\uD83D\uDCC4") directly across cut index 76..77 (budget = 77)
        String astralAtBoundary = "t".repeat(76) + "\uD83D\uDCC4" + "tail";
        String astralTruncated = service.deriveAutoTitle(astralAtBoundary);
        assertThat(astralTruncated).isEqualTo("t".repeat(76) + "...");
        assertThat(astralTruncated.length()).isLessThanOrEqualTo(RagProperties.DEFAULT_AUTO_TITLE_MAX_CHARS);
        for (int i = 0; i < astralTruncated.length(); i++) {
            assertThat(Character.isSurrogate(astralTruncated.charAt(i))).isFalse();
        }
    }

    @Test
    void loadAuthorizedHistoryFiltersRevokedTurnsAndRecordCompletedTurnPersistsMonotonically() {
        CallerContext employee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.1");
        setJwtPrincipal(employee);
        RbacVectorScope scope = rbacVectorFilterFactory.resolveScope(employee, null);

        UUID sessionId = UUID.randomUUID();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        session.setTitle(ChatSessionService.DEFAULT_SESSION_TITLE);
        OffsetDateTime t1 = OffsetDateTime.of(2026, 11, 21, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime t2 = OffsetDateTime.of(2026, 11, 21, 10, 0, 5, 0, ZoneOffset.UTC);
        session.setCreatedAt(t1);
        session.setUpdatedAt(t2);

        UUID allowedDocId = UUID.randomUUID();
        UUID revokedDocId = UUID.randomUUID();
        DocumentEntity allowedDoc = document(allowedDocId, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, "hr-leave.pdf", "hr/leave.pdf");
        DocumentEntity revokedDoc = document(revokedDocId, HR_DEPT_ID, AccessLevel.CONFIDENTIAL, DocumentStatus.COMPLETED, "hr-salary.pdf", "hr/salary.pdf");

        ChatMessageEntity olderUser = new ChatMessageEntity();
        olderUser.setId(UUID.randomUUID());
        olderUser.setRole(MessageRole.USER);
        olderUser.setContent("Nghỉ phép năm?");
        olderUser.setCreatedAt(t1);

        ChatMessageEntity olderAssistant = new ChatMessageEntity();
        olderAssistant.setId(UUID.randomUUID());
        olderAssistant.setRole(MessageRole.ASSISTANT);
        olderAssistant.setContent("14 ngày phép.");
        olderAssistant.setCitations(List.of(new CitationPayload(allowedDocId, 1, "hr-leave.pdf", 1, null, 0.9d, "14 ngày phép.")));
        olderAssistant.setCreatedAt(t2);

        ChatMessageEntity revokedUser = new ChatMessageEntity();
        revokedUser.setId(UUID.randomUUID());
        revokedUser.setRole(MessageRole.USER);
        revokedUser.setContent("Lương giám đốc?");
        revokedUser.setCreatedAt(t2.plusSeconds(1));

        ChatMessageEntity revokedAssistant = new ChatMessageEntity();
        revokedAssistant.setId(UUID.randomUUID());
        revokedAssistant.setRole(MessageRole.ASSISTANT);
        revokedAssistant.setContent("Bí mật 100 triệu.");
        revokedAssistant.setCitations(List.of(new CitationPayload(revokedDocId, "hr-salary.pdf", 1, null, 0.9d, "100 triệu.")));
        revokedAssistant.setCreatedAt(t2.plusSeconds(2));

        when(chatSessionRepository.findByIdAndUserId(sessionId, HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.of(session));
        // Repository returns DESC (newest first): [revokedAssistant, revokedUser, olderAssistant, olderUser, leadingRevokedAssistant]
        ChatMessageEntity leadingRevokedAssistant = new ChatMessageEntity();
        leadingRevokedAssistant.setId(UUID.randomUUID());
        leadingRevokedAssistant.setRole(MessageRole.ASSISTANT);
        leadingRevokedAssistant.setContent("Bí mật từ cửa sổ lẻ trước đó.");
        leadingRevokedAssistant.setCitations(List.of(new CitationPayload(revokedDocId, "hr-salary.pdf", 1, null, 0.9d, "100 triệu.")));
        leadingRevokedAssistant.setCreatedAt(t1.minusSeconds(1));

        when(chatMessageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(eq(sessionId), any(Pageable.class)))
            .thenReturn(List.of(revokedAssistant, revokedUser, olderAssistant, olderUser, leadingRevokedAssistant));
        when(documentRepository.findAllById(anyIterable())).thenReturn(List.of(allowedDoc, revokedDoc));

        List<ChatMessageEntity> loaded = service.loadAuthorizedHistoryForTurn(sessionId, HR_EMPLOYEE_USER_ID, scope);
        assertThat(loaded).containsExactly(olderUser, olderAssistant);

        // Now test Turn 1 auto-title + atomic turn persistence under pessimistic write lock
        when(chatSessionRepository.findByIdAndUserIdForUpdate(sessionId, HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.of(session));
        when(chatMessageRepository.findFirstBySessionIdOrderByCreatedAtDescIdDesc(sessionId))
            .thenReturn(Optional.empty());
        UserEntity actor = userRepository.findById(HR_EMPLOYEE_USER_ID).orElseThrow();
        List<ChatMessageEntity> savedMessages = new ArrayList<>();
        when(chatMessageRepository.save(any(ChatMessageEntity.class))).thenAnswer(inv -> {
            ChatMessageEntity msg = inv.getArgument(0);
            if (msg.getId() == null) {
                msg.setId(UUID.randomUUID());
            }
            savedMessages.add(msg);
            return msg;
        });

        ChatSessionService.PersistedTurnResult persisted = service.recordCompletedTurn(
            new ChatSessionService.CompletedTurnCommand(
                actor,
                HR_DEPT_ID,
                sessionId,
                "Quy định nghỉ phép năm?",
                null,
                "Nhân viên có 12 ngày phép năm.",
                List.of(),
                false,
                RagConstants.PROVIDER_GROQ,
                100,
                30,
                120L,
                "10.0.0.1"
            )
        );

        assertThat(persisted.sessionId()).isEqualTo(sessionId);
        assertThat(persisted.assistantMessageId()).isNotNull();
        assertThat(session.getTitle()).isEqualTo("Quy định nghỉ phép năm?");
        assertThat(savedMessages).hasSize(2);
        assertThat(savedMessages.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(savedMessages.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(savedMessages.get(1).getCreatedAt()).isAfter(savedMessages.get(0).getCreatedAt());
        verify(chatSessionRepository).findByIdAndUserIdForUpdate(sessionId, HR_EMPLOYEE_USER_ID);

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getActionType()).isEqualTo(AuditActionType.CHAT_QUERY);
        assertThat(auditCaptor.getValue().getSessionId()).isEqualTo(sessionId);
        assertThat(auditCaptor.getValue().getCacheTier()).isEqualTo(CacheTier.NONE);
    }

    @Test
    void recordCompletedTurnCommitsFinOpsAuditAndThrows404WhenSessionDeletedMidFlight() {
        CallerContext employee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.1");
        setJwtPrincipal(employee);
        UserEntity actor = userRepository.findById(HR_EMPLOYEE_USER_ID).orElseThrow();
        UUID deletedSessionId = UUID.randomUUID();
        when(chatSessionRepository.findByIdAndUserIdForUpdate(deletedSessionId, HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordCompletedTurn(new ChatSessionService.CompletedTurnCommand(
            actor,
            HR_DEPT_ID,
            deletedSessionId,
            "Nếu làm đủ 5 năm?",
            "Làm đủ 5 năm được cộng thêm mấy ngày phép năm?",
            "Được cộng thêm 1 ngày phép.",
            List.of(),
            false,
            RagConstants.PROVIDER_GROQ,
            165,
            50,
            240L,
            "10.0.0.1"
        ))).isInstanceOf(ResourceNotFoundException.class);

        verify(chatMessageRepository, never()).save(any());
        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLogEntity savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getSessionId()).isNull();
        assertThat(savedAudit.getStatusCode()).isEqualTo(404);
        assertThat(savedAudit.getPromptTokens()).isEqualTo(165);
        assertThat(savedAudit.getCompletionTokens()).isEqualTo(50);
        assertThat(savedAudit.getProvider()).isEqualTo(RagConstants.PROVIDER_GROQ);

        // Also test recordFailureAudit (502) handles both active session and concurrently deleted session without FK failure
        clearInvocations(auditLogRepository);
        when(chatSessionRepository.findByIdAndUserIdForUpdate(deletedSessionId, HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.empty());
        service.recordFailureAudit(actor, HR_DEPT_ID, deletedSessionId, "Thắc mắc 502?", 45, 14, 95L, RagConstants.PROVIDER_GROQ, "10.0.0.1");
        ArgumentCaptor<AuditLogEntity> failureAuditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(failureAuditCaptor.capture());
        assertThat(failureAuditCaptor.getValue().getSessionId()).isNull();
        assertThat(failureAuditCaptor.getValue().getStatusCode()).isEqualTo(502);
        assertThat(failureAuditCaptor.getValue().isAbstained()).isTrue();
        assertThat(failureAuditCaptor.getValue().getPromptTokens()).isEqualTo(45);
        assertThat(failureAuditCaptor.getValue().getCompletionTokens()).isEqualTo(14);
    }

    @Test
    void renameSessionNormalizesNfcAndRejectsInvalidTitles() {
        CallerContext employee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        setJwtPrincipal(employee);
        UUID sessionId = UUID.randomUUID();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        session.setTitle("Cũ");
        when(chatSessionRepository.findByIdAndUserIdForUpdate(sessionId, HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.of(session));

        String nfdNewTitle = Normalizer.normalize("  Chính sách mới 2026  ", Normalizer.Form.NFD);
        ChatSessionResponse renamed = service.renameSession(sessionId, nfdNewTitle, employee);
        assertThat(renamed.title()).isEqualTo("Chính sách mới 2026");
        assertThat(Normalizer.isNormalized(renamed.title(), Normalizer.Form.NFC)).isTrue();

        assertThatThrownBy(() -> service.renameSession(sessionId, null, employee))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> service.renameSession(sessionId, "  \uFEFF  ", employee))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> service.renameSession(sessionId, "bad\0title", employee))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> service.renameSession(sessionId, "x".repeat(256), employee))
            .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void recordCompletedTurnPreservesCustomTitleAndFailsClosedOnUnauthenticatedOrInactiveUser() {
        CallerContext employee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        // Unauthenticated fails closed with 401
        assertThatThrownBy(() -> service.createSession("Test", employee))
            .isInstanceOf(AuthenticationRequiredException.class);

        // Inactive user fails with 403
        setJwtPrincipal(employee);
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        when(userRepository.findById(HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.of(user(HR_EMPLOYEE_USER_ID, hrDept, Role.STANDARD_EMPLOYEE, false)));
        assertThatThrownBy(() -> service.createSession("Test", employee))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // Active user + pre-created session with custom title keeps custom title on Turn 1
        UserEntity activeUser = user(HR_EMPLOYEE_USER_ID, hrDept, Role.STANDARD_EMPLOYEE, true);
        when(userRepository.findById(HR_EMPLOYEE_USER_ID)).thenReturn(Optional.of(activeUser));

        UUID sessionId = UUID.randomUUID();
        ChatSessionEntity customSession = new ChatSessionEntity();
        customSession.setId(sessionId);
        customSession.setTitle("Tiêu đề tự đặt của tôi");
        when(chatSessionRepository.findByIdAndUserIdForUpdate(sessionId, HR_EMPLOYEE_USER_ID))
            .thenReturn(Optional.of(customSession));
        when(chatMessageRepository.findFirstBySessionIdOrderByCreatedAtDescIdDesc(sessionId))
            .thenReturn(Optional.empty());

        service.recordCompletedTurn(new ChatSessionService.CompletedTurnCommand(
            activeUser,
            HR_DEPT_ID,
            sessionId,
            "Câu hỏi đầu tiên sẽ không ghi đè tiêu đề riêng?",
            null,
            "Câu trả lời",
            List.of(),
            false,
            RagConstants.PROVIDER_GROQ,
            80,
            20,
            50L,
            null
        ));
        assertThat(customSession.getTitle()).isEqualTo("Tiêu đề tự đặt của tôi");
        verifyNoInteractions(previewTokenService);
    }

    @Test
    void chatSessionServiceDoesNotDeclareTransactionalAndDeclaresPreAuthorize() throws NoSuchMethodException {
        assertThat(ChatSessionService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(ChatSessionService.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        for (Method method : ChatSessionService.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
            assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        }
        for (Method method : List.of(
            ChatSessionService.class.getMethod("createSession", String.class, CallerContext.class),
            ChatSessionService.class.getMethod("listSessions", int.class, int.class, CallerContext.class),
            ChatSessionService.class.getMethod("getSession", UUID.class, CallerContext.class),
            ChatSessionService.class.getMethod("listMessages", UUID.class, CallerContext.class),
            ChatSessionService.class.getMethod("renameSession", UUID.class, String.class, CallerContext.class),
            ChatSessionService.class.getMethod("deleteSession", UUID.class, CallerContext.class),
            ChatSessionService.class.getMethod("loadAuthorizedHistoryForTurn", UUID.class, UUID.class, RbacVectorScope.class),
            ChatSessionService.class.getMethod("recordCompletedTurn", ChatSessionService.CompletedTurnCommand.class),
            ChatSessionService.class.getMethod("recordFailureAudit", UserEntity.class, UUID.class, UUID.class, String.class, int.class, int.class, long.class, String.class, String.class)
        )) {
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value())
                .contains("SYSTEM_ADMIN")
                .contains("DEPARTMENT_MANAGER")
                .contains("STANDARD_EMPLOYEE");
        }
    }

    private static void setJwtPrincipal(CallerContext caller) {
        CustomUserDetails principal = new CustomUserDetails(
            caller.userId(),
            caller.departmentId(),
            caller.role().name().toLowerCase() + "@documind.ai",
            caller.role().name(),
            caller.role(),
            "jti-" + caller.userId(),
            Instant.now().plusSeconds(900)
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static DepartmentEntity department(UUID id, String code) {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(id);
        dept.setCode(code);
        dept.setName(code);
        return dept;
    }

    private static UserEntity user(UUID id, DepartmentEntity dept, Role role, boolean active) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDepartment(dept);
        user.setEmail(role.name().toLowerCase() + "@documind.ai");
        user.setPasswordHash("hash");
        user.setFullName(role.name());
        user.setRole(role);
        user.setIsActive(active);
        return user;
    }

    private static DocumentEntity document(
        UUID id,
        UUID deptId,
        AccessLevel accessLevel,
        DocumentStatus status,
        String originalName,
        String storageKey
    ) {
        DepartmentEntity dept = department(deptId, "DEPT");
        DocumentEntity doc = new DocumentEntity();
        doc.setId(id);
        doc.setDepartment(dept);
        doc.setAccessLevel(accessLevel);
        doc.setStatus(status);
        doc.setOriginalName(originalName);
        doc.setStorageKey(storageKey);
        return doc;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final Object TX_RESOURCE_KEY = new Object();

        private record TxHandle(boolean active) {
        }

        @Override
        protected Object doGetTransaction() {
            return new TxHandle(TransactionSynchronizationManager.hasResource(TX_RESOURCE_KEY));
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return transaction instanceof TxHandle handle && handle.active();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TransactionSynchronizationManager.bindResource(TX_RESOURCE_KEY, Boolean.TRUE);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            TransactionSynchronizationManager.unbindResourceIfPossible(TX_RESOURCE_KEY);
        }
    }
}
