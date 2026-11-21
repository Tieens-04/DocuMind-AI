package com.documind.controller;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.chat.ChatMessageResponse;
import com.documind.controller.dto.chat.ChatSessionResponse;
import com.documind.controller.dto.chat.CreateChatSessionRequest;
import com.documind.controller.dto.chat.RenameChatSessionRequest;
import com.documind.service.chat.ChatSessionService;
import com.documind.service.document.CallerContext;
import com.documind.service.document.CallerContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST API endpoints for strict owner-only chat session management and conversation message history.
 */
@RestController
@RequestMapping("/api/v1/chat/sessions")
@Tag(name = "Chat Sessions", description = "Owner-scoped multi-turn chat session lifecycle and RBAC-verified message history")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final CallerContextResolver callerContextResolver;

    public ChatSessionController(
        ChatSessionService chatSessionService,
        CallerContextResolver callerContextResolver
    ) {
        this.chatSessionService = Objects.requireNonNull(chatSessionService, "chatSessionService must not be null");
        this.callerContextResolver = Objects.requireNonNull(callerContextResolver, "callerContextResolver must not be null");
    }

    @PostMapping
    @Operation(
        summary = "Create chat session",
        description = "Creates a new chat session owned by the authenticated user. When title is omitted or blank, defaults to 'Cuộc hội thoại mới' and auto-titles from the first question."
    )
    public ResponseEntity<ChatSessionResponse> createSession(
        @Valid @RequestBody(required = false) CreateChatSessionRequest body,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        String rawTitle = body != null ? body.title() : null;
        ChatSessionResponse created = chatSessionService.createSession(rawTitle, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Operation(
        summary = "List user's chat sessions",
        description = "Returns paginated chat sessions owned by the authenticated user sorted by last activity descending"
    )
    public PageResponse<ChatSessionResponse> listSessions(
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return chatSessionService.listSessions(page, size, caller);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Get chat session metadata",
        description = "Returns metadata for a single chat session owned by the authenticated user (404 if absent or owned by another user)"
    )
    public ChatSessionResponse getSession(
        @PathVariable("id") UUID id,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return chatSessionService.getSession(id, caller);
    }

    @GetMapping("/{id}/messages")
    @Operation(
        summary = "Get chat session message history",
        description = "Returns chronological (ASC) conversation turns with freshly signed citation URLs for currently authorized documents and redaction for reclassified restricted documents"
    )
    public List<ChatMessageResponse> listMessages(
        @PathVariable("id") UUID id,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return chatSessionService.listMessages(id, caller);
    }

    @PatchMapping("/{id}")
    @Operation(
        summary = "Rename chat session",
        description = "Updates the title of a chat session owned by the authenticated user"
    )
    public ChatSessionResponse renameSession(
        @PathVariable("id") UUID id,
        @Valid @RequestBody RenameChatSessionRequest body,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return chatSessionService.renameSession(id, body.title(), caller);
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Delete chat session",
        description = "Hard-deletes an owned chat session and cascades message deletion while preserving FinOps audit logs"
    )
    public ResponseEntity<Void> deleteSession(
        @PathVariable("id") UUID id,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        chatSessionService.deleteSession(id, caller);
        return ResponseEntity.noContent().build();
    }
}
