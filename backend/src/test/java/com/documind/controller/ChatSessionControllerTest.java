package com.documind.controller;

import com.documind.controller.advice.GlobalExceptionHandler;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.chat.ChatMessageResponse;
import com.documind.controller.dto.chat.ChatSessionResponse;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.service.chat.ChatSessionService;
import com.documind.service.document.CallerContext;
import com.documind.service.document.CallerContextResolver;
import com.documind.service.document.ProdCallerContextResolver;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatSessionControllerTest {

    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-3333-4444-555555555553");

    private ChatSessionService chatSessionService;
    private CallerContextResolver callerContextResolver;
    private ObjectMapper objectMapper;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatSessionService = mock(ChatSessionService.class);
        callerContextResolver = mock(CallerContextResolver.class);
        objectMapper = Jackson2ObjectMapperBuilder.json()
            .mixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
            .standaloneSetup(new ChatSessionController(chatSessionService, callerContextResolver))
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
    void createSessionSupportsOptionalBodyAndReturns201() throws Exception {
        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        when(callerContextResolver.resolve(any())).thenReturn(caller);

        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.of(2026, 11, 21, 10, 0, 0, 0, ZoneOffset.UTC);
        ChatSessionResponse defaultResponse = new ChatSessionResponse(sessionId, ChatSessionService.DEFAULT_SESSION_TITLE, now, now);
        when(chatSessionService.createSession(null, caller)).thenReturn(defaultResponse);

        mockMvc.perform(post("/api/v1/chat/sessions"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.title").value(ChatSessionService.DEFAULT_SESSION_TITLE));

        ChatSessionResponse titledResponse = new ChatSessionResponse(sessionId, "Nghỉ phép năm 2026", now, now);
        when(chatSessionService.createSession("Nghỉ phép năm 2026", caller)).thenReturn(titledResponse);

        mockMvc.perform(post("/api/v1/chat/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", "Nghỉ phép năm 2026"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("Nghỉ phép năm 2026"));

        mockMvc.perform(post("/api/v1/chat/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", "a".repeat(256)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void listGetMessagesRenameAndDeleteMapResponsesAndErrorsCleanly() throws Exception {
        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        when(callerContextResolver.resolve(any())).thenReturn(caller);

        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.of(2026, 11, 21, 10, 0, 0, 0, ZoneOffset.UTC);
        ChatSessionResponse session = new ChatSessionResponse(sessionId, "Chính sách HR", now, now);

        when(chatSessionService.listSessions(0, 20, caller))
            .thenReturn(new PageResponse<>(List.of(session), 0, 20, 1L, 1));
        mockMvc.perform(get("/api/v1/chat/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(sessionId.toString()))
            .andExpect(jsonPath("$.totalElements").value(1));

        when(chatSessionService.listSessions(-1, 20, caller))
            .thenThrow(new InvalidChatRequestException("Page index must be >= 0"));
        mockMvc.perform(get("/api/v1/chat/sessions").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_CHAT_REQUEST"));

        when(chatSessionService.listSessions(0, 0, caller))
            .thenThrow(new InvalidChatRequestException("Page size must be between 1 and 100"));
        mockMvc.perform(get("/api/v1/chat/sessions").param("size", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_CHAT_REQUEST"));

        when(chatSessionService.listSessions(0, 101, caller))
            .thenThrow(new InvalidChatRequestException("Page size must be between 1 and 100"));
        mockMvc.perform(get("/api/v1/chat/sessions").param("size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_CHAT_REQUEST"));

        when(chatSessionService.getSession(sessionId, caller)).thenReturn(session);
        mockMvc.perform(get("/api/v1/chat/sessions/{id}", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Chính sách HR"));

        UUID msgId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        CitationDto citation = new CitationDto(docId, "hr.pdf", 2, null, 0.91d, "12 ngày phép.", "http://minio/hr.pdf#page=2");
        ChatMessageResponse assistantMsg = new ChatMessageResponse(
            msgId, sessionId, MessageRole.ASSISTANT, "12 ngày phép.", "Nghỉ phép năm nhân viên chính thức?",
            List.of(citation), false, 120, 35, 80L, "groq", now
        );
        when(chatSessionService.listMessages(sessionId, caller)).thenReturn(List.of(assistantMsg));
        mockMvc.perform(get("/api/v1/chat/sessions/{id}/messages", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(msgId.toString()))
            .andExpect(jsonPath("$[0].rewrittenQuery").value("Nghỉ phép năm nhân viên chính thức?"))
            .andExpect(jsonPath("$[0].citations[0].presignedUrl").value("http://minio/hr.pdf#page=2"));

        ChatSessionResponse renamed = new ChatSessionResponse(sessionId, "Tên mới", now, now.plusSeconds(10));
        when(chatSessionService.renameSession(sessionId, "Tên mới", caller)).thenReturn(renamed);
        mockMvc.perform(patch("/api/v1/chat/sessions/{id}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", "Tên mới"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Tên mới"));

        mockMvc.perform(patch("/api/v1/chat/sessions/{id}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/v1/chat/sessions/{id}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", "a".repeat(256)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/v1/chat/sessions/{id}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON_REQUEST"));

        doNothing().when(chatSessionService).deleteSession(sessionId, caller);
        mockMvc.perform(delete("/api/v1/chat/sessions/{id}", sessionId))
            .andExpect(status().isNoContent());
        verify(chatSessionService).deleteSession(sessionId, caller);

        UUID otherSessionId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Chat session not found: " + otherSessionId))
            .when(chatSessionService).deleteSession(otherSessionId, caller);
        mockMvc.perform(delete("/api/v1/chat/sessions/{id}", otherSessionId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

        when(chatSessionService.listSessions(0, 20, caller))
            .thenThrow(new DocumentAccessDeniedException("User account is inactive"));
        mockMvc.perform(get("/api/v1/chat/sessions"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    void sessionEndpointsFailClosedWith401WhenUnauthenticated() throws Exception {
        try (LocalValidatorFactoryBean localValidator = new LocalValidatorFactoryBean()) {
            localValidator.afterPropertiesSet();
            MockMvc failClosedMvc = MockMvcBuilders
                .standaloneSetup(new ChatSessionController(chatSessionService, new ProdCallerContextResolver()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(localValidator)
                .build();

            UUID sessionId = UUID.randomUUID();
            for (var req : List.of(
                post("/api/v1/chat/sessions"),
                get("/api/v1/chat/sessions"),
                get("/api/v1/chat/sessions/{id}", sessionId),
                get("/api/v1/chat/sessions/{id}/messages", sessionId),
                patch("/api/v1/chat/sessions/{id}", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Updated\"}"),
                delete("/api/v1/chat/sessions/{id}", sessionId)
            )) {
                failClosedMvc.perform(req)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"DocuMind AI\""))
                    .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
            }
        }
        verifyNoInteractions(chatSessionService);
    }
}
