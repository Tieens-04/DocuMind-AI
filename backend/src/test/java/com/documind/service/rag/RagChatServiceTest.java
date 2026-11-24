package com.documind.service.rag;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.enums.Role;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.LlmProviderException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.ai.AiProperties;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.chat.ChatSessionService;
import com.documind.service.document.CallerContext;
import com.documind.service.document.DevFallbackCallerContextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID HR_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ADMIN_USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private VectorRetrievalService vectorRetrievalService;
    private GroundedSystemPromptService groundedSystemPromptService;
    private RbacVectorFilterFactory rbacVectorFilterFactory;
    private ChatModel groqChatModel;
    private ChatModel geminiChatModel;
    private ChatClient groqChatClient;
    private ChatClient geminiChatClient;
    private UserRepository userRepository;
    private DepartmentRepository departmentRepository;
    private AuditLogRepository auditLogRepository;
    private TransactionTemplate transactionTemplate;
    private MockEnvironment environment;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        vectorRetrievalService = mock(VectorRetrievalService.class);
        groundedSystemPromptService = new GroundedSystemPromptService();
        groqChatModel = mock(ChatModel.class);
        geminiChatModel = mock(ChatModel.class);
        groqChatClient = ChatClient.create(groqChatModel);
        geminiChatClient = ChatClient.create(geminiChatModel);
        userRepository = mock(UserRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        transactionTemplate = new TransactionTemplate(new RecordingTransactionManager());
        environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("documind.security.dev-caller-fallback.enabled", "true");
        rbacVectorFilterFactory = new RbacVectorFilterFactory(environment);
        ragProperties = RagProperties.defaults();

        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        UserEntity hrEmployee = user(HR_USER_ID, hrDept, Role.STANDARD_EMPLOYEE, true);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(hrEmployee));

        DepartmentEntity itDept = department(IT_DEPT_ID, "IT");
        UserEntity adminUser = user(ADMIN_USER_ID, itDept, Role.SYSTEM_ADMIN, true);
        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(adminUser));

        when(departmentRepository.getReferenceById(any(UUID.class)))
            .thenAnswer(inv -> department(inv.getArgument(0), "DEPT"));
        clearInvocations(groqChatModel, geminiChatModel);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void askShortCircuitsWithoutCallingLlmWhenNoChunksPassThreshold() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.5");
        setJwtPrincipal(caller);
        when(vectorRetrievalService.retrieve(eq("Giá Bitcoin hôm nay là bao nhiêu?"), any(RbacVectorScope.class)))
            .thenReturn(List.of());

        ChatAskResponse response = service.ask("  Giá Bitcoin hôm nay là bao nhiêu?  ", caller);

        assertThat(response.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
        assertThat(response.abstained()).isTrue();
        assertThat(response.citations()).isEmpty();
        assertThat(response.modelProvider()).isNull();
        assertThat(response.promptTokens()).isZero();
        assertThat(response.completionTokens()).isZero();
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0L);

        verifyNoInteractions(groqChatModel, geminiChatModel);

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLogEntity savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getActionType()).isEqualTo(AuditActionType.CHAT_QUERY);
        assertThat(savedAudit.isAbstained()).isTrue();
        assertThat(savedAudit.isCacheHit()).isFalse();
        assertThat(savedAudit.getCacheTier()).isEqualTo(CacheTier.NONE);
        assertThat(savedAudit.getPromptTokens()).isZero();
        assertThat(savedAudit.getCompletionTokens()).isZero();
        assertThat(savedAudit.getProvider()).isNull();
        assertThat(savedAudit.getStatusCode()).isEqualTo(200);
        assertThat(savedAudit.getIpAddress()).isEqualTo("10.0.0.5");
        verify(departmentRepository, never()).findById(any());
        verify(departmentRepository).getReferenceById(HR_DEPT_ID);
    }

    @Test
    void askInvokesPrimaryLlmOutsideTransactionCapturesUsageAndPreservesSpecialCharacters() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "203.0.113.25");
        setJwtPrincipal(caller);

        RetrievedChunk chunk = sampleChunk(HR_DEPT_ID, AccessLevel.DEPARTMENT, "handbook.pdf", 3, "Nhân viên chính thức có 12 ngày phép năm.");
        String questionWithBraces = "Chính sách nghỉ phép {annual} cho ${USER} là bao nhiêu?";
        AtomicBoolean txActiveDuringRetrieval = new AtomicBoolean(true);
        when(vectorRetrievalService.retrieve(eq(questionWithBraces), any(RbacVectorScope.class))).thenAnswer(inv -> {
            txActiveDuringRetrieval.set(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(chunk);
        });

        AtomicBoolean txActiveDuringLlm = new AtomicBoolean(true);
        when(groqChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            txActiveDuringLlm.set(TransactionSynchronizationManager.isActualTransactionActive());
            Prompt prompt = inv.getArgument(0);
            assertThat(prompt.getInstructions()).hasSize(2);
            assertThat(prompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
            assertThat(prompt.getInstructions().get(1)).isInstanceOf(UserMessage.class);
            assertThat(prompt.getInstructions().get(1).getText()).isEqualTo(questionWithBraces);
            return chatResponse("Nhân viên chính thức có 12 ngày phép năm [Nguồn: handbook.pdf - Trang 3].", 145, 38);
        });

        AtomicBoolean txActiveDuringAuditSave = new AtomicBoolean(false);
        when(auditLogRepository.save(any(AuditLogEntity.class))).thenAnswer(inv -> {
            txActiveDuringAuditSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.getArgument(0);
        });

        String nfdQuestion = Normalizer.normalize(questionWithBraces, Normalizer.Form.NFD);
        ChatAskResponse response = service.ask(nfdQuestion, caller);

        assertThat(txActiveDuringRetrieval.get()).isFalse();
        assertThat(txActiveDuringLlm.get()).isFalse();
        assertThat(txActiveDuringAuditSave.get()).isTrue();
        assertThat(response.answer()).isEqualTo("Nhân viên chính thức có 12 ngày phép năm [Nguồn: handbook.pdf - Trang 3].");
        assertThat(response.abstained()).isFalse();
        assertThat(response.citations()).containsExactly(chunk.citation());
        assertThat(response.modelProvider()).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat(response.promptTokens()).isEqualTo(145);
        assertThat(response.completionTokens()).isEqualTo(38);
        verifyNoInteractions(geminiChatModel);

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLogEntity savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.isAbstained()).isFalse();
        assertThat(savedAudit.getPromptTokens()).isEqualTo(145);
        assertThat(savedAudit.getCompletionTokens()).isEqualTo(38);
        assertThat(savedAudit.getProvider()).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat(savedAudit.getStatusCode()).isEqualTo(200);
        verify(departmentRepository, never()).findById(any());
        verify(departmentRepository).getReferenceById(HR_DEPT_ID);
    }

    @Test
    void askSupportsSystemAdminOptionalDepartmentFilterWithCallerDepartmentAuditAttributionAnd404Check() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext adminCaller = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN, "10.1.1.1");
        setJwtPrincipal(adminCaller);

        UUID missingDeptId = UUID.randomUUID();
        when(departmentRepository.existsById(missingDeptId)).thenReturn(false);
        assertThatThrownBy(() -> service.ask("Chính sách phòng ban?", missingDeptId, adminCaller))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(vectorRetrievalService, groqChatModel, geminiChatModel);

        when(departmentRepository.existsById(HR_DEPT_ID)).thenReturn(true);
        RetrievedChunk hrConfidential = sampleChunk(HR_DEPT_ID, AccessLevel.CONFIDENTIAL, "hr-comp.pdf", 1, "Bảng lương nội bộ HR.");
        ArgumentCaptor<RbacVectorScope> scopeCaptor = ArgumentCaptor.forClass(RbacVectorScope.class);
        when(vectorRetrievalService.retrieve(eq("Bảng lương HR?"), scopeCaptor.capture()))
            .thenReturn(List.of(hrConfidential));
        when(groqChatModel.call(any(Prompt.class)))
            .thenReturn(chatResponse("Bảng lương HR [Nguồn: hr-comp.pdf - Trang 1].", 100, 20));

        ChatAskResponse response = service.ask("Bảng lương HR?", HR_DEPT_ID, adminCaller);

        assertThat(response.abstained()).isFalse();
        assertThat(scopeCaptor.getValue().filterDepartmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(scopeCaptor.getValue().cacheScopeKey()).isEqualTo("ADMIN:DEPT:" + HR_DEPT_ID);

        // FinOps attribution records caller's own department (IT_DEPT_ID), not target HR_DEPT_ID
        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getDepartmentId()).isEqualTo(IT_DEPT_ID);
        verify(departmentRepository).getReferenceById(IT_DEPT_ID);
    }

    @Test
    void askRejectsNonAdminDepartmentFilterAndBlocksUnauthorizedChunksBeforePromptAssembly() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext hrEmployee = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.5");
        setJwtPrincipal(hrEmployee);

        // Non-admin attempting departmentId filter -> 403 immediately
        assertThatThrownBy(() -> service.ask("Chính sách bán hàng?", SALES_DEPT_ID, hrEmployee))
            .isInstanceOf(DocumentAccessDeniedException.class);
        verifyNoInteractions(vectorRetrievalService, groqChatModel, geminiChatModel);

        // Pre-prompt Defense-in-Depth guard blocks leaked CONFIDENTIAL chunk with 0 LLM calls
        RetrievedChunk leakedConfidential = sampleChunk(HR_DEPT_ID, AccessLevel.CONFIDENTIAL, "hr-secret.pdf", 1, "Lương CEO.");
        when(vectorRetrievalService.retrieve(eq("Lương CEO?"), any(RbacVectorScope.class)))
            .thenReturn(List.of(leakedConfidential));
        assertThatThrownBy(() -> service.ask("Lương CEO?", hrEmployee))
            .isInstanceOf(DocumentAccessDeniedException.class);
        verifyNoInteractions(groqChatModel, geminiChatModel);
    }

    @Test
    void askFailsClosedWith401WhenSecurityContextAbsentAnd403WhenCallerMismatchesBeforeValidation() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext hrEmployee = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.5");
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.ask("Chính sách phép năm?", hrEmployee))
            .isInstanceOf(AuthenticationRequiredException.class);
        // RV-05 regression: null caller when unauthenticated must fail closed with 401, not 400
        assertThatThrownBy(() -> service.ask("Chính sách phép năm?", null, null))
            .isInstanceOf(AuthenticationRequiredException.class);

        // RV-05 regression: authenticated principal + mismatched/incomplete caller must fail with 403, not 400
        setJwtPrincipal(hrEmployee);
        CallerContext incompleteMismatchedCaller = new CallerContext(HR_USER_ID, null, Role.STANDARD_EMPLOYEE, null);
        assertThatThrownBy(() -> service.ask("Chính sách phép năm?", null, incompleteMismatchedCaller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        verifyNoInteractions(vectorRetrievalService, groqChatModel, geminiChatModel);
    }

    @Test
    void askFallsBackAuditDepartmentToFilterDepartmentWhenAdminHasNoOwnDepartment() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext deptlessAdmin = new CallerContext(ADMIN_USER_ID, null, Role.SYSTEM_ADMIN, "10.1.1.2");
        setPreAuthDevPrincipal(deptlessAdmin);
        when(departmentRepository.existsById(HR_DEPT_ID)).thenReturn(true);
        when(vectorRetrievalService.retrieve(eq("Chính sách HR?"), any(RbacVectorScope.class)))
            .thenReturn(List.of());

        ChatAskResponse response = service.ask("Chính sách HR?", HR_DEPT_ID, deptlessAdmin);

        assertThat(response.abstained()).isTrue();
        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getDepartmentId()).isEqualTo(HR_DEPT_ID);
        verify(departmentRepository).getReferenceById(HR_DEPT_ID);
    }

    @Test
    void ragServicesDoNotDeclareTransactionalAndDeclarePreAuthorize() throws NoSuchMethodException {
        for (Class<?> serviceClass : List.of(RagChatService.class, VectorRetrievalService.class)) {
            assertThat(serviceClass.isAnnotationPresent(Transactional.class)).isFalse();
            assertThat(serviceClass.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
            for (Method method : serviceClass.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(Transactional.class))
                    .as("%s.%s must not declare @Transactional", serviceClass.getSimpleName(), method.getName())
                    .isFalse();
                assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class))
                    .as("%s.%s must not declare @jakarta.transaction.Transactional", serviceClass.getSimpleName(), method.getName())
                    .isFalse();
            }
        }
        for (Method method : List.of(
            RagChatService.class.getMethod("ask", String.class, CallerContext.class),
            RagChatService.class.getMethod("ask", String.class, UUID.class, CallerContext.class),
            RagChatService.class.getMethod("ask", String.class, UUID.class, UUID.class, CallerContext.class)
        )) {
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value())
                .contains("SYSTEM_ADMIN")
                .contains("DEPARTMENT_MANAGER")
                .contains("STANDARD_EMPLOYEE");
        }
    }

    @Test
    void askNormalizesSecondaryLlmRefusalWithoutHidingActualTokenUsage() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        setJwtPrincipal(caller);
        RetrievedChunk chunk = sampleChunk(HR_DEPT_ID, AccessLevel.DEPARTMENT, "handbook.pdf", 1, "Nội dung quy định chung.");
        when(vectorRetrievalService.retrieve(eq("Mức thưởng Tết cụ thể là bao nhiêu?"), any(RbacVectorScope.class)))
            .thenReturn(List.of(chunk));

        List<String> refusalVariants = List.of(
            "Không tìm thấy thông tin trong tài liệu nội bộ.",
            "Không tìm thấy thông tin trong tài liệu nội bộ?",
            "Không tìm thấy thông tin trong tài liệu nội bộ...",
            "Không tìm thấy thông tin trong tài liệu nội bộ。",
            "\"Không tìm thấy thông tin trong tài liệu nội bộ.\"",
            "Không tìm thấy thông tin trong tài liệu nội bộ (theo ngữ cảnh hiện có)."
        );

        for (String refusalText : refusalVariants) {
            clearInvocations(auditLogRepository);
            when(groqChatModel.call(any(Prompt.class))).thenReturn(chatResponse(refusalText, 90, 12));

            ChatAskResponse response = service.ask("Mức thưởng Tết cụ thể là bao nhiêu?", caller);

            assertThat(response.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
            assertThat(response.abstained()).isTrue();
            assertThat(response.citations()).isEmpty();
            assertThat(response.modelProvider()).isEqualTo(RagConstants.PROVIDER_GROQ);
            assertThat(response.promptTokens()).isEqualTo(90);
            assertThat(response.completionTokens()).isEqualTo(12);

            ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
            verify(auditLogRepository).save(auditCaptor.capture());
            assertThat(auditCaptor.getValue().isAbstained()).isTrue();
            assertThat(auditCaptor.getValue().getPromptTokens()).isEqualTo(90);
            assertThat(auditCaptor.getValue().getCompletionTokens()).isEqualTo(12);
        }

        clearInvocations(auditLogRepository);
        String longGroundedResponse = RagConstants.SAFE_REFUSAL_MESSAGE + " về mục A, "
            + "nhưng mục B quy định nhân viên được hưởng đầy đủ phụ cấp ăn trưa và gửi xe hằng tháng [Nguồn: handbook.pdf - Trang 1].";
        when(groqChatModel.call(any(Prompt.class))).thenReturn(chatResponse(longGroundedResponse, 95, 30));
        ChatAskResponse nonRefusalResponse = service.ask("Mức thưởng Tết cụ thể là bao nhiêu?", caller);
        assertThat(nonRefusalResponse.abstained()).isFalse();
        assertThat(nonRefusalResponse.citations()).containsExactly(chunk.citation());
    }

    @Test
    void askFallsBackToGeminiWhenGroqFailsAndGeminiConfigured() {
        RagChatService service = createService("AIza-configured-key", ragProperties);
        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        setJwtPrincipal(caller);
        RetrievedChunk chunk = sampleChunk(HR_DEPT_ID, AccessLevel.DEPARTMENT, "handbook.pdf", 2, "Nghỉ phép 12 ngày.");
        when(vectorRetrievalService.retrieve(eq("Số ngày phép?"), any(RbacVectorScope.class))).thenReturn(List.of(chunk));
        when(groqChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Groq 503"));
        when(geminiChatModel.call(any(Prompt.class)))
            .thenReturn(chatResponse("12 ngày phép [Nguồn: handbook.pdf - Trang 2].", 110, 24));

        ChatAskResponse response = service.ask("Số ngày phép?", caller);

        assertThat(response.modelProvider()).isEqualTo(RagConstants.PROVIDER_GEMINI);
        assertThat(response.promptTokens()).isEqualTo(110);
        assertThat(response.completionTokens()).isEqualTo(24);
        assertThat(response.citations()).hasSize(1);
    }

    @Test
    void askRecords502AuditAndThrowsLlmProviderExceptionWhenAllProvidersFail() {
        RagChatService serviceWithFallback = createService("AIza-configured-key", ragProperties);
        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        setJwtPrincipal(caller);
        RetrievedChunk chunk = sampleChunk(HR_DEPT_ID, AccessLevel.DEPARTMENT, "handbook.pdf", 2, "Nghỉ phép 12 ngày.");
        when(vectorRetrievalService.retrieve(eq("Số ngày phép?"), any(RbacVectorScope.class))).thenReturn(List.of(chunk));
        when(groqChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Groq timeout"));
        when(geminiChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Gemini 429"));

        assertThatThrownBy(() -> serviceWithFallback.ask("Số ngày phép?", caller))
            .isInstanceOf(LlmProviderException.class)
            .extracting(ex -> ((LlmProviderException) ex).provider())
            .isEqualTo(RagConstants.PROVIDER_GROQ_GEMINI_FAILOVER);

        ArgumentCaptor<AuditLogEntity> failoverAuditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(failoverAuditCaptor.capture());
        assertThat(failoverAuditCaptor.getValue().getStatusCode()).isEqualTo(502);
        assertThat(failoverAuditCaptor.getValue().getProvider()).isEqualTo(RagConstants.PROVIDER_GROQ_GEMINI_FAILOVER);
        assertThat(failoverAuditCaptor.getValue().getActionType()).isEqualTo(AuditActionType.CHAT_QUERY);

        clearInvocations(auditLogRepository);
        RagChatService servicePrimaryOnly = createService("unconfigured-api-key", ragProperties);
        assertThatThrownBy(() -> servicePrimaryOnly.ask("Số ngày phép?", caller))
            .isInstanceOf(LlmProviderException.class)
            .extracting(ex -> ((LlmProviderException) ex).provider())
            .isEqualTo(RagConstants.PROVIDER_GROQ);

        ArgumentCaptor<AuditLogEntity> primaryAuditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(primaryAuditCaptor.capture());
        assertThat(primaryAuditCaptor.getValue().getStatusCode()).isEqualTo(502);
        assertThat(primaryAuditCaptor.getValue().getProvider()).isEqualTo(RagConstants.PROVIDER_GROQ);
    }

    @Test
    void askRecords502AuditAndThrowsLlmProviderExceptionWhenEmbeddingProviderFails() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.10.0.9");
        setJwtPrincipal(caller);
        when(vectorRetrievalService.retrieve(eq("Số ngày phép?"), any(RbacVectorScope.class)))
            .thenThrow(new org.springframework.ai.retry.TransientAiException("Ollama connection refused"));

        assertThatThrownBy(() -> service.ask("Số ngày phép?", caller))
            .isInstanceOf(LlmProviderException.class)
            .extracting(ex -> ((LlmProviderException) ex).provider())
            .isEqualTo("ollama");

        verifyNoInteractions(groqChatModel, geminiChatModel);

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLogEntity savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getActionType()).isEqualTo(AuditActionType.CHAT_QUERY);
        assertThat(savedAudit.getStatusCode()).isEqualTo(502);
        assertThat(savedAudit.getProvider()).isEqualTo("ollama");
        assertThat(savedAudit.isAbstained()).isTrue();
        assertThat(savedAudit.getPromptTokens()).isZero();
        assertThat(savedAudit.getCompletionTokens()).isZero();
        assertThat(savedAudit.getIpAddress()).isEqualTo("10.10.0.9");
    }

    @Test
    void askRejectsOversizedQuestionInactiveUserAndSpoofedRole() {
        RagChatService service = createService("unconfigured-api-key", ragProperties);
        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        setJwtPrincipal(caller);

        String maxBoundary = "a".repeat(4000);
        when(vectorRetrievalService.retrieve(eq(maxBoundary), any(RbacVectorScope.class))).thenReturn(List.of());
        assertThat(service.ask(maxBoundary, caller).abstained()).isTrue();

        String oversized = "a".repeat(4001);
        assertThatThrownBy(() -> service.ask(oversized, caller))
            .isInstanceOf(InvalidChatRequestException.class);

        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR");
        UserEntity disabledUser = user(HR_USER_ID, hrDept, Role.STANDARD_EMPLOYEE, false);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(disabledUser));
        assertThatThrownBy(() -> service.ask("Hỏi thử?", caller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        UserEntity activeEmployee = user(HR_USER_ID, hrDept, Role.STANDARD_EMPLOYEE, true);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(activeEmployee));
        CallerContext spoofedAdmin = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.SYSTEM_ADMIN, null);
        assertThatThrownBy(() -> service.ask("Hỏi thử?", spoofedAdmin))
            .isInstanceOf(DocumentAccessDeniedException.class);

        CallerContext wrongDeptCaller = new CallerContext(HR_USER_ID, IT_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        assertThatThrownBy(() -> service.ask("Hỏi thử?", wrongDeptCaller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        CallerContext devDefaultCaller = new CallerContext(
            DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID,
            IT_DEPT_ID,
            Role.DEPARTMENT_MANAGER,
            null
        );
        setPreAuthDevPrincipal(devDefaultCaller);
        when(userRepository.findById(DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID)).thenReturn(Optional.empty());
        when(vectorRetrievalService.retrieve(eq("Hỏi thử?"), any(RbacVectorScope.class))).thenReturn(List.of());
        assertThat(service.ask("Hỏi thử?", devDefaultCaller).abstained()).isTrue();
    }

    @Test
    void askMultiTurnSkipsRewriteOnTurn1ContextualizesTurn2FiltersAbstainedHistorySumsTokensAndPreventsOrphanOn502() {
        ChatSessionService sessionService = mock(ChatSessionService.class);
        QueryContextualizerService contextualizerService = mock(QueryContextualizerService.class);
        RagChatService service = createMultiTurnService("unconfigured-api-key", ragProperties, sessionService, contextualizerService);

        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.5");
        setJwtPrincipal(caller);

        UUID sessionId = UUID.randomUUID();
        UUID turn1MsgId = UUID.randomUUID();
        RetrievedChunk chunk = sampleChunk(HR_DEPT_ID, AccessLevel.DEPARTMENT, "handbook.pdf", 3, "12 ngày phép năm, cộng 1 ngày mỗi 5 năm.");

        // 1. Turn 1 with sessionId == null -> skips contextualizer, auto-creates session via recordCompletedTurn
        when(vectorRetrievalService.retrieve(eq("Nghỉ phép năm bao nhiêu ngày?"), any(RbacVectorScope.class)))
            .thenReturn(List.of(chunk));
        when(groqChatModel.call(any(Prompt.class)))
            .thenReturn(chatResponse("12 ngày phép năm [Nguồn: handbook.pdf - Trang 3].", 100, 25));
        when(sessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(sessionId, turn1MsgId));

        ChatAskResponse turn1 = service.ask("Nghỉ phép năm bao nhiêu ngày?", null, null, caller);
        assertThat(turn1.sessionId()).isEqualTo(sessionId);
        assertThat(turn1.messageId()).isEqualTo(turn1MsgId);
        assertThat(turn1.rewrittenQuery()).isNull();
        verifyNoInteractions(contextualizerService);

        // 2. Turn 2 with existing sessionId (includes 1 grounded turn + 1 abstained turn) -> contextualizes,
        //    excludes abstained turn from Answer LLM Prompt, sums Rewrite + Answer tokens!
        ChatMessageEntity u1 = chatMsg(MessageRole.USER, "Nghỉ phép năm bao nhiêu ngày?", false);
        ChatMessageEntity a1 = chatMsg(MessageRole.ASSISTANT, "12 ngày phép năm.", false);
        ChatMessageEntity u2Oos = chatMsg(MessageRole.USER, "Có trợ cấp thú cưng không?", false);
        ChatMessageEntity a2Oos = chatMsg(MessageRole.ASSISTANT, RagConstants.SAFE_REFUSAL_MESSAGE, true);
        List<ChatMessageEntity> recentHistory = List.of(u1, a1, u2Oos, a2Oos);

        when(sessionService.loadAuthorizedHistoryForTurn(eq(sessionId), eq(HR_USER_ID), any(RbacVectorScope.class)))
            .thenReturn(recentHistory);
        String standalone = "Nhân viên chính thức làm đủ 5 năm được cộng thêm mấy ngày nghỉ phép năm?";
        when(contextualizerService.contextualize("Làm đủ 5 năm thì sao?", recentHistory))
            .thenReturn(new QueryContextualizerService.ContextualizedQuery(standalone, standalone, 60, 15, RagConstants.PROVIDER_GROQ));
        when(vectorRetrievalService.retrieve(eq(standalone), any(RbacVectorScope.class)))
            .thenReturn(List.of(chunk));
        when(groqChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            // System + (u1, a1) + User = 4 messages (abstained pair u2Oos/a2Oos excluded!)
            List<Message> instructions = prompt.getInstructions();
            assertThat(instructions).hasSize(4);
            assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
            assertThat(instructions.get(1).getText()).isEqualTo("Nghỉ phép năm bao nhiêu ngày?");
            assertThat(instructions.get(2).getText()).isEqualTo("12 ngày phép năm.");
            assertThat(instructions.get(3).getText()).contains("Làm đủ 5 năm thì sao?");
            return chatResponse("Cộng thêm 1 ngày phép [Nguồn: handbook.pdf - Trang 3].", 140, 30);
        });
        UUID turn2MsgId = UUID.randomUUID();
        when(sessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(sessionId, turn2MsgId));

        ChatAskResponse turn2 = service.ask("Làm đủ 5 năm thì sao?", sessionId, null, caller);
        assertThat(turn2.sessionId()).isEqualTo(sessionId);
        assertThat(turn2.messageId()).isEqualTo(turn2MsgId);
        assertThat(turn2.rewrittenQuery()).isEqualTo(standalone);
        assertThat(turn2.promptTokens()).isEqualTo(200); // 60 rewrite + 140 answer
        assertThat(turn2.completionTokens()).isEqualTo(45); // 15 rewrite + 30 answer

        // 3. Turn 3 retrieval returns empty -> Answer LLM skipped, rewrite tokens STILL preserved in response & DB
        String oosStandalone = "Mức thưởng cổ phiếu cho nhân viên thử việc?";
        when(contextualizerService.contextualize("Còn cổ phiếu thì sao?", recentHistory))
            .thenReturn(new QueryContextualizerService.ContextualizedQuery(oosStandalone, oosStandalone, 55, 12, RagConstants.PROVIDER_GROQ));
        when(vectorRetrievalService.retrieve(eq(oosStandalone), any(RbacVectorScope.class)))
            .thenReturn(List.of());
        clearInvocations(groqChatModel, sessionService);
        UUID turn3MsgId = UUID.randomUUID();
        when(sessionService.loadAuthorizedHistoryForTurn(eq(sessionId), eq(HR_USER_ID), any(RbacVectorScope.class)))
            .thenReturn(recentHistory);
        when(sessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(sessionId, turn3MsgId));

        ChatAskResponse turn3Abstained = service.ask("Còn cổ phiếu thì sao?", sessionId, null, caller);
        assertThat(turn3Abstained.abstained()).isTrue();
        assertThat(turn3Abstained.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
        assertThat(turn3Abstained.rewrittenQuery()).isEqualTo(oosStandalone);
        assertThat(turn3Abstained.promptTokens()).isEqualTo(55);
        assertThat(turn3Abstained.completionTokens()).isEqualTo(12);
        verifyNoInteractions(groqChatModel);

        // 4. Turn 4 Answer LLM fails 502 -> recordCompletedTurn NOT called (zero orphan USER message),
        //    delegates failure audit with rewrite tokens to ChatSessionService
        clearInvocations(sessionService, auditLogRepository);
        when(sessionService.loadAuthorizedHistoryForTurn(eq(sessionId), eq(HR_USER_ID), any(RbacVectorScope.class)))
            .thenReturn(recentHistory);
        when(vectorRetrievalService.retrieve(eq(standalone), any(RbacVectorScope.class)))
            .thenReturn(List.of(chunk));
        when(groqChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Groq 503"));

        assertThatThrownBy(() -> service.ask("Làm đủ 5 năm thì sao?", sessionId, null, caller))
            .isInstanceOf(LlmProviderException.class);
        verify(sessionService, never()).recordCompletedTurn(any());
        verify(sessionService).recordFailureAudit(
            any(UserEntity.class),
            eq(HR_DEPT_ID),
            eq(sessionId),
            eq("Làm đủ 5 năm thì sao?"),
            eq(60),
            eq(15),
            any(Long.class),
            eq(RagConstants.PROVIDER_GROQ),
            eq(502),
            eq("10.0.0.5")
        );

        // 5. Unowned sessionId -> fails immediately with 404 before contextualizer/retrieval/LLM
        clearInvocations(contextualizerService, vectorRetrievalService, groqChatModel, geminiChatModel);
        UUID unownedSessionId = UUID.randomUUID();
        when(sessionService.loadAuthorizedHistoryForTurn(eq(unownedSessionId), eq(HR_USER_ID), any(RbacVectorScope.class)))
            .thenThrow(new ResourceNotFoundException("Chat session not found: " + unownedSessionId));
        assertThatThrownBy(() -> service.ask("Hỏi phiên người khác?", unownedSessionId, null, caller))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(contextualizerService, vectorRetrievalService, groqChatModel, geminiChatModel);

        // 6. Odd historyWindowSize (leading AssistantMessage) drops orphan leading AssistantMessage so Gemini/Groq prompt starts with USER
        RagProperties oddWindowProps = new RagProperties(
            RagProperties.RetrievalProperties.defaults(),
            RagProperties.LlmProperties.defaults(),
            new RagProperties.MemoryProperties(5, 1000, 80)
        );
        RagChatService oddWindowService = createMultiTurnService("AIza-configured-key", oddWindowProps, sessionService, contextualizerService);
        ChatMessageEntity orphanLeadingAssistant = chatMsg(MessageRole.ASSISTANT, "Trả lời cũ bị cắt nửa cặp", false);
        List<ChatMessageEntity> oddHistory = List.of(orphanLeadingAssistant, u1, a1);
        when(sessionService.loadAuthorizedHistoryForTurn(eq(sessionId), eq(HR_USER_ID), any(RbacVectorScope.class)))
            .thenReturn(oddHistory);
        when(contextualizerService.contextualize("Còn tiếp?", oddHistory))
            .thenReturn(new QueryContextualizerService.ContextualizedQuery("Còn tiếp theo quy định nào?", "Còn tiếp theo quy định nào?", 30, 10, RagConstants.PROVIDER_GROQ));
        when(vectorRetrievalService.retrieve(eq("Còn tiếp theo quy định nào?"), any(RbacVectorScope.class)))
            .thenReturn(List.of(chunk));
        clearInvocations(groqChatModel, geminiChatModel);
        when(groqChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Groq 503"));
        when(geminiChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            List<Message> instructions = prompt.getInstructions();
            assertThat(instructions).hasSize(4); // System + u1 + a1 + current User (orphanLeadingAssistant dropped!)
            assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
            assertThat(instructions.get(1)).isInstanceOf(UserMessage.class);
            assertThat(instructions.get(1).getText()).isEqualTo("Nghỉ phép năm bao nhiêu ngày?");
            assertThat(instructions.get(2)).isInstanceOf(AssistantMessage.class);
            assertThat(instructions.get(3)).isInstanceOf(UserMessage.class);
            return chatResponse("Tiếp theo quy định [Nguồn: handbook.pdf - Trang 3].", 110, 20);
        });

        ChatAskResponse oddResponse = oddWindowService.ask("Còn tiếp?", sessionId, null, caller);
        assertThat(oddResponse.modelProvider()).isEqualTo(RagConstants.PROVIDER_GEMINI);
    }

    @Test
    void askShortCircuitsOnL1AndL2CacheHitsAndPublishesGroundedMissesWithCapturedVersion() {
        ChatSessionService sessionService = mock(ChatSessionService.class);
        QueryContextualizerService contextualizerService = mock(QueryContextualizerService.class);
        RagCacheService ragCacheService = mock(RagCacheService.class);
        RagChatService service = createServiceWithCache(
            "unconfigured-api-key",
            ragProperties,
            sessionService,
            contextualizerService,
            ragCacheService
        );

        CallerContext caller = new CallerContext(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.5");
        setJwtPrincipal(caller);

        RetrievedChunk chunk = sampleChunk(HR_DEPT_ID, AccessLevel.DEPARTMENT, "handbook.pdf", 3, "12 ngày phép năm.");
        UUID sessionId = UUID.randomUUID();
        UUID l1MsgId = UUID.randomUUID();
        UUID l2MsgId = UUID.randomUUID();
        UUID missMsgId = UUID.randomUUID();

        // 1. L1 cache hit -> 0 retrieval/LLM calls, 0 tokens, CacheTier.L1 persisted
        when(ragCacheService.lookup(any(RbacVectorScope.class), eq("Nghỉ phép năm bao nhiêu ngày?"), eq(List.of()), any()))
            .thenReturn(RagCacheService.CacheLookupResult.hit(
                CacheTier.L1,
                4L,
                "12 ngày phép năm [Nguồn: handbook.pdf - Trang 3].",
                null,
                List.of(chunk.citation()),
                RagConstants.PROVIDER_GROQ,
                null,
                500_000L,
                0L,
                0L
            ));
        when(sessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(sessionId, l1MsgId));

        ChatAskResponse l1Response = service.ask("Nghỉ phép năm bao nhiêu ngày?", null, null, caller);

        assertThat(l1Response.cacheHit()).isTrue();
        assertThat(l1Response.cacheTier()).isEqualTo(CacheTier.L1);
        assertThat(l1Response.promptTokens()).isZero();
        assertThat(l1Response.completionTokens()).isZero();
        assertThat(l1Response.messageId()).isEqualTo(l1MsgId);
        assertThat(l1Response.citations()).containsExactly(chunk.citation());
        verifyNoInteractions(contextualizerService, vectorRetrievalService, groqChatModel, geminiChatModel);
        verify(ragCacheService, never()).publishIfCurrent(any(), any(Long.class), any(), any(), any(), any(), any(), any(), any(Boolean.class), any());

        ArgumentCaptor<ChatSessionService.CompletedTurnCommand> l1CmdCaptor =
            ArgumentCaptor.forClass(ChatSessionService.CompletedTurnCommand.class);
        verify(sessionService).recordCompletedTurn(l1CmdCaptor.capture());
        assertThat(l1CmdCaptor.getValue().cacheTier()).isEqualTo(CacheTier.L1);
        assertThat(l1CmdCaptor.getValue().promptTokens()).isZero();
        assertThat(l1CmdCaptor.getValue().completionTokens()).isZero();

        // 2. L2 semantic hit -> 0 retrieval/LLM calls, 0 tokens, CacheTier.L2 persisted
        clearInvocations(sessionService);
        float[] queryVector = new float[]{1.0f, 0.0f};
        when(ragCacheService.lookup(any(RbacVectorScope.class), eq("Phép năm được mấy ngày?"), eq(List.of()), any()))
            .thenReturn(RagCacheService.CacheLookupResult.hit(
                CacheTier.L2,
                4L,
                "12 ngày phép năm [Nguồn: handbook.pdf - Trang 3].",
                null,
                List.of(chunk.citation()),
                RagConstants.PROVIDER_GROQ,
                queryVector,
                400_000L,
                3_000_000L,
                5_000_000L
            ));
        when(sessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(sessionId, l2MsgId));

        ChatAskResponse l2Response = service.ask("Phép năm được mấy ngày?", null, null, caller);
        assertThat(l2Response.cacheHit()).isTrue();
        assertThat(l2Response.cacheTier()).isEqualTo(CacheTier.L2);
        assertThat(l2Response.messageId()).isEqualTo(l2MsgId);
        assertThat(l2Response.promptTokens()).isZero();
        verifyNoInteractions(vectorRetrievalService, groqChatModel, geminiChatModel);

        // 3. Grounded MISS -> executes retrieval + LLM, persists CacheTier.NONE, publishes with version captured before retrieval
        clearInvocations(sessionService, ragCacheService);
        when(ragCacheService.lookup(any(RbacVectorScope.class), eq("Chính sách thử việc?"), eq(List.of()), any()))
            .thenReturn(RagCacheService.CacheLookupResult.miss(5L, queryVector, 500_000L, 2_000_000L, 4_000_000L));
        when(ragCacheService.readAuthoritativeVersion(any(RbacVectorScope.class))).thenReturn(5L);
        when(vectorRetrievalService.retrieve(eq("Chính sách thử việc?"), any(RbacVectorScope.class)))
            .thenReturn(List.of(chunk));
        when(groqChatModel.call(any(Prompt.class)))
            .thenReturn(chatResponse("Thời gian thử việc là 60 ngày [Nguồn: handbook.pdf - Trang 3].", 120, 30));
        when(sessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(sessionId, missMsgId));

        ChatAskResponse missResponse = service.ask("Chính sách thử việc?", null, null, caller);
        assertThat(missResponse.cacheHit()).isFalse();
        assertThat(missResponse.cacheTier()).isEqualTo(CacheTier.NONE);
        assertThat(missResponse.promptTokens()).isEqualTo(120);
        verify(ragCacheService).publishIfCurrent(
            any(RbacVectorScope.class),
            eq(5L),
            eq("Chính sách thử việc?"),
            eq(null),
            eq(List.of()),
            eq(queryVector),
            eq("Thời gian thử việc là 60 ngày [Nguồn: handbook.pdf - Trang 3]."),
            eq(List.of(chunk.citation())),
            eq(false),
            eq(RagConstants.PROVIDER_GROQ)
        );

        // 4. Abstained MISS -> never publishes to cache
        clearInvocations(ragCacheService);
        when(ragCacheService.lookup(any(RbacVectorScope.class), eq("Giá vàng hôm nay?"), eq(List.of()), any()))
            .thenReturn(RagCacheService.CacheLookupResult.miss(5L, queryVector, 500_000L, 2_000_000L, 4_000_000L));
        when(vectorRetrievalService.retrieve(eq("Giá vàng hôm nay?"), any(RbacVectorScope.class)))
            .thenReturn(List.of());
        when(sessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(sessionId, UUID.randomUUID()));

        ChatAskResponse abstainedResponse = service.ask("Giá vàng hôm nay?", null, null, caller);
        assertThat(abstainedResponse.abstained()).isTrue();
        assertThat(abstainedResponse.cacheHit()).isFalse();
        verify(ragCacheService, never()).publishIfCurrent(any(), any(Long.class), any(), any(), any(), any(), any(), any(), any(Boolean.class), any());
    }

    private RagChatService createServiceWithCache(
        String geminiApiKey,
        RagProperties props,
        ChatSessionService chatSessionService,
        QueryContextualizerService queryContextualizerService,
        RagCacheService ragCacheService
    ) {
        AiProperties aiProperties = new AiProperties(new AiProperties.GeminiProperties(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "/chat/completions",
            geminiApiKey,
            "gemini-3.5-flash",
            0.1d
        ));
        return new RagChatService(
            vectorRetrievalService,
            groundedSystemPromptService,
            rbacVectorFilterFactory,
            groqChatClient,
            providerOf(geminiChatClient),
            providerOf(userRepository),
            providerOf(departmentRepository),
            providerOf(auditLogRepository),
            providerOf(transactionTemplate),
            providerOf(chatSessionService),
            providerOf(queryContextualizerService),
            providerOf(ragCacheService),
            aiProperties,
            props,
            environment
        );
    }

    private RagChatService createMultiTurnService(
        String geminiApiKey,
        RagProperties props,
        ChatSessionService chatSessionService,
        QueryContextualizerService queryContextualizerService
    ) {
        AiProperties aiProperties = new AiProperties(new AiProperties.GeminiProperties(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "/chat/completions",
            geminiApiKey,
            "gemini-3.5-flash",
            0.1d
        ));
        return new RagChatService(
            vectorRetrievalService,
            groundedSystemPromptService,
            rbacVectorFilterFactory,
            groqChatClient,
            providerOf(geminiChatClient),
            providerOf(userRepository),
            providerOf(departmentRepository),
            providerOf(auditLogRepository),
            providerOf(transactionTemplate),
            providerOf(chatSessionService),
            providerOf(queryContextualizerService),
            aiProperties,
            props,
            environment
        );
    }

    private static ChatMessageEntity chatMsg(MessageRole role, String content, boolean abstained) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setId(UUID.randomUUID());
        entity.setRole(role);
        entity.setContent(content);
        entity.setAbstained(abstained);
        return entity;
    }

    private RagChatService createService(String geminiApiKey, RagProperties props) {
        AiProperties aiProperties = new AiProperties(new AiProperties.GeminiProperties(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "/chat/completions",
            geminiApiKey,
            "gemini-3.5-flash",
            0.1d
        ));
        return new RagChatService(
            vectorRetrievalService,
            groundedSystemPromptService,
            rbacVectorFilterFactory,
            groqChatClient,
            providerOf(geminiChatClient),
            providerOf(userRepository),
            providerOf(departmentRepository),
            providerOf(auditLogRepository),
            providerOf(transactionTemplate),
            providerOf(null),
            providerOf(null),
            aiProperties,
            props,
            environment
        );
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

    private static void setPreAuthDevPrincipal(CallerContext caller) {
        SecurityContextHolder.getContext().setAuthentication(
            new PreAuthenticatedAuthenticationToken(
                caller,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + caller.role().name()))
            )
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static RetrievedChunk sampleChunk(
        UUID departmentId,
        AccessLevel accessLevel,
        String fileName,
        int pageNumber,
        String content
    ) {
        UUID docId = UUID.randomUUID();
        CitationDto citation = new CitationDto(
            docId, fileName, pageNumber, null, 0.90d, content,
            "http://localhost:9000/documind-documents/" + fileName + "#page=" + pageNumber
        );
        return new RetrievedChunk(UUID.randomUUID().toString(), docId, departmentId, accessLevel, 0, content, citation);
    }

    private static ChatResponse chatResponse(String text, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
            .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
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

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final Object TX_RESOURCE_KEY = new Object();

        @Override
        protected Object doGetTransaction() {
            return TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY);
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return transaction != null;
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
