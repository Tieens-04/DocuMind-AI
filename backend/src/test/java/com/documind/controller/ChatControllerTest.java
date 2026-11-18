package com.documind.controller;

import com.documind.controller.advice.GlobalExceptionHandler;
import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.LlmProviderException;
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

    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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
        when(ragChatService.ask("Chính sách nghỉ phép năm?", caller)).thenReturn(expected);

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

        verify(ragChatService).ask("Chính sách nghỉ phép năm?", caller);
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
        when(ragChatService.ask(minQuestion, caller)).thenReturn(refusal);
        when(ragChatService.ask(maxQuestion, caller)).thenReturn(refusal);

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

        verify(ragChatService).ask(minQuestion, caller);
        verify(ragChatService).ask(maxQuestion, caller);
    }

    @Test
    void askReturns403And400And502AppropriatelyWithoutLeakingSecrets() throws Exception {
        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        when(callerContextResolver.resolve(any())).thenReturn(caller);

        when(ragChatService.ask("blocked", caller))
            .thenThrow(new DocumentAccessDeniedException("User account is inactive"));
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"blocked\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("DOCUMENT_ACCESS_DENIED"));

        when(ragChatService.ask("invalid", caller))
            .thenThrow(new InvalidChatRequestException("Question contains invalid characters"));
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"invalid\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_CHAT_REQUEST"));

        when(ragChatService.ask("outage", caller))
            .thenThrow(new LlmProviderException("Upstream failure sk-secret-token-123", new RuntimeException("503")));
        mockMvc.perform(post("/api/v1/chat/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"outage\"}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorCode").value("LLM_PROVIDER_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail", not(containsString("sk-secret-token-123"))));
    }
}
