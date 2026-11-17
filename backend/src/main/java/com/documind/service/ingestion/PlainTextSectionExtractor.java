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
import java.util.regex.Pattern;

/**
 * Extracts plain-text documents into structural Vietnamese sections when formal heading patterns exist, or a single flat segment otherwise.
 */
@Component
public class PlainTextSectionExtractor {

    private static final int MAX_HEADING_LENGTH = 140;
    private static final int MAX_HEADING_LEVELS = 3;

    private static final Pattern LEVEL_1_HEADING = Pattern.compile(
        "^(?iu)(phần|chương)\\s+[ivxlcdm\\d]+([.:\\-–—\\s].*)?$"
    );
    private static final Pattern LEVEL_2_HEADING = Pattern.compile(
        "^(?iu)(mục|điều)\\s+\\d+([.:\\-–—\\s].*)?$|^[IVX]+\\.\\s+\\S+.*$"
    );
    private static final Pattern LEVEL_3_HEADING = Pattern.compile(
        "^(\\d+\\.\\d+(?:\\.\\d+)?\\.?|\\d+\\.)\\s+\\p{Lu}.*$"
    );

    private final IngestionProperties properties;

    public PlainTextSectionExtractor() {
        this(IngestionProperties.defaults());
    }

    @Autowired
    public PlainTextSectionExtractor(IngestionProperties properties) {
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

    public List<ExtractedSegment> extractFromText(String rawText, String originalName) {
        if (rawText == null) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Text document contains no extractable text: " + originalName
            );
        }
        String normalized = IngestionTextNormalizer.normalizeRawText(
            rawText,
            properties.extraction().maxExtractedChars(),
            originalName
        );
        return extractNormalized(normalized, originalName);
    }

    private static List<ExtractedSegment> extractNormalized(String normalized, String originalName) {
        if (normalized.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Text document is empty: " + originalName
            );
        }

        String[] lines = normalized.split("\n", -1);
        String[] headings = new String[MAX_HEADING_LEVELS];
        List<ExtractedSegment> segments = new ArrayList<>();
        StringBuilder bodyBuffer = new StringBuilder();
        boolean previousBlankOrHeading = true;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!bodyBuffer.isEmpty()) {
                    bodyBuffer.append('\n');
                }
                previousBlankOrHeading = true;
                continue;
            }

            int headingLevel = previousBlankOrHeading ? detectHeadingLevel(trimmed) : -1;
            if (headingLevel >= 0) {
                flushSegment(segments, bodyBuffer, headings);
                headings[headingLevel] = trimmed;
                Arrays.fill(headings, headingLevel + 1, headings.length, null);
                previousBlankOrHeading = true;
                continue;
            }

            if (!bodyBuffer.isEmpty()) {
                bodyBuffer.append('\n');
            }
            bodyBuffer.append(line);
            previousBlankOrHeading = false;
        }

        flushSegment(segments, bodyBuffer, headings);
        if (segments.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Text document contains no extractable body content: " + originalName
            );
        }
        return List.copyOf(segments);
    }

    private static int detectHeadingLevel(String trimmedLine) {
        if (trimmedLine.length() > MAX_HEADING_LENGTH) {
            return -1;
        }
        char lastChar = trimmedLine.charAt(trimmedLine.length() - 1);
        if (lastChar == '.' || lastChar == '?' || lastChar == '!' || lastChar == ',' || lastChar == ';') {
            return -1;
        }
        if (LEVEL_1_HEADING.matcher(trimmedLine).matches()) {
            return 0;
        }
        if (LEVEL_2_HEADING.matcher(trimmedLine).matches()) {
            return 1;
        }
        if (LEVEL_3_HEADING.matcher(trimmedLine).matches()) {
            return 2;
        }
        return -1;
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
