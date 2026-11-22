package com.documind.controller;

import com.documind.controller.dto.ChatAskRequest;
import com.documind.controller.dto.ChatAskResponse;
import com.documind.service.document.CallerContext;
import com.documind.service.document.CallerContextResolver;
import com.documind.service.rag.RagChatService;
import com.documind.service.rag.RagChatStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * REST API endpoints for synchronous and typed SSE streaming grounded multi-turn RAG question answering.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "Grounded multi-turn RAG Q&A with RBAC retrieval, query contextualization, citations, typed SSE streaming, and zero-hallucination fallback")
public class ChatController {

    private static final MediaType TEXT_EVENT_STREAM_UTF8 = new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8);
    private static final String SSE_CACHE_CONTROL = "no-cache, no-transform";
    private static final String HEADER_X_ACCEL_BUFFERING = "X-Accel-Buffering";

    private final RagChatService ragChatService;
    private final RagChatStreamService ragChatStreamService;
    private final CallerContextResolver callerContextResolver;

    public ChatController(
        RagChatService ragChatService,
        RagChatStreamService ragChatStreamService,
        CallerContextResolver callerContextResolver
    ) {
        this.ragChatService = Objects.requireNonNull(ragChatService, "ragChatService must not be null");
        this.ragChatStreamService = Objects.requireNonNull(ragChatStreamService, "ragChatStreamService must not be null");
        this.callerContextResolver = Objects.requireNonNull(callerContextResolver, "callerContextResolver must not be null");
    }

    @PostMapping("/ask")
    @Operation(
        summary = "Ask grounded RAG question",
        description = "Supports single-turn auto-session creation (skipping query rewrite on Turn 1 for optimal TTFT) and multi-turn follow-up contextualization (last 6 messages), enforces RBAC vector search + Java Defense-in-Depth, and persists the turn atomically"
    )
    public ResponseEntity<ChatAskResponse> ask(
        @Valid @RequestBody ChatAskRequest requestBody,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        ChatAskResponse response = ragChatService.ask(
            requestBody.question(),
            requestBody.sessionId(),
            requestBody.departmentId(),
            caller
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping(
        value = "/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @Operation(
        summary = "Stream grounded RAG answer via Typed SSE",
        description = "Validates auth, input, RBAC, and session ownership synchronously (returning RFC 7807 ProblemDetail for 400/401/403/404), then streams typed SSE events: citations (<500ms after pgvector retrieval), token (JSON-wrapped deltas preserving whitespace/Markdown), done (sessionId, messageId, abstained, modelProvider, cacheHit, cacheTier, tokenUsage, latencyMs), and error"
    )
    public ResponseEntity<SseEmitter> stream(
        @Valid @RequestBody ChatAskRequest requestBody,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        SseEmitter emitter = ragChatStreamService.openStream(
            requestBody.question(),
            requestBody.sessionId(),
            requestBody.departmentId(),
            caller
        );
        return ResponseEntity.ok()
            .contentType(TEXT_EVENT_STREAM_UTF8)
            .header(HttpHeaders.CACHE_CONTROL, SSE_CACHE_CONTROL)
            .header(HEADER_X_ACCEL_BUFFERING, "no")
            .body(emitter);
    }
}
