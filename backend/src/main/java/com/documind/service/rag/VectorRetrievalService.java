package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.exception.InvalidChatRequestException;
import com.documind.infrastructure.security.DocumentPreviewTokenService;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.repository.DocumentRepository;
import com.documind.service.document.CallerContext;
import com.documind.service.ingestion.ChunkMetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Executes RBAC-filtered semantic similarity retrieval over {@code pgvector} using {@link RbacVectorFilterFactory}
 * and enforces Java-layer Defense-in-Depth verification against both chunk JSONB metadata and published parent
 * {@link DocumentEntity} records before emitting structured citations with MinIO presigned URLs.
 */
@Service
public class VectorRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(VectorRetrievalService.class);
    private static final String ELLIPSIS = "...";
    private static final Pattern EXCESS_INLINE_WHITESPACE = Pattern.compile("[\\t\\x0B\\f\\r\\n]+");
    private static final Pattern MULTI_SPACE = Pattern.compile(" {2,}");

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<DocumentRepository> documentRepositoryProvider;
    private final DocumentPreviewTokenService previewTokenService;
    private final RbacVectorFilterFactory rbacVectorFilterFactory;
    private final RagProperties properties;

    public VectorRetrievalService(
        ObjectProvider<VectorStore> vectorStoreProvider,
        ObjectProvider<DocumentRepository> documentRepositoryProvider,
        DocumentPreviewTokenService previewTokenService,
        RbacVectorFilterFactory rbacVectorFilterFactory,
        RagProperties properties
    ) {
        this.vectorStoreProvider = Objects.requireNonNull(vectorStoreProvider, "vectorStoreProvider must not be null");
        this.documentRepositoryProvider = Objects.requireNonNull(documentRepositoryProvider, "documentRepositoryProvider must not be null");
        this.previewTokenService = Objects.requireNonNull(previewTokenService, "previewTokenService must not be null");
        this.rbacVectorFilterFactory = Objects.requireNonNull(rbacVectorFilterFactory, "rbacVectorFilterFactory must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Retrieves up to {@code topK} published, RBAC-authorized chunks exceeding {@code similarityThreshold}
     * without holding a database transaction across embedding HTTP or MinIO operations.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public List<RetrievedChunk> retrieve(String question, CallerContext caller) {
        return retrieve(question, caller, null);
    }

    /**
     * Retrieves up to {@code topK} published, RBAC-authorized chunks scoped by {@code SecurityContextHolder}
     * and an optional {@code SYSTEM_ADMIN}-only {@code requestedDepartmentId} filter.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public List<RetrievedChunk> retrieve(String question, CallerContext caller, UUID requestedDepartmentId) {
        RbacVectorScope rbacScope = rbacVectorFilterFactory.resolveScope(caller, requestedDepartmentId);
        return retrieveWithVerifiedScope(question, rbacScope);
    }

    /**
     * Retrieves up to {@code topK} published, RBAC-authorized chunks using a pre-resolved {@link RbacVectorScope},
     * re-validating scope authority against {@code SecurityContextHolder} before executing {@code pgvector} search.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public List<RetrievedChunk> retrieve(String question, RbacVectorScope rbacScope) {
        Objects.requireNonNull(rbacScope, "rbacScope must not be null");
        RbacVectorScope verifiedScope = rbacVectorFilterFactory.resolveScope(
            rbacScope.caller(),
            rbacScope.filterDepartmentId()
        );
        return retrieveWithVerifiedScope(question, verifiedScope);
    }

    /**
     * Builds the mandatory {@code pgvector} metadata filter for a verified {@link CallerContext}.
     */
    public Filter.Expression buildRbacFilter(CallerContext caller) {
        return RbacVectorFilterFactory.buildFilterExpression(caller, null);
    }

    private List<RetrievedChunk> retrieveWithVerifiedScope(String question, RbacVectorScope rbacScope) {
        String normalizedQuestion = normalizeQuery(question);
        double threshold = properties.retrieval().similarityThreshold();

        SearchRequest searchRequest = SearchRequest.builder()
            .query(normalizedQuestion)
            .topK(properties.retrieval().candidateFetchLimit())
            .similarityThreshold(threshold)
            .filterExpression(rbacScope.filterExpression())
            .build();

        List<Document> rawHits = vectorStoreProvider.getObject().similaritySearch(searchRequest);
        if (rawHits == null || rawHits.isEmpty()) {
            return List.of();
        }

        List<ParsedCandidate> candidates = new ArrayList<>(rawHits.size());
        Set<UUID> documentIds = new LinkedHashSet<>();
        for (Document hit : rawHits) {
            ParsedCandidate parsed = parseAndAuthorizeCandidate(hit, rbacScope);
            if (parsed != null && parsed.similarityScore() >= threshold) {
                candidates.add(parsed);
                documentIds.add(parsed.documentId());
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<UUID, DocumentEntity> parentsById = documentRepositoryProvider.getObject()
            .findAllById(documentIds)
            .stream()
            .collect(Collectors.toMap(DocumentEntity::getId, Function.identity(), (a, b) -> a));

        Map<UUID, Optional<String>> presignedUrlMemo = new HashMap<>();
        int topK = properties.retrieval().topK();
        int maxExcerptChars = properties.retrieval().maxExcerptChars();
        List<RetrievedChunk> verified = new ArrayList<>(Math.min(topK, candidates.size()));

        for (ParsedCandidate candidate : candidates) {
            if (verified.size() >= topK) {
                break;
            }
            DocumentEntity parent = parentsById.get(candidate.documentId());
            if (!isEligibleChunk(rbacScope, parent, candidate)) {
                continue;
            }

            String baseUrl = presignedUrlMemo.computeIfAbsent(
                parent.getId(),
                id -> DocumentPreviewTokenService.tryIssuePreviewBaseUrl(
                    previewTokenService,
                    parent.getId(),
                    parent.getCurrentVersion(),
                    rbacScope.caller(),
                    log
                )
            ).orElse(null);

            String citationUrl = MinioStorageService.appendPageFragment(baseUrl, candidate.pageNumber());
            String excerpt = buildExcerpt(candidate.content(), maxExcerptChars);
            CitationDto citation = new CitationDto(
                parent.getId(),
                candidate.docVersion(),
                parent.getOriginalName(),
                candidate.pageNumber(),
                candidate.sectionHeading(),
                candidate.similarityScore(),
                excerpt,
                citationUrl
            );
            verified.add(new RetrievedChunk(
                candidate.chunkId(),
                parent.getId(),
                parent.getDepartmentId(),
                parent.getAccessLevel(),
                candidate.chunkIndex(),
                candidate.content(),
                citation
            ));
        }

        return List.copyOf(verified);
    }

    static String normalizeQuery(String rawQuestion) {
        if (rawQuestion == null) {
            throw new InvalidChatRequestException("Question must not be blank");
        }
        if (rawQuestion.indexOf('\0') >= 0) {
            throw new InvalidChatRequestException("Question must not contain null characters");
        }
        String cleaned = rawQuestion
            .replace("\uFEFF", "")
            .replace("\u00AD", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n');
        if (!Normalizer.isNormalized(cleaned, Normalizer.Form.NFC)) {
            cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC);
        }
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            throw new InvalidChatRequestException("Question must not be blank");
        }
        return cleaned;
    }

    private static boolean isEligibleChunk(
        RbacVectorScope rbacScope,
        DocumentEntity parent,
        ParsedCandidate candidate
    ) {
        if (parent == null || parent.getStatus() != DocumentStatus.COMPLETED) {
            return false;
        }
        if (parent.getCurrentVersion() == null || parent.getCurrentVersion() != candidate.docVersion()) {
            return false;
        }
        boolean parentAuthorized = rbacScope.allows(parent.getDepartmentId(), parent.getAccessLevel());
        boolean metadataDrifted = !Objects.equals(parent.getDepartmentId(), candidate.departmentId())
            || parent.getAccessLevel() != candidate.accessLevel();
        if (!parentAuthorized || metadataDrifted) {
            log.error(
                "SECURITY_ALERT action=VECTOR_RBAC_POST_FILTER_DROP reason={} chunkId={} documentId={} actorUserId={} actorRole={} actorDeptId={} filterDeptId={} chunkDeptId={} chunkAccessLevel={} parentDeptId={} parentAccessLevel={}",
                !parentAuthorized ? "PARENT_UNAUTHORIZED" : "CHUNK_PARENT_METADATA_DRIFT",
                candidate.chunkId(),
                candidate.documentId(),
                rbacScope.caller().userId(),
                rbacScope.caller().role(),
                rbacScope.caller().departmentId(),
                rbacScope.filterDepartmentId(),
                candidate.departmentId(),
                candidate.accessLevel(),
                parent.getDepartmentId(),
                parent.getAccessLevel()
            );
            return false;
        }
        return true;
    }

    private static ParsedCandidate parseAndAuthorizeCandidate(Document hit, RbacVectorScope rbacScope) {
        if (hit == null || hit.getMetadata() == null) {
            return null;
        }
        Map<String, Object> metadata = hit.getMetadata();
        UUID documentId = parseUuid(metadata.get(ChunkMetadataKeys.DOCUMENT_ID));
        Integer docVersion = parseInteger(metadata.get(ChunkMetadataKeys.DOC_VERSION));
        Integer chunkIndex = parseInteger(metadata.get(ChunkMetadataKeys.CHUNK_INDEX));
        if (documentId == null || docVersion == null || docVersion < 1) {
            log.warn("Skipping vector chunk {} with missing/invalid document_id or doc_version", hit.getId());
            return null;
        }

        UUID chunkDepartmentId = parseUuid(metadata.get(ChunkMetadataKeys.DEPARTMENT_ID));
        AccessLevel chunkAccessLevel = parseAccessLevel(metadata.get(ChunkMetadataKeys.ACCESS_LEVEL));
        if (chunkDepartmentId == null || chunkAccessLevel == null) {
            log.error(
                "SECURITY_ALERT action=VECTOR_CHUNK_INVALID_RBAC_METADATA chunkId={} documentId={} actorUserId={} actorRole={}",
                hit.getId(),
                documentId,
                rbacScope.caller().userId(),
                rbacScope.caller().role()
            );
            return null;
        }
        if (!rbacScope.allows(chunkDepartmentId, chunkAccessLevel)) {
            log.error(
                "SECURITY_ALERT action=VECTOR_RBAC_POST_FILTER_DROP reason=CHUNK_METADATA_UNAUTHORIZED chunkId={} documentId={} actorUserId={} actorRole={} actorDeptId={} filterDeptId={} chunkDeptId={} chunkAccessLevel={}",
                hit.getId(),
                documentId,
                rbacScope.caller().userId(),
                rbacScope.caller().role(),
                rbacScope.caller().departmentId(),
                rbacScope.filterDepartmentId(),
                chunkDepartmentId,
                chunkAccessLevel
            );
            return null;
        }

        double score = resolveSimilarityScore(hit, metadata);
        Integer pageNumber = parseInteger(metadata.get(ChunkMetadataKeys.PAGE_NUMBER));
        if (pageNumber != null && pageNumber < 1) {
            pageNumber = null;
        }
        String sectionHeading = parseOptionalString(metadata.get(ChunkMetadataKeys.SECTION_HEADING));
        String content = hit.getText() != null ? hit.getText() : "";

        return new ParsedCandidate(
            hit.getId(),
            documentId,
            chunkDepartmentId,
            chunkAccessLevel,
            docVersion,
            chunkIndex != null && chunkIndex >= 0 ? chunkIndex : 0,
            pageNumber,
            sectionHeading,
            score,
            content
        );
    }

    private static AccessLevel parseAccessLevel(Object value) {
        if (value instanceof AccessLevel level) {
            return level;
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return AccessLevel.valueOf(str.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double resolveSimilarityScore(Document hit, Map<String, Object> metadata) {
        Double score = hit.getScore();
        if (score != null && Double.isFinite(score)) {
            return score;
        }
        Object distanceObj = metadata.get(DocumentMetadata.DISTANCE.value());
        if (distanceObj instanceof Number number) {
            double distance = number.doubleValue();
            if (Double.isFinite(distance)) {
                return Math.max(0.0d, 1.0d - distance);
            }
        }
        return 0.0d;
    }

    private static UUID parseUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return UUID.fromString(str.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String parseOptionalString(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }

    private static String buildExcerpt(String rawContent, int maxChars) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }
        String collapsed = MULTI_SPACE.matcher(
            EXCESS_INLINE_WHITESPACE.matcher(rawContent).replaceAll(" ")
        ).replaceAll(" ").trim();
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

    private record ParsedCandidate(
        String chunkId,
        UUID documentId,
        UUID departmentId,
        AccessLevel accessLevel,
        int docVersion,
        int chunkIndex,
        Integer pageNumber,
        String sectionHeading,
        double similarityScore,
        String content
    ) {
    }
}
