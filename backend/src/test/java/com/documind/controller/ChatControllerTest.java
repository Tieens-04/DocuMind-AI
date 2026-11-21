package com.documind.controller;

import com.documind.controller.advice.GlobalExceptionHandler;
import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.LlmProviderException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.service.document.CallerContext;
import com.documind.service.document.CallerContextResolver;
import com.documind.service.document.ProdCallerContextResolver;
import com.documind.service.rag.RagChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ADMIN_USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private RagChatService ragChatService;
    private CallerContextResolver callerContextResolver;
    private ObjectMapper objectMapper;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        callerContextResolver = mock(CallerContextResolver.class);
        objectMapper = Jackson2ObjectMapperBuilder.json()
            .mixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
            .standaloneSetup(new ChatController(ragChatService, callerContextResolver))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .setValidator(validator)
            .build();
    }

    @AfterEach
    void tearDown() {
        if (validator != null) {
            validator.close();
        }
    }

    @Test
    void askReturns200WithGroundedAnswerAndCitations() throws Exception {
        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        when(callerContextResolver.resolve(any())).thenReturn(caller);

        UUID docId = UUID.randomUUID();
        CitationDto citation = new CitationDto(
            docId,
            "hr-handbook.pdf",
            3,
            null,
            0.91d,
            "Nhân viên chính thức được nghỉ 12 ngày phép năm.",
            "http://localhost:9000/documind-documents/hr-handbook.pdf?sig=abc#page=3"
        );
        ChatAskResponse expected = new ChatAskResponse(
            "Nhân viên chính thức có 12 ngày phép năm [Nguồn: hr-handbook.pdf - Trang 3].",
            false,
            List.of(citation),
            "groq",
            120,
            30,
            42L
        );
        when(ragChatService.ask("Chính sách nghỉ phép năm?", null, null, caller)).thenReturn(expected);

        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("question", "Chính sách nghỉ phép năm?"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value(expected.answer()))
            .andExpect(jsonPath("$.abstained").value(false))
            .andExpect(jsonPath("$.citations[0].documentId").value(docId.toString()))
            .andExpect(jsonPath("$.citations[0].fileName").value("hr-handbook.pdf"))
            .andExpect(jsonPath("$.citations[0].pageNumber").value(3))
            .andExpect(jsonPath("$.citations[0].presignedUrl").value(citation.presignedUrl()))
            .andExpect(jsonPath("$.modelProvider").value("groq"))
            .andExpect(jsonPath("$.promptTokens").value(120))
            .andExpect(jsonPath("$.completionTokens").value(30))
            .andExpect(jsonPath("$.latencyMs").value(42));

        verify(ragChatService).ask("Chính sách nghỉ phép năm?", null, null, caller);
    }

    @Test
    void askForwardsSessionIdAndReturnsSessionMetadataAndRewrittenQuery() throws Exception {
        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        when(callerContextResolver.resolve(any())).thenReturn(caller);

        UUID sessionId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ChatAskResponse multiTurnResp = new ChatAskResponse(
            sessionId,
            messageId,
            "Nhân viên chính thức làm đủ 5 năm được cộng thêm mấy ngày phép năm?",
            "Được cộng thêm 1 ngày phép [Nguồn: hr-handbook.pdf - Trang 3].",
            false,
            List.of(),
            "groq",
            195,
            52,
            110L
        );
        when(ragChatService.ask("Còn làm đủ 5 năm thì sao?", sessionId, null, caller)).thenReturn(multiTurnResp);

        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "question", "Còn làm đủ 5 năm thì sao?",
                    "sessionId", sessionId.toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
            .andExpect(jsonPath("$.messageId").value(messageId.toString()))
            .andExpect(jsonPath("$.rewrittenQuery").value("Nhân viên chính thức làm đủ 5 năm được cộng thêm mấy ngày phép năm?"))
            .andExpect(jsonPath("$.promptTokens").value(195));

        verify(ragChatService).ask("Còn làm đủ 5 năm thì sao?", sessionId, null, caller);

        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Hỏi tiếp?\",\"sessionId\":\"not-a-uuid\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON_REQUEST"));
    }

    @Test
    void askForwardsOptionalDepartmentIdForSystemAdminAndMaps404And400UuidErrors() throws Exception {
        CallerContext adminCaller = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");
        when(callerContextResolver.resolve(any())).thenReturn(adminCaller);

        ChatAskResponse scopedResponse = new ChatAskResponse(
            "Chính sách HR [Nguồn: hr-handbook.pdf - Trang 1].",
            false,
            List.of(),
            "groq",
            95,
            25,
            31L
        );
        when(ragChatService.ask("Chính sách HR?", null, HR_DEPT_ID, adminCaller)).thenReturn(scopedResponse);

        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "question", "Chính sách HR?",
                    "departmentId", HR_DEPT_ID.toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value(scopedResponse.answer()));
        verify(ragChatService).ask("Chính sách HR?", null, HR_DEPT_ID, adminCaller);

        UUID unknownDeptId = UUID.randomUUID();
        when(ragChatService.ask("Chính sách phòng lạ?", null, unknownDeptId, adminCaller))
            .thenThrow(new ResourceNotFoundException("Department not found: " + unknownDeptId));
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "question", "Chính sách phòng lạ?",
                    "departmentId", unknownDeptId.toString()
                ))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Chính sách?\",\"departmentId\":\"not-a-valid-uuid\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON_REQUEST"));

        CallerContext nonAdminCaller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        when(callerContextResolver.resolve(any())).thenReturn(nonAdminCaller);
        when(ragChatService.ask("Chính sách IT?", null, IT_DEPT_ID, nonAdminCaller))
            .thenThrow(new DocumentAccessDeniedException("Only SYSTEM_ADMIN can filter chat retrieval by departmentId"));
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "question", "Chính sách IT?",
                    "departmentId", IT_DEPT_ID.toString()
                ))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    void askReturns400ValidationErrorWhenQuestionBlankMissingOrTooLong() throws Exception {
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        String oversized = "x".repeat(4001);
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("question", oversized))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(ragChatService);
    }

    @Test
    void askReturns400MalformedJsonWhenPayloadCannotBeParsed() throws Exception {
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON_REQUEST"));

        verifyNoInteractions(ragChatService);
    }

    @Test
    void askReturns401WhenCallerIsUnauthenticatedFailClosed() throws Exception {
        try (LocalValidatorFactoryBean localValidator = new LocalValidatorFactoryBean()) {
            localValidator.afterPropertiesSet();
            MockMvc failClosedMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(ragChatService, new ProdCallerContextResolver()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(localValidator)
                .build();

            failClosedMvc.perform(post("/api/v1/chat/ask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"Chính sách công ty?\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"DocuMind AI\""))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        }

        verifyNoInteractions(ragChatService);
    }

    @Test
    void askAcceptsBoundaryLengths1And4000Characters() throws Exception {
        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        when(callerContextResolver.resolve(any())).thenReturn(caller);

        ChatAskResponse refusal = new ChatAskResponse(
            "Không tìm thấy thông tin trong tài liệu nội bộ",
            true,
            List.of(),
            null,
            0,
            0,
            5L
        );
        String minQuestion = "?";
        String maxQuestion = "a".repeat(4000);
        when(ragChatService.ask(minQuestion, null, null, caller)).thenReturn(refusal);
        when(ragChatService.ask(maxQuestion, null, null, caller)).thenReturn(refusal);

        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("question", minQuestion))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.abstained").value(true));

        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("question", maxQuestion))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.abstained").value(true));

        verify(ragChatService).ask(minQuestion, null, null, caller);
        verify(ragChatService).ask(maxQuestion, null, null, caller);
    }

    @Test
    void askReturns403And400And502AppropriatelyWithoutLeakingSecrets() throws Exception {
        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        when(callerContextResolver.resolve(any())).thenReturn(caller);

        when(ragChatService.ask("blocked", null, null, caller))
            .thenThrow(new DocumentAccessDeniedException("User account is inactive"));
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"blocked\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("DOCUMENT_ACCESS_DENIED"));

        when(ragChatService.ask("invalid", null, null, caller))
            .thenThrow(new InvalidChatRequestException("Question contains invalid characters"));
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"invalid\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_CHAT_REQUEST"));

        when(ragChatService.ask("outage", null, null, caller))
            .thenThrow(new LlmProviderException("Upstream failure sk-secret-token-123", new RuntimeException("503")));
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"outage\"}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorCode").value("LLM_PROVIDER_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail", not(containsString("sk-secret-token-123"))));
    }
}
