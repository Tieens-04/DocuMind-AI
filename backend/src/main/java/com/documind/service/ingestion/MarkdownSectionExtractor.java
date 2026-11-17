package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Markdown sections with hierarchical {@code section_heading} breadcrumbs while ignoring headings inside code fences and tables.
 */
@Component
public class MarkdownSectionExtractor {

    private static final int MAX_HEADING_LEVELS = 6;
    private static final Pattern ATX_HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern FENCE_OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern SETEXT_H1 = Pattern.compile("^ {0,3}={3,}[ \\t]*$");
    private static final Pattern SETEXT_H2 = Pattern.compile("^ {0,3}-{3,}[ \\t]*$");
    private static final Pattern LIST_OR_QUOTE_PREFIX = Pattern.compile("^ {0,3}(?:[>*+\\-]|\\d+[.)])[ \\t]+.*$");

    private final IngestionProperties properties;

    public MarkdownSectionExtractor() {
        this(IngestionProperties.defaults());
    }

    @Autowired
    public MarkdownSectionExtractor(IngestionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public List<ExtractedSegment> extract(InputStream inputStream, String originalName) {
        String normalized = IngestionTextNormalizer.readBoundedUtf8(
            inputStream,
            properties.extraction().maxSpoolBytes(),
            properties.extraction().maxExtractedChars(),
            originalName
        );
        return extractNormalized(normalized, originalName);
    }

    public List<ExtractedSegment> extractFromText(String rawMarkdown, String originalName) {
        if (rawMarkdown == null) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Markdown document contains no extractable text: " + originalName
            );
        }
        String normalized = IngestionTextNormalizer.normalizeRawText(
            rawMarkdown,
            properties.extraction().maxExtractedChars(),
            originalName
        );
        return extractNormalized(normalized, originalName);
    }

    private static List<ExtractedSegment> extractNormalized(String normalized, String originalName) {
        if (normalized.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Markdown document is empty: " + originalName
            );
        }

        String[] lines = normalized.split("\n", -1);
        String[] headings = new String[MAX_HEADING_LEVELS];
        List<ExtractedSegment> segments = new ArrayList<>();
        StringBuilder bodyBuffer = new StringBuilder();

        char activeFenceChar = '\0';
        int activeFenceLen = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (activeFenceChar != '\0') {
                appendLine(bodyBuffer, line);
                if (isClosingFence(line, activeFenceChar, activeFenceLen)) {
                    activeFenceChar = '\0';
                    activeFenceLen = 0;
                }
                continue;
            }

            Matcher fenceMatcher = FENCE_OPEN.matcher(line);
            if (fenceMatcher.matches()) {
                String fenceToken = fenceMatcher.group(1);
                String info = fenceMatcher.group(2);
                char candidateChar = fenceToken.charAt(0);
                if (candidateChar != '`' || info.indexOf('`') < 0) {
                    activeFenceChar = candidateChar;
                    activeFenceLen = fenceToken.length();
                    appendLine(bodyBuffer, line);
                    continue;
                }
            }

            Matcher atxMatcher = ATX_HEADING.matcher(line);
            if (atxMatcher.matches()) {
                flushSegment(segments, bodyBuffer, headings);
                int level = atxMatcher.group(1).length();
                updateHeadings(headings, level - 1, atxMatcher.group(2));
                continue;
            }

            if (i + 1 < lines.length && isSetextTitleCandidate(line)) {
                String nextLine = lines[i + 1];
                int setextLevel = resolveSetextLevel(nextLine);
                if (setextLevel > 0) {
                    flushSegment(segments, bodyBuffer, headings);
                    updateHeadings(headings, setextLevel - 1, line);
                    i++;
                    continue;
                }
            }

            appendLine(bodyBuffer, line);
        }

        flushSegment(segments, bodyBuffer, headings);
        if (segments.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Markdown document contains no extractable body content: " + originalName
            );
        }
        return List.copyOf(segments);
    }

    private static boolean isClosingFence(String line, char fenceChar, int minLen) {
        String trimmed = line.trim();
        if (trimmed.length() < minLen) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != fenceChar) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSetextTitleCandidate(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.startsWith("|") || SETEXT_H1.matcher(line).matches() || SETEXT_H2.matcher(line).matches()) {
            return false;
        }
        return !LIST_OR_QUOTE_PREFIX.matcher(line).matches();
    }

    private static int resolveSetextLevel(String underline) {
        if (SETEXT_H1.matcher(underline).matches()) {
            return 1;
        }
        if (SETEXT_H2.matcher(underline).matches()) {
            return 2;
        }
        return 0;
    }

    private static void updateHeadings(String[] headings, int levelIndex, String rawHeading) {
        String cleanHeading = IngestionTextNormalizer.normalizeText(rawHeading);
        if (cleanHeading.isEmpty()) {
            return;
        }
        headings[levelIndex] = cleanHeading;
        Arrays.fill(headings, levelIndex + 1, headings.length, null);
    }

    private static void appendLine(StringBuilder bodyBuffer, String line) {
        if (bodyBuffer.isEmpty() && line.isBlank()) {
            return;
        }
        if (!bodyBuffer.isEmpty()) {
            bodyBuffer.append('\n');
        }
        bodyBuffer.append(line);
    }

    private static void flushSegment(List<ExtractedSegment> segments, StringBuilder bodyBuffer, String[] headings) {
        if (bodyBuffer.isEmpty()) {
            return;
        }
        String content = IngestionTextNormalizer.normalizeText(bodyBuffer.toString());
        bodyBuffer.setLength(0);
        if (!content.isEmpty()) {
            segments.add(new ExtractedSegment(content, null, IngestionTextNormalizer.formatBreadcrumb(headings)));
        }
    }
}
