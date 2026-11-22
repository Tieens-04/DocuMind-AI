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
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.enums.Role;
import com.documind.domain.model.CitationPayload;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.DocumentStorageException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.ChatMessageRepository;
import com.documind.repository.ChatSessionRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.document.CallerContext;
import com.documind.service.rag.RagProperties;
import com.documind.service.rag.RbacVectorFilterFactory;
import com.documind.service.rag.RbacVectorScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URL;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages strict owner-only multi-turn {@link ChatSessionEntity} lifecycles, deterministic zero-token
 * auto-titling, RBAC-reverified historical citation hydration, recent conversation history loading,
 * and atomic post-turn message + audit persistence without holding DB transactions across I/O.
 */
@Service
public class ChatSessionService {

    public static final String DEFAULT_SESSION_TITLE = "Cuộc hội thoại mới";
    public static final String REDACTED_ASSISTANT_CONTENT =
        "[Nội dung câu trả lời đã được ẩn do quyền truy cập hoặc trạng thái tài liệu nguồn đã thay đổi]";

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_IP_LENGTH = 45;
    private static final String ELLIPSIS = "...";
    private static final Pattern WHITESPACE_COLLAPSE = Pattern.compile("[\\s\\u00A0]+");

    private final ObjectProvider<ChatSessionRepository> chatSessionRepositoryProvider;
    private final ObjectProvider<ChatMessageRepository> chatMessageRepositoryProvider;
    private final ObjectProvider<UserRepository> userRepositoryProvider;
    private final ObjectProvider<DepartmentRepository> departmentRepositoryProvider;
    private final ObjectProvider<DocumentRepository> documentRepositoryProvider;
    private final ObjectProvider<AuditLogRepository> auditLogRepositoryProvider;
    private final MinioStorageService minioStorageService;
    private final RbacVectorFilterFactory rbacVectorFilterFactory;
    private final ObjectProvider<TransactionTemplate> transactionTemplateProvider;
    private final RagProperties ragProperties;

    public ChatSessionService(
        ObjectProvider<ChatSessionRepository> chatSessionRepositoryProvider,
        ObjectProvider<ChatMessageRepository> chatMessageRepositoryProvider,
        ObjectProvider<UserRepository> userRepositoryProvider,
        ObjectProvider<DepartmentRepository> departmentRepositoryProvider,
        ObjectProvider<DocumentRepository> documentRepositoryProvider,
        ObjectProvider<AuditLogRepository> auditLogRepositoryProvider,
        MinioStorageService minioStorageService,
        RbacVectorFilterFactory rbacVectorFilterFactory,
        ObjectProvider<TransactionTemplate> transactionTemplateProvider,
        RagProperties ragProperties
    ) {
        this.chatSessionRepositoryProvider = Objects.requireNonNull(chatSessionRepositoryProvider, "chatSessionRepositoryProvider must not be null");
        this.chatMessageRepositoryProvider = Objects.requireNonNull(chatMessageRepositoryProvider, "chatMessageRepositoryProvider must not be null");
        this.userRepositoryProvider = Objects.requireNonNull(userRepositoryProvider, "userRepositoryProvider must not be null");
        this.departmentRepositoryProvider = Objects.requireNonNull(departmentRepositoryProvider, "departmentRepositoryProvider must not be null");
        this.documentRepositoryProvider = Objects.requireNonNull(documentRepositoryProvider, "documentRepositoryProvider must not be null");
        this.auditLogRepositoryProvider = Objects.requireNonNull(auditLogRepositoryProvider, "auditLogRepositoryProvider must not be null");
        this.minioStorageService = Objects.requireNonNull(minioStorageService, "minioStorageService must not be null");
        this.rbacVectorFilterFactory = Objects.requireNonNull(rbacVectorFilterFactory, "rbacVectorFilterFactory must not be null");
        this.transactionTemplateProvider = Objects.requireNonNull(transactionTemplateProvider, "transactionTemplateProvider must not be null");
        this.ragProperties = Objects.requireNonNull(ragProperties, "ragProperties must not be null");
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public ChatSessionResponse createSession(String rawTitle, CallerContext caller) {
        RbacVectorScope scope = rbacVectorFilterFactory.resolveScope(caller, null);
        UserEntity actor = resolveRequiredActor(scope.caller());
        String normalizedTitle = normalizeOptionalTitle(rawTitle);

        ChatSessionEntity saved = transactionTemplateProvider.getObject().execute(status -> {
            ChatSessionEntity session = new ChatSessionEntity();
            session.setUser(actor);
            session.setTitle(normalizedTitle);
            return chatSessionRepositoryProvider.getObject().save(session);
        });
        return ChatSessionResponse.from(Objects.requireNonNull(saved, "saved session must not be null"));
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public PageResponse<ChatSessionResponse> listSessions(int page, int size, CallerContext caller) {
        RbacVectorScope scope = rbacVectorFilterFactory.resolveScope(caller, null);
        UserEntity actor = resolveRequiredActor(scope.caller());
        validatePaging(page, size);

        PageRequest pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))
        );
        Page<ChatSessionResponse> sessionPage = chatSessionRepositoryProvider.getObject()
            .findByUserId(actor.getId(), pageable)
            .map(ChatSessionResponse::from);
        return PageResponse.from(sessionPage);
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public ChatSessionResponse getSession(UUID sessionId, CallerContext caller) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        RbacVectorScope scope = rbacVectorFilterFactory.resolveScope(caller, null);
        UserEntity actor = resolveRequiredActor(scope.caller());
        ChatSessionEntity session = requireOwnedSession(sessionId, actor.getId());
        return ChatSessionResponse.from(session);
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public List<ChatMessageResponse> listMessages(UUID sessionId, CallerContext caller) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        RbacVectorScope scope = rbacVectorFilterFactory.resolveScope(caller, null);
        UserEntity actor = resolveRequiredActor(scope.caller());
        requireOwnedSession(sessionId, actor.getId());

        List<ChatMessageEntity> messages = chatMessageRepositoryProvider.getObject()
            .findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
        if (messages.isEmpty()) {
            return List.of();
        }

        Map<UUID, DocumentEntity> parentsById = loadReferencedDocuments(messages);

        Map<UUID, Optional<String>> presignedUrlMemo = new HashMap<>();
        List<ChatMessageResponse> responses = new ArrayList<>(messages.size());
        for (ChatMessageEntity message : messages) {
            boolean redactAssistant = message.getRole() == MessageRole.ASSISTANT
                && hasUnauthorizedCitation(message.getCitations(), parentsById, scope);
            List<CitationDto> hydrated = hydrateCitations(message.getCitations(), parentsById, scope, presignedUrlMemo);
            if (redactAssistant) {
                responses.add(new ChatMessageResponse(
                    message.getId(),
                    message.getSessionId(),
                    message.getRole(),
                    REDACTED_ASSISTANT_CONTENT,
                    null,
                    hydrated,
                    message.isAbstained(),
                    message.getPromptTokens() != null ? message.getPromptTokens() : 0,
                    message.getCompletionTokens() != null ? message.getCompletionTokens() : 0,
                    message.getLatencyMs(),
                    message.getModelProvider(),
                    message.getCreatedAt()
                ));
            } else {
                responses.add(ChatMessageResponse.from(message, hydrated));
            }
        }
        return List.copyOf(responses);
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public ChatSessionResponse renameSession(UUID sessionId, String rawTitle, CallerContext caller) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        RbacVectorScope scope = rbacVectorFilterFactory.resolveScope(caller, null);
        UserEntity actor = resolveRequiredActor(scope.caller());
        String normalizedTitle = normalizeRequiredTitle(rawTitle);

        ChatSessionEntity updated = transactionTemplateProvider.getObject().execute(status -> {
            ChatSessionEntity session = requireOwnedSessionForUpdate(sessionId, actor.getId());
            session.setTitle(normalizedTitle);
            session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            return chatSessionRepositoryProvider.getObject().save(session);
        });
        return ChatSessionResponse.from(Objects.requireNonNull(updated, "updated session must not be null"));
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public void deleteSession(UUID sessionId, CallerContext caller) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        RbacVectorScope scope = rbacVectorFilterFactory.resolveScope(caller, null);
        UserEntity actor = resolveRequiredActor(scope.caller());

        transactionTemplateProvider.getObject().executeWithoutResult(status -> {
            ChatSessionEntity session = requireOwnedSessionForUpdate(sessionId, actor.getId());
            chatSessionRepositoryProvider.getObject().delete(session);
        });
    }

    /**
     * Loads and verifies session ownership plus the bounded recent message window in chronological
     * (oldest -> newest) order, filtering out any historical turns whose cited source documents are no
     * longer {@code COMPLETED} or authorized under {@code rbacScope}.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public List<ChatMessageEntity> loadAuthorizedHistoryForTurn(
        UUID sessionId,
        UUID ownerUserId,
        RbacVectorScope rbacScope
    ) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(rbacScope, "rbacScope must not be null");
        if (ownerUserId == null) {
            throw new ResourceNotFoundException("Chat session not found: " + sessionId);
        }
        requireOwnedSession(sessionId, ownerUserId);
        int windowSize = ragProperties.memory().historyWindowSize();
        List<ChatMessageEntity> descRecent = chatMessageRepositoryProvider.getObject()
            .findBySessionIdOrderByCreatedAtDescIdDesc(sessionId, PageRequest.of(0, windowSize));

        if (descRecent == null || descRecent.isEmpty()) {
            return List.of();
        }
        List<ChatMessageEntity> chronological = new ArrayList<>(descRecent);
        Collections.reverse(chronological);

        Map<UUID, DocumentEntity> parentsById = loadReferencedDocuments(chronological);
        List<ChatMessageEntity> authorized = new ArrayList<>(chronological.size());
        for (int i = 0; i < chronological.size(); i++) {
            ChatMessageEntity current = chronological.get(i);
            if (current == null) {
                continue;
            }
            if (current.getRole() == MessageRole.ASSISTANT
                && hasUnauthorizedCitation(current.getCitations(), parentsById, rbacScope)) {
                continue;
            }
            if (current.getRole() == MessageRole.USER
                && i + 1 < chronological.size()
                && chronological.get(i + 1) != null
                && chronological.get(i + 1).getRole() == MessageRole.ASSISTANT
                && hasUnauthorizedCitation(chronological.get(i + 1).getCitations(), parentsById, rbacScope)) {
                i++;
                continue;
            }
            authorized.add(current);
        }
        return List.copyOf(authorized);
    }

    /**
     * Atomically creates or updates the session under a pessimistic write lock, persists the
     * {@code (USER, ASSISTANT)} message pair with microsecond-monotonic timestamps, and commits the linked
     * {@link AuditLogEntity}. If a concurrent {@code DELETE} removed the target session during Phase-2 I/O,
     * still commits the FinOps {@link AuditLogEntity} ({@code status_code = 404}, {@code session = null})
     * before throwing {@link ResourceNotFoundException}.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public PersistedTurnResult recordCompletedTurn(CompletedTurnCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.actor(), "command.actor must not be null");

        PersistedTurnResult persisted = transactionTemplateProvider.getObject().execute(status -> {
            ChatSessionRepository sessionRepo = chatSessionRepositoryProvider.getObject();
            ChatMessageRepository messageRepo = chatMessageRepositoryProvider.getObject();
            DepartmentEntity department = command.auditDepartmentId() != null
                ? departmentRepositoryProvider.getObject().getReferenceById(command.auditDepartmentId())
                : null;

            ChatSessionEntity session;
            OffsetDateTime userCreatedAt;
            OffsetDateTime assistantCreatedAt;
            if (command.sessionId() == null) {
                userCreatedAt = resolveMonotonicTimestamp(null);
                assistantCreatedAt = resolveMonotonicTimestamp(userCreatedAt);
                session = new ChatSessionEntity();
                session.setUser(command.actor());
                session.setTitle(deriveAutoTitle(command.userQuestion()));
                session.setCreatedAt(userCreatedAt);
                session.setUpdatedAt(assistantCreatedAt);
                session = sessionRepo.save(session);
            } else {
                Optional<ChatSessionEntity> lockedOpt =
                    sessionRepo.findByIdAndUserIdForUpdate(command.sessionId(), command.actor().getId());
                if (lockedOpt.isEmpty()) {
                    // Preserve FinOps token accounting even if session was deleted while LLM I/O was in flight
                    auditLogRepositoryProvider.getObject().save(buildChatAuditLog(
                        command.actor(),
                        department,
                        null,
                        command.userQuestion(),
                        command.abstained(),
                        command.promptTokens(),
                        command.completionTokens(),
                        command.latencyMs(),
                        command.modelProvider(),
                        HttpStatus.NOT_FOUND.value(),
                        command.ipAddress()
                    ));
                    return null;
                }
                session = lockedOpt.get();
                Optional<ChatMessageEntity> latestMsg =
                    messageRepo.findFirstBySessionIdOrderByCreatedAtDescIdDesc(session.getId());
                boolean isFirstTurn = latestMsg.isEmpty();
                OffsetDateTime floor = latestMsg.map(ChatMessageEntity::getCreatedAt).orElse(session.getCreatedAt());
                userCreatedAt = resolveMonotonicTimestamp(floor);
                assistantCreatedAt = resolveMonotonicTimestamp(userCreatedAt);
                if (isFirstTurn && DEFAULT_SESSION_TITLE.equals(session.getTitle())) {
                    session.setTitle(deriveAutoTitle(command.userQuestion()));
                }
                session.setUpdatedAt(assistantCreatedAt);
                session = sessionRepo.save(session);
            }

            ChatMessageEntity userMessage = new ChatMessageEntity();
            userMessage.setSession(session);
            userMessage.setRole(MessageRole.USER);
            userMessage.setContent(command.userQuestion());
            userMessage.setCitations(List.of());
            userMessage.setAbstained(false);
            userMessage.setCacheTier(CacheTier.NONE);
            userMessage.setPromptTokens(0);
            userMessage.setCompletionTokens(0);
            userMessage.setCreatedAt(userCreatedAt);
            messageRepo.save(userMessage);

            List<CitationPayload> citationPayloads = command.citations().stream()
                .map(CitationDto::toCitationPayload)
                .toList();

            ChatMessageEntity assistantMessage = new ChatMessageEntity();
            assistantMessage.setSession(session);
            assistantMessage.setRole(MessageRole.ASSISTANT);
            assistantMessage.setContent(command.assistantAnswer());
            assistantMessage.setRewrittenQuery(command.rewrittenQuery());
            assistantMessage.setCitations(citationPayloads);
            assistantMessage.setAbstained(command.abstained());
            assistantMessage.setCacheTier(CacheTier.NONE);
            assistantMessage.setPromptTokens(Math.max(0, command.promptTokens()));
            assistantMessage.setCompletionTokens(Math.max(0, command.completionTokens()));
            assistantMessage.setLatencyMs(Math.max(0L, command.latencyMs()));
            assistantMessage.setModelProvider(command.modelProvider());
            assistantMessage.setCreatedAt(assistantCreatedAt);
            ChatMessageEntity savedAssistant = messageRepo.save(assistantMessage);

            auditLogRepositoryProvider.getObject().save(buildChatAuditLog(
                command.actor(),
                department,
                session,
                command.userQuestion(),
                command.abstained(),
                command.promptTokens(),
                command.completionTokens(),
                command.latencyMs(),
                command.modelProvider(),
                HttpStatus.OK.value(),
                command.ipAddress()
            ));

            return new PersistedTurnResult(session.getId(), savedAssistant.getId());
        });
        if (persisted == null) {
            throw new ResourceNotFoundException("Chat session not found: " + command.sessionId());
        }
        return persisted;
    }

    /**
     * Records a {@code 502} provider-failure {@link AuditLogEntity} while locking any existing target session
     * so concurrent deletion cannot trigger foreign-key violations on commit.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public void recordFailureAudit(
        UserEntity actor,
        UUID auditDepartmentId,
        UUID sessionId,
        String queryText,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String failedProvider,
        String ipAddress
    ) {
        recordFailureAudit(
            actor,
            auditDepartmentId,
            sessionId,
            queryText,
            promptTokens,
            completionTokens,
            latencyMs,
            failedProvider,
            HttpStatus.BAD_GATEWAY.value(),
            ipAddress
        );
    }

    /**
     * Records a non-200 failure or client-abort ({@code 499} / {@code 502} / {@code 504}) {@link AuditLogEntity}
     * while locking any existing target session so concurrent deletion cannot trigger foreign-key violations on commit.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public void recordFailureAudit(
        UserEntity actor,
        UUID auditDepartmentId,
        UUID sessionId,
        String queryText,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String failedProvider,
        int statusCode,
        String ipAddress
    ) {
        transactionTemplateProvider.getObject().executeWithoutResult(status -> {
            ChatSessionEntity session = (sessionId != null && actor != null)
                ? chatSessionRepositoryProvider.getObject()
                    .findByIdAndUserIdForUpdate(sessionId, actor.getId())
                    .orElse(null)
                : null;
            DepartmentEntity department = auditDepartmentId != null
                ? departmentRepositoryProvider.getObject().getReferenceById(auditDepartmentId)
                : null;
            auditLogRepositoryProvider.getObject().save(buildChatAuditLog(
                actor,
                department,
                session,
                queryText,
                true,
                promptTokens,
                completionTokens,
                latencyMs,
                failedProvider,
                statusCode,
                ipAddress
            ));
        });
    }

    /**
     * Deterministically derives a clean Unicode NFC title from the first question in {@code 0} LLM tokens.
     */
    public String deriveAutoTitle(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return DEFAULT_SESSION_TITLE;
        }
        String cleaned = normalizedQuestion
            .replace("\0", "")
            .replace("\uFEFF", "")
            .replace("\u00AD", "");
        if (!Normalizer.isNormalized(cleaned, Normalizer.Form.NFC)) {
            cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC);
        }
        String collapsed = WHITESPACE_COLLAPSE.matcher(cleaned).replaceAll(" ").trim();
        if (collapsed.isEmpty()) {
            return DEFAULT_SESSION_TITLE;
        }
        int maxChars = Math.min(MAX_TITLE_LENGTH, ragProperties.memory().autoTitleMaxChars());
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        int budget = Math.max(1, maxChars - ELLIPSIS.length());
        int cut = budget;
        if (cut > 0 && cut < collapsed.length() && Character.isLowSurrogate(collapsed.charAt(cut))) {
            cut--;
        }
        while (cut > 0 && Character.isHighSurrogate(collapsed.charAt(cut - 1))
            && (cut >= collapsed.length() || !Character.isLowSurrogate(collapsed.charAt(cut)))) {
            cut--;
        }
        int lastSpace = collapsed.lastIndexOf(' ', cut);
        if (lastSpace > budget / 2) {
            cut = lastSpace;
        }
        String prefix = collapsed.substring(0, Math.max(1, cut)).trim();
        while (!prefix.isEmpty() && Character.isHighSurrogate(prefix.charAt(prefix.length() - 1))) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + ELLIPSIS;
    }

    private Map<UUID, DocumentEntity> loadReferencedDocuments(List<ChatMessageEntity> messages) {
        Set<UUID> referencedDocIds = new LinkedHashSet<>();
        for (ChatMessageEntity message : messages) {
            if (message != null && message.getCitations() != null) {
                for (CitationPayload payload : message.getCitations()) {
                    if (payload != null && payload.documentId() != null) {
                        referencedDocIds.add(payload.documentId());
                    }
                }
            }
        }
        if (referencedDocIds.isEmpty()) {
            return Map.of();
        }
        return documentRepositoryProvider.getObject()
            .findAllById(referencedDocIds)
            .stream()
            .collect(Collectors.toMap(DocumentEntity::getId, Function.identity(), (a, b) -> a));
    }

    private static boolean hasUnauthorizedCitation(
        List<CitationPayload> storedCitations,
        Map<UUID, DocumentEntity> parentsById,
        RbacVectorScope scope
    ) {
        if (storedCitations == null || storedCitations.isEmpty()) {
            return false;
        }
        for (CitationPayload payload : storedCitations) {
            if (payload != null && !isCitationAuthorized(payload, parentsById, scope)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCitationAuthorized(
        CitationPayload payload,
        Map<UUID, DocumentEntity> parentsById,
        RbacVectorScope scope
    ) {
        if (payload == null || payload.documentId() == null) {
            return false;
        }
        DocumentEntity parent = parentsById.get(payload.documentId());
        return parent != null
            && parent.getStatus() == DocumentStatus.COMPLETED
            && scope.allows(parent.getDepartmentId(), parent.getAccessLevel());
    }

    private List<CitationDto> hydrateCitations(
        List<CitationPayload> storedCitations,
        Map<UUID, DocumentEntity> parentsById,
        RbacVectorScope scope,
        Map<UUID, Optional<String>> presignedUrlMemo
    ) {
        if (storedCitations == null || storedCitations.isEmpty()) {
            return List.of();
        }
        List<CitationDto> result = new ArrayList<>(storedCitations.size());
        for (CitationPayload payload : storedCitations) {
            if (payload == null) {
                continue;
            }
            if (!isCitationAuthorized(payload, parentsById, scope)) {
                // Fail closed on deleted, restricted, reclassified, or non-COMPLETED documents
                result.add(new CitationDto(
                    payload.documentId(),
                    payload.fileName(),
                    payload.pageNumber(),
                    payload.sectionHeading(),
                    payload.similarityScore(),
                    "",
                    null
                ));
                continue;
            }
            DocumentEntity parent = parentsById.get(payload.documentId());
            String baseUrl = presignedUrlMemo.computeIfAbsent(
                parent.getId(),
                id -> generateBasePresignedUrl(parent)
            ).orElse(null);
            String citationUrl = appendPageFragment(baseUrl, payload.pageNumber());
            result.add(new CitationDto(
                parent.getId(),
                parent.getOriginalName(),
                payload.pageNumber(),
                payload.sectionHeading(),
                payload.similarityScore(),
                payload.excerpt(),
                citationUrl
            ));
        }
        return List.copyOf(result);
    }

    private Optional<String> generateBasePresignedUrl(DocumentEntity parent) {
        try {
            URL url = minioStorageService.generatePresignedGetUrl(parent.getStorageKey(), parent.getOriginalName());
            return url != null ? Optional.of(url.toString()) : Optional.empty();
        } catch (DocumentStorageException ex) {
            log.warn("Failed to re-sign historical citation URL for document {}: {}", parent.getId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private ChatSessionEntity requireOwnedSession(UUID sessionId, UUID ownerUserId) {
        return chatSessionRepositoryProvider.getObject()
            .findByIdAndUserId(sessionId, ownerUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Chat session not found: " + sessionId));
    }

    private ChatSessionEntity requireOwnedSessionForUpdate(UUID sessionId, UUID ownerUserId) {
        return chatSessionRepositoryProvider.getObject()
            .findByIdAndUserIdForUpdate(sessionId, ownerUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Chat session not found: " + sessionId));
    }

    private UserEntity resolveRequiredActor(CallerContext caller) {
        UUID userId = caller.userId();
        if (userId == null) {
            throw new ResourceNotFoundException("Authenticated user record not found");
        }
        UserEntity user = userRepositoryProvider.getObject().findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        assertCallerMatchesUser(caller, user);
        return user;
    }

    public static void assertCallerMatchesUser(CallerContext caller, UserEntity user) {
        if (!user.isActive()) {
            throw new DocumentAccessDeniedException("User account is inactive");
        }
        if (!user.getRole().canAssume(caller.role())) {
            throw new DocumentAccessDeniedException("Caller role exceeds authenticated user privileges");
        }
        if (!caller.isSystemAdmin()
            && user.getRole() != Role.SYSTEM_ADMIN
            && caller.departmentId() != null
            && !Objects.equals(user.getDepartmentId(), caller.departmentId())) {
            throw new DocumentAccessDeniedException("Caller user does not belong to target department");
        }
    }

    private static AuditLogEntity buildChatAuditLog(
        UserEntity user,
        DepartmentEntity department,
        ChatSessionEntity session,
        String queryText,
        boolean abstained,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String provider,
        int statusCode,
        String ipAddress
    ) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setUser(user);
        auditLog.setDepartment(department);
        auditLog.setSession(session);
        auditLog.setActionType(AuditActionType.CHAT_QUERY);
        auditLog.setQueryText(queryText);
        auditLog.setAbstained(abstained);
        auditLog.setCacheTier(CacheTier.NONE);
        auditLog.setPromptTokens(Math.max(0, promptTokens));
        auditLog.setCompletionTokens(Math.max(0, completionTokens));
        auditLog.setLatencyMs(Math.max(0L, latencyMs));
        auditLog.setProvider(provider);
        auditLog.setStatusCode(statusCode);
        auditLog.setIpAddress(truncateIp(ipAddress));
        return auditLog;
    }

    private static String normalizeOptionalTitle(String rawTitle) {
        if (rawTitle == null) {
            return DEFAULT_SESSION_TITLE;
        }
        String sanitized = cleanAndValidateTitleChars(rawTitle);
        return sanitized.isEmpty() ? DEFAULT_SESSION_TITLE : sanitized;
    }

    private static String normalizeRequiredTitle(String rawTitle) {
        if (rawTitle == null) {
            throw new InvalidChatRequestException("Session title must not be blank");
        }
        String sanitized = cleanAndValidateTitleChars(rawTitle);
        if (sanitized.isEmpty()) {
            throw new InvalidChatRequestException("Session title must not be blank");
        }
        return sanitized;
    }

    private static String cleanAndValidateTitleChars(String rawTitle) {
        if (rawTitle.indexOf('\0') >= 0) {
            throw new InvalidChatRequestException("Session title must not contain null characters");
        }
        String cleaned = rawTitle
            .replace("\uFEFF", "")
            .replace("\u00AD", "")
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ');
        if (!Normalizer.isNormalized(cleaned, Normalizer.Form.NFC)) {
            cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC);
        }
        cleaned = cleaned.trim();
        if (cleaned.length() > MAX_TITLE_LENGTH) {
            throw new InvalidChatRequestException("Session title must not exceed " + MAX_TITLE_LENGTH + " characters");
        }
        return cleaned;
    }

    private static void validatePaging(int page, int size) {
        if (page < 0) {
            throw new InvalidChatRequestException("Page index must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidChatRequestException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static OffsetDateTime resolveMonotonicTimestamp(OffsetDateTime floorExclusive) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (floorExclusive == null) {
            return now;
        }
        OffsetDateTime floorMicros = floorExclusive.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        return now.isAfter(floorMicros) ? now : floorMicros.plus(1, ChronoUnit.MICROS);
    }

    private static String appendPageFragment(String baseUrl, Integer pageNumber) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        if (pageNumber == null) {
            return baseUrl;
        }
        return baseUrl + "#page=" + pageNumber;
    }

    public static String truncateIp(String ipAddress) {
        if (ipAddress == null) {
            return null;
        }
        String trimmed = ipAddress.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_IP_LENGTH ? trimmed.substring(0, MAX_IP_LENGTH) : trimmed;
    }

    public record CompletedTurnCommand(
        UserEntity actor,
        UUID auditDepartmentId,
        UUID sessionId,
        String userQuestion,
        String rewrittenQuery,
        String assistantAnswer,
        List<CitationDto> citations,
        boolean abstained,
        String modelProvider,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String ipAddress
    ) {
        public CompletedTurnCommand {
            citations = citations != null ? List.copyOf(citations) : List.of();
        }
    }

    public record PersistedTurnResult(
        UUID sessionId,
        UUID assistantMessageId
    ) {
    }
}
