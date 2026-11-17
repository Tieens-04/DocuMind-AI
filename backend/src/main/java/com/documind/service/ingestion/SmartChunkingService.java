package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits extracted document segments into overlapping token chunks, injecting contextual section prefixes
 * within the configured token ceiling and stamping deterministic IDs plus RBAC metadata for {@code PgVectorStore}.
 */
@Service
public class SmartChunkingService {

    private static final int MIN_BODY_BUDGET_TOKENS = 32;
    private static final int MAX_HEADER_PREFIX_DIVISOR = 4;

    private final IngestionProperties properties;
    private final OverlappingTokenTextSplitter splitter;

    @Autowired
    public SmartChunkingService(IngestionProperties properties) {
        this(
            properties,
            new OverlappingTokenTextSplitter(
                properties.chunking().chunkSize(),
                properties.chunking().chunkOverlap(),
                properties.chunking().maxChunksPerDocument()
            )
        );
    }

    SmartChunkingService(IngestionProperties properties, OverlappingTokenTextSplitter splitter) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.splitter = Objects.requireNonNull(splitter, "splitter must not be null");
    }

    public List<Document> chunk(DocumentIngestionContext context, List<ExtractedSegment> segments) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (segments == null || segments.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Cannot chunk empty segment list for document: " + context.originalName()
            );
        }

        int chunkSize = properties.chunking().chunkSize();
        int chunkOverlap = properties.chunking().chunkOverlap();
        int maxChunks = properties.chunking().maxChunksPerDocument();

        List<Document> documents = new ArrayList<>();
        int globalChunkIndex = 0;

        for (ExtractedSegment segment : segments) {
            if (segment == null || segment.content() == null || segment.content().isBlank()) {
                continue;
            }
            String normalizedBody = IngestionTextNormalizer.normalizeText(segment.content());
            if (normalizedBody.isEmpty()) {
                continue;
            }

            List<String> finalChunkTexts = splitSegmentWithContext(
                normalizedBody,
                segment.sectionHeading(),
                chunkSize,
                chunkOverlap
            );

            for (String chunkText : finalChunkTexts) {
                if (globalChunkIndex >= maxChunks) {
                    throw new DocumentExtractionException(
                        DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                        "Document '" + context.originalName() + "' exceeded maxChunksPerDocument limit of " + maxChunks
                    );
                }

                String chunkId = ChunkIdGenerator.generate(
                    context.documentId(),
                    context.docVersion(),
                    globalChunkIndex
                ).toString();
                Map<String, Object> metadata = buildMetadata(context, segment, globalChunkIndex);
                documents.add(new Document(chunkId, chunkText, metadata));
                globalChunkIndex++;
            }
        }

        if (documents.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "No non-empty chunks produced for document: " + context.originalName()
            );
        }

        return List.copyOf(documents);
    }

    private List<String> splitSegmentWithContext(
        String normalizedBody,
        String sectionHeading,
        int chunkSize,
        int chunkOverlap
    ) {
        if (sectionHeading == null || sectionHeading.isBlank()) {
            return splitter.splitTextWithBudget(normalizedBody, chunkSize, chunkOverlap);
        }

        String normalizedHeading = IngestionTextNormalizer.normalizeText(sectionHeading);
        int maxHeaderTokens = Math.max(8, Math.min(chunkSize / MAX_HEADER_PREFIX_DIVISOR, chunkSize - 16));
        String headerForPrefix = capHeadingToTokenBudget(normalizedHeading, maxHeaderTokens);
        String prefix = headerForPrefix + "\n\n";
        int prefixTokens = splitter.countTokens(prefix);

        int initialBodyBudget = Math.max(8, chunkSize - prefixTokens);
        int minBodyBudget = Math.min(MIN_BODY_BUDGET_TOKENS, initialBodyBudget);
        int bodyBudget = initialBodyBudget;
        int bodyOverlap = Math.min(chunkOverlap, Math.max(0, bodyBudget - 1));
        List<String> rawBodyChunks = splitter.splitTextWithBudget(normalizedBody, bodyBudget, bodyOverlap);

        while (!allFitWithPrefix(rawBodyChunks, normalizedHeading, prefix, chunkSize) && bodyBudget > minBodyBudget) {
            bodyBudget = Math.max(minBodyBudget, bodyBudget - 4);
            bodyOverlap = Math.min(chunkOverlap, Math.max(0, bodyBudget - 1));
            rawBodyChunks = splitter.splitTextWithBudget(normalizedBody, bodyBudget, bodyOverlap);
        }

        List<String> enriched = new ArrayList<>(rawBodyChunks.size());
        for (String bodyChunk : rawBodyChunks) {
            enriched.add(assembleWithPrefix(bodyChunk, normalizedHeading, prefix));
        }
        return List.copyOf(enriched);
    }

    private boolean allFitWithPrefix(
        List<String> rawBodyChunks,
        String normalizedHeading,
        String prefix,
        int chunkSize
    ) {
        return rawBodyChunks.stream()
            .allMatch(body -> splitter.countTokens(assembleWithPrefix(body, normalizedHeading, prefix)) <= chunkSize);
    }

    private String assembleWithPrefix(String bodyChunk, String fullHeading, String prefix) {
        if (bodyChunk.startsWith(fullHeading) || bodyChunk.startsWith(prefix)) {
            return bodyChunk;
        }
        return prefix + bodyChunk;
    }

    private String capHeadingToTokenBudget(String heading, int maxTokens) {
        if (splitter.countTokens(heading) <= maxTokens) {
            return heading;
        }
        List<String> headParts = splitter.splitTextWithBudget(heading, Math.max(8, maxTokens), 0);
        return headParts.isEmpty() ? heading : headParts.getFirst();
    }

    private static Map<String, Object> buildMetadata(
        DocumentIngestionContext context,
        ExtractedSegment segment,
        int globalChunkIndex
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.DOCUMENT_ID, context.documentId().toString());
        metadata.put(ChunkMetadataKeys.DEPARTMENT_ID, context.departmentId().toString());
        metadata.put(ChunkMetadataKeys.ACCESS_LEVEL, context.accessLevel().name());
        metadata.put(ChunkMetadataKeys.FILE_NAME, context.originalName());
        metadata.put(ChunkMetadataKeys.DOC_VERSION, context.docVersion());
        metadata.put(ChunkMetadataKeys.CHUNK_INDEX, globalChunkIndex);
        if (segment.pageNumber() != null) {
            metadata.put(ChunkMetadataKeys.PAGE_NUMBER, segment.pageNumber());
        }
        if (segment.sectionHeading() != null && !segment.sectionHeading().isBlank()) {
            metadata.put(ChunkMetadataKeys.SECTION_HEADING, segment.sectionHeading());
        }
        return metadata;
    }
}
