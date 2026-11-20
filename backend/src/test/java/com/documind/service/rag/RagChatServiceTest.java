package com.documind.service.rag;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
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
import com.documind.service.document.CallerContext;
import com.documind.service.document.DevFallbackCallerContextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
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
            RagChatService.class.getMethod("ask", String.class, UUID.class, CallerContext.class)
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
