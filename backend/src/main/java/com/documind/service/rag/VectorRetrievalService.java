package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.exception.DocumentStorageException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.repository.DocumentRepository;
import com.documind.service.document.CallerContext;
import com.documind.service.document.DocumentVisibilityPolicy;
import com.documind.service.ingestion.ChunkMetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
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
 * Executes RBAC-filtered semantic similarity retrieval over {@code pgvector} and validates candidate chunks
 * against published document versions before emitting structured citations with MinIO presigned URLs.
 */
@Service
public class VectorRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(VectorRetrievalService.class);
    private static final String ELLIPSIS = "...";
    private static final Pattern EXCESS_INLINE_WHITESPACE = Pattern.compile("[\\t\\x0B\\f\\r\\n]+");
    private static final Pattern MULTI_SPACE = Pattern.compile(" {2,}");

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<DocumentRepository> documentRepositoryProvider;
    private final MinioStorageService minioStorageService;
    private final RagProperties properties;

    public VectorRetrievalService(
        ObjectProvider<VectorStore> vectorStoreProvider,
        ObjectProvider<DocumentRepository> documentRepositoryProvider,
        MinioStorageService minioStorageService,
        RagProperties properties
    ) {
        this.vectorStoreProvider = Objects.requireNonNull(vectorStoreProvider, "vectorStoreProvider must not be null");
        this.documentRepositoryProvider = Objects.requireNonNull(documentRepositoryProvider, "documentRepositoryProvider must not be null");
        this.minioStorageService = Objects.requireNonNull(minioStorageService, "minioStorageService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Retrieves up to {@code topK} published, RBAC-authorized chunks exceeding {@code similarityThreshold}
     * without holding a database transaction across embedding HTTP or MinIO operations.
     */
    public List<RetrievedChunk> retrieve(String question, CallerContext caller) {
        String normalizedQuestion = normalizeQuery(question);
        Filter.Expression rbacFilter = buildRbacFilter(caller);
        double threshold = properties.retrieval().similarityThreshold();

        SearchRequest searchRequest = SearchRequest.builder()
            .query(normalizedQuestion)
            .topK(properties.retrieval().candidateFetchLimit())
            .similarityThreshold(threshold)
            .filterExpression(rbacFilter)
            .build();

        List<Document> rawHits = vectorStoreProvider.getObject().similaritySearch(searchRequest);
        if (rawHits == null || rawHits.isEmpty()) {
            return List.of();
        }

        List<ParsedCandidate> candidates = new ArrayList<>(rawHits.size());
        Set<UUID> documentIds = new LinkedHashSet<>();
        for (Document hit : rawHits) {
            ParsedCandidate parsed = parseCandidate(hit);
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
            if (!isEligibleChunk(caller, parent, candidate.docVersion())) {
                continue;
            }

            String baseUrl = presignedUrlMemo.computeIfAbsent(
                parent.getId(),
                id -> generateBasePresignedUrl(parent)
            ).orElse(null);

            String citationUrl = appendPageFragment(baseUrl, candidate.pageNumber());
            String excerpt = buildExcerpt(candidate.content(), maxExcerptChars);
            CitationDto citation = new CitationDto(
                parent.getId(),
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
                candidate.chunkIndex(),
                candidate.content(),
                citation
            ));
        }

        return List.copyOf(verified);
    }

    /**
     * Builds the mandatory {@code pgvector} metadata filter strictly from server-resolved caller identity.
     */
    public Filter.Expression buildRbacFilter(CallerContext caller) {
        if (caller == null || caller.role() == null) {
            throw new InvalidChatRequestException("Caller context and role must not be null");
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return switch (caller.role()) {
            case SYSTEM_ADMIN -> builder.in(
                ChunkMetadataKeys.ACCESS_LEVEL,
                AccessLevel.PUBLIC.name(),
                AccessLevel.DEPARTMENT.name(),
                AccessLevel.CONFIDENTIAL.name()
            ).build();
            case DEPARTMENT_MANAGER -> {
                UUID departmentId = requireDepartmentId(caller);
                yield builder.or(
                    builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.PUBLIC.name()),
                    builder.eq(ChunkMetadataKeys.DEPARTMENT_ID, departmentId.toString())
                ).build();
            }
            case STANDARD_EMPLOYEE -> {
                UUID departmentId = requireDepartmentId(caller);
                yield builder.or(
                    builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.PUBLIC.name()),
                    builder.and(
                        builder.eq(ChunkMetadataKeys.DEPARTMENT_ID, departmentId.toString()),
                        builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.DEPARTMENT.name())
                    )
                ).build();
            }
        };
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

    private Optional<String> generateBasePresignedUrl(DocumentEntity parent) {
        try {
            URL url = minioStorageService.generatePresignedGetUrl(parent.getStorageKey(), parent.getOriginalName());
            return url != null ? Optional.of(url.toString()) : Optional.empty();
        } catch (DocumentStorageException ex) {
            log.warn("Failed to generate presigned citation URL for document {}: {}", parent.getId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isEligibleChunk(CallerContext caller, DocumentEntity parent, int chunkDocVersion) {
        if (parent == null || parent.getStatus() != DocumentStatus.COMPLETED) {
            return false;
        }
        if (parent.getCurrentVersion() == null || parent.getCurrentVersion() != chunkDocVersion) {
            return false;
        }
        return DocumentVisibilityPolicy.canRead(caller, parent.getDepartmentId(), parent.getAccessLevel());
    }

    private static UUID requireDepartmentId(CallerContext caller) {
        if (caller.departmentId() == null) {
            throw new InvalidChatRequestException("Caller departmentId must not be null for non-admin queries");
        }
        return caller.departmentId();
    }

    private static ParsedCandidate parseCandidate(Document hit) {
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
            docVersion,
            chunkIndex != null && chunkIndex >= 0 ? chunkIndex : 0,
            pageNumber,
            sectionHeading,
            score,
            content
        );
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

    private static String appendPageFragment(String baseUrl, Integer pageNumber) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        if (pageNumber == null) {
            return baseUrl;
        }
        return baseUrl + "#page=" + pageNumber;
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
        int docVersion,
        int chunkIndex,
        Integer pageNumber,
        String sectionHeading,
        double similarityScore,
        String content
    ) {
    }
}
