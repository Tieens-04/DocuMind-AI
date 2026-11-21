package com.documind.controller;

import com.documind.controller.dto.ChatAskRequest;
import com.documind.controller.dto.ChatAskResponse;
import com.documind.service.document.CallerContext;
import com.documind.service.document.CallerContextResolver;
import com.documind.service.rag.RagChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST API endpoints for synchronous grounded multi-turn RAG question answering.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "Grounded multi-turn RAG Q&A with RBAC retrieval, query contextualization, citations, and zero-hallucination fallback")
public class ChatController {

    private final RagChatService ragChatService;
    private final CallerContextResolver callerContextResolver;

    public ChatController(RagChatService ragChatService, CallerContextResolver callerContextResolver) {
        this.ragChatService = Objects.requireNonNull(ragChatService, "ragChatService must not be null");
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
}
