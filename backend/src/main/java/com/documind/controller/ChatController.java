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
 * REST API endpoints for synchronous grounded RAG question answering.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "Grounded RAG Q&A with RBAC retrieval, source citations, and zero-hallucination fallback")
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
        description = "Retrieves RBAC-scoped chunks from pgvector using SecurityContextHolder (with optional departmentId filtering exclusively for SYSTEM_ADMIN), verifies chunks with Java Defense-in-Depth, and answers strictly from document context with citations or returns a deterministic zero-token refusal"
    )
    public ResponseEntity<ChatAskResponse> ask(
        @Valid @RequestBody ChatAskRequest requestBody,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        ChatAskResponse response = ragChatService.ask(requestBody.question(), requestBody.departmentId(), caller);
        return ResponseEntity.ok(response);
    }
}
