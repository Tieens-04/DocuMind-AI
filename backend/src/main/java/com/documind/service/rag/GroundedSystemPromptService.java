package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds the bilingual grounded {@link SystemPromptTemplate} enclosing retrieved chunks inside sanitized
 * {@code <context>} tags and enforcing inline source citations.
 */
@Service
public class GroundedSystemPromptService {

    private static final String DEFAULT_TEMPLATE_PATH = "prompts/rag-grounded-system.st";
    private static final Pattern DELIMITER_TAG_PATTERN =
        Pattern.compile("(?i)<(\\s*/?\\s*(?:context|document)\\b[^>]*)>");

    private final SystemPromptTemplate systemPromptTemplate;

    public GroundedSystemPromptService() {
        this(new ClassPathResource(DEFAULT_TEMPLATE_PATH));
    }

    GroundedSystemPromptService(Resource templateResource) {
        Objects.requireNonNull(templateResource, "templateResource must not be null");
        String templateText = readUtf8Template(templateResource);
        this.systemPromptTemplate = new SystemPromptTemplate(templateText);
    }

    public Message buildSystemMessage(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be null or empty");
        }
        StringBuilder contextBuilder = new StringBuilder(chunks.size() * 512);
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = Objects.requireNonNull(chunks.get(i), "chunk must not be null");
            CitationDto citation = chunk.citation();
            String location = formatLocation(citation);
            String citationTag = formatCitationTag(citation);

            if (i > 0) {
                contextBuilder.append('\n');
            }
            contextBuilder
                .append("<document index=\"").append(i + 1).append('"')
                .append(" file_name=\"").append(escapeXmlAttr(citation.fileName())).append('"')
                .append(" location=\"").append(escapeXmlAttr(location)).append('"')
                .append(" citation=\"").append(escapeXmlAttr(citationTag)).append("\">\n")
                .append(sanitizeDelimiterTags(chunk.content()))
                .append("\n</document>");
        }
        return systemPromptTemplate.createMessage(Map.of("context", contextBuilder.toString()));
    }

    static String formatCitationTag(CitationDto citation) {
        String fileName = citation.fileName() != null ? citation.fileName() : "document";
        if (citation.pageNumber() != null) {
            return "[Nguồn: " + fileName + " - Trang " + citation.pageNumber() + "]";
        }
        if (citation.sectionHeading() != null && !citation.sectionHeading().isBlank()) {
            return "[Nguồn: " + fileName + " - Mục " + citation.sectionHeading().trim() + "]";
        }
        return "[Nguồn: " + fileName + "]";
    }

    private static String formatLocation(CitationDto citation) {
        if (citation.pageNumber() != null) {
            return "Trang " + citation.pageNumber();
        }
        if (citation.sectionHeading() != null && !citation.sectionHeading().isBlank()) {
            return "Mục " + citation.sectionHeading().trim();
        }
        return "Toàn văn";
    }

    // Neutralizes structural XML delimiter breakout (<context>/<document>) while preserving legitimate symbols.
    // Instruction-level prompt injection inside chunk bodies is mitigated at the prompt layer (rag-grounded-system.st rule #2).
    private static String sanitizeDelimiterTags(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) {
            return "";
        }
        return DELIMITER_TAG_PATTERN.matcher(rawContent).replaceAll(match ->
            "&lt;" + match.group(1) + "&gt;"
        );
    }

    private static String escapeXmlAttr(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String normalized = raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return normalized
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private static String readUtf8Template(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load system prompt template: " + resource.getDescription(), ex);
        }
    }
}
