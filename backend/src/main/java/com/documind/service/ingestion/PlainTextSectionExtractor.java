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
 * Extracts plain-text documents into structural Vietnamese sections when formal heading patterns exist, or a single flat segment otherwise.
 */
@Component
public class PlainTextSectionExtractor {

    private static final int MAX_HEADING_LENGTH = 140;
    private static final int MAX_HEADING_LEVELS = 3;

    private static final Pattern LEVEL_1_HEADING = Pattern.compile(
        "^(?iu)(phần|chương)\\s+[ivxlcdm\\d]+([.:\\-–—\\s].*)?$"
    );
    private static final Pattern LEVEL_2_KEYWORD_HEADING = Pattern.compile(
        "^(?iu)(mục|điều)\\s+\\d+([.:\\-–—\\s].*)?$"
    );
    private static final Pattern LEVEL_2_ROMAN_HEADING = Pattern.compile(
        "^([IVX]+)\\.\\s+\\S+.*$"
    );
    private static final Pattern LEVEL_3_DECIMAL_HEADING = Pattern.compile(
        "^\\d+\\.\\d+(?:\\.\\d+)?\\.?\\s+\\p{Lu}.*$"
    );
    private static final Pattern LEVEL_3_INTEGER_HEADING = Pattern.compile(
        "^(\\d+)\\.\\s+\\p{Lu}.*$"
    );
    private static final Pattern INTEGER_LIST_ITEM = Pattern.compile(
        "^(\\d+)[.)]\\s+\\S+.*$"
    );
    private static final Pattern ROMAN_LIST_ITEM = Pattern.compile(
        "^([IVX]+)\\.\\s+\\S+.*$"
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
        boolean[] dirtyHeadings = new boolean[MAX_HEADING_LEVELS];
        List<ExtractedSegment> segments = new ArrayList<>();
        StringBuilder bodyBuffer = new StringBuilder();

        boolean previousLineBlank = true;
        int previousHeadingLevel = -1;
        int activeIntListSeq = 0;
        int expectedNextIntHeading = 1;
        int activeRomanListSeq = 0;
        int expectedNextRomanHeading = 1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!bodyBuffer.isEmpty()) {
                    bodyBuffer.append('\n');
                }
                previousLineBlank = true;
                previousHeadingLevel = -1;
                continue;
            }

            int acceptedLevel = -1;
            if (hasHeadingShape(trimmed)) {
                if (LEVEL_1_HEADING.matcher(trimmed).matches()) {
                    if (previousLineBlank || previousHeadingLevel >= 0) {
                        acceptedLevel = 0;
                        expectedNextRomanHeading = 1;
                        expectedNextIntHeading = 1;
                    }
                } else if (LEVEL_2_KEYWORD_HEADING.matcher(trimmed).matches()) {
                    if (previousLineBlank || previousHeadingLevel >= 0) {
                        acceptedLevel = 1;
                        expectedNextIntHeading = 1;
                    }
                } else if (LEVEL_3_DECIMAL_HEADING.matcher(trimmed).matches()) {
                    if ((previousLineBlank || (previousHeadingLevel >= 0 && previousHeadingLevel < 2))
                        && hasValidDecimalLookahead(lines, i + 1)) {
                        acceptedLevel = 2;
                    }
                } else {
                    Matcher intMatcher = LEVEL_3_INTEGER_HEADING.matcher(trimmed);
                    if (intMatcher.matches()) {
                        int n = parsePositiveIntSafe(intMatcher.group(1));
                        if (n > 0
                            && previousLineBlank
                            && !(activeIntListSeq > 0 && n == activeIntListSeq + 1)
                            && n == expectedNextIntHeading
                            && hasValidIntegerLookahead(lines, i + 1, n)) {
                            acceptedLevel = 2;
                            expectedNextIntHeading = n + 1;
                        }
                    } else {
                        Matcher romanMatcher = LEVEL_2_ROMAN_HEADING.matcher(trimmed);
                        if (romanMatcher.matches()) {
                            int r = parseRomanNumeral(romanMatcher.group(1));
                            if (r > 0
                                && previousLineBlank
                                && !(activeRomanListSeq > 0 && r == activeRomanListSeq + 1)
                                && r == expectedNextRomanHeading
                                && hasValidRomanLookahead(lines, i + 1, r)) {
                                acceptedLevel = 1;
                                expectedNextRomanHeading = r + 1;
                                expectedNextIntHeading = 1;
                            }
                        }
                    }
                }
            }

            if (acceptedLevel >= 0) {
                flushSegment(segments, bodyBuffer, headings, dirtyHeadings);
                flushOrphanedHeadingIfNeeded(segments, headings, dirtyHeadings, acceptedLevel);
                headings[acceptedLevel] = trimmed;
                Arrays.fill(headings, acceptedLevel + 1, headings.length, null);
                dirtyHeadings[acceptedLevel] = true;
                previousLineBlank = false;
                previousHeadingLevel = acceptedLevel;
                activeIntListSeq = 0;
                activeRomanListSeq = 0;
                continue;
            }

            if (!bodyBuffer.isEmpty()) {
                bodyBuffer.append('\n');
            }
            bodyBuffer.append(line);

            int intItem = matchIntegerListItem(trimmed);
            activeIntListSeq = (intItem > 0 && (intItem == 1 || intItem == activeIntListSeq + 1)) ? intItem : 0;

            int romanItem = matchRomanListItem(trimmed);
            activeRomanListSeq = (romanItem > 0 && (romanItem == 1 || romanItem == activeRomanListSeq + 1))
                ? romanItem
                : 0;

            previousLineBlank = false;
            previousHeadingLevel = -1;
        }

        flushSegment(segments, bodyBuffer, headings, dirtyHeadings);
        flushOrphanedHeadingIfNeeded(segments, headings, dirtyHeadings, 0);
        if (segments.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Text document contains no extractable body content: " + originalName
            );
        }
        return List.copyOf(segments);
    }

    private static boolean hasHeadingShape(String trimmedLine) {
        if (trimmedLine.length() > MAX_HEADING_LENGTH || trimmedLine.isEmpty()) {
            return false;
        }
        char lastChar = trimmedLine.charAt(trimmedLine.length() - 1);
        return lastChar != '.'
            && lastChar != '?'
            && lastChar != '!'
            && lastChar != ','
            && lastChar != ';'
            && lastChar != ':';
    }

    private static int findNextNonBlankLine(String[] lines, int startIndex) {
        for (int i = startIndex; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasValidDecimalLookahead(String[] lines, int startIndex) {
        int nextIdx = findNextNonBlankLine(lines, startIndex);
        if (nextIdx < 0) {
            return false;
        }
        String nextTrimmed = lines[nextIdx].trim();
        if (!hasHeadingShape(nextTrimmed)) {
            return true;
        }
        return !LEVEL_3_DECIMAL_HEADING.matcher(nextTrimmed).matches()
            && !isLevel0Or1HeadingCandidate(nextTrimmed);
    }

    private static boolean hasValidIntegerLookahead(String[] lines, int startIndex, int n) {
        int nextIdx = findNextNonBlankLine(lines, startIndex);
        if (nextIdx < 0) {
            return false;
        }
        String nextTrimmed = lines[nextIdx].trim();
        if (matchIntegerListItem(nextTrimmed) == n + 1) {
            return false;
        }
        return !hasHeadingShape(nextTrimmed)
            || (!isLevel0Or1HeadingCandidate(nextTrimmed)
                && !LEVEL_3_DECIMAL_HEADING.matcher(nextTrimmed).matches()
                && !LEVEL_3_INTEGER_HEADING.matcher(nextTrimmed).matches());
    }

    private static boolean hasValidRomanLookahead(String[] lines, int startIndex, int r) {
        int nextIdx = findNextNonBlankLine(lines, startIndex);
        if (nextIdx < 0) {
            return false;
        }
        String nextTrimmed = lines[nextIdx].trim();
        if (matchRomanListItem(nextTrimmed) == r + 1) {
            return false;
        }
        return !hasHeadingShape(nextTrimmed) || !isLevel0Or1HeadingCandidate(nextTrimmed);
    }

    private static boolean isLevel0Or1HeadingCandidate(String trimmed) {
        if (LEVEL_1_HEADING.matcher(trimmed).matches() || LEVEL_2_KEYWORD_HEADING.matcher(trimmed).matches()) {
            return true;
        }
        Matcher roman = LEVEL_2_ROMAN_HEADING.matcher(trimmed);
        return roman.matches() && parseRomanNumeral(roman.group(1)) > 0;
    }

    private static int matchIntegerListItem(String trimmed) {
        Matcher m = INTEGER_LIST_ITEM.matcher(trimmed);
        return m.matches() ? parsePositiveIntSafe(m.group(1)) : -1;
    }

    private static int matchRomanListItem(String trimmed) {
        Matcher m = ROMAN_LIST_ITEM.matcher(trimmed);
        return m.matches() ? parseRomanNumeral(m.group(1)) : -1;
    }

    private static int parsePositiveIntSafe(String digits) {
        if (digits == null || digits.isEmpty() || digits.length() > 9) {
            return -1;
        }
        try {
            int val = Integer.parseInt(digits);
            return val > 0 ? val : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static int parseRomanNumeral(String token) {
        if (token == null || token.isEmpty()) {
            return -1;
        }
        int total = 0;
        int prev = 0;
        for (int i = token.length() - 1; i >= 0; i--) {
            int val = switch (token.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> -1;
            };
            if (val < 0) {
                return -1;
            }
            if (val < prev) {
                total -= val;
            } else {
                total += val;
                prev = val;
            }
        }
        if (total < 1 || total > 39 || !toRoman(total).equals(token)) {
            return -1;
        }
        return total;
    }

    private static String toRoman(int number) {
        int[] values = {10, 9, 5, 4, 1};
        String[] numerals = {"X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int remaining = number;
        for (int i = 0; i < values.length; i++) {
            while (remaining >= values[i]) {
                sb.append(numerals[i]);
                remaining -= values[i];
            }
        }
        return sb.toString();
    }

    private static void flushSegment(
        List<ExtractedSegment> segments,
        StringBuilder bodyBuffer,
        String[] headings,
        boolean[] dirtyHeadings
    ) {
        if (bodyBuffer.isEmpty()) {
            return;
        }
        String content = IngestionTextNormalizer.normalizeText(bodyBuffer.toString());
        bodyBuffer.setLength(0);
        if (!content.isEmpty()) {
            segments.add(new ExtractedSegment(content, null, IngestionTextNormalizer.formatBreadcrumb(headings)));
            Arrays.fill(dirtyHeadings, false);
        }
    }

    private static void flushOrphanedHeadingIfNeeded(
        List<ExtractedSegment> segments,
        String[] headings,
        boolean[] dirtyHeadings,
        int targetLevel
    ) {
        int deepestDirty = -1;
        for (int k = targetLevel; k < dirtyHeadings.length; k++) {
            if (dirtyHeadings[k] && headings[k] != null && !headings[k].isBlank()) {
                deepestDirty = k;
            }
        }
        if (deepestDirty >= 0) {
            String parentBreadcrumb = IngestionTextNormalizer.formatBreadcrumb(Arrays.copyOf(headings, deepestDirty));
            segments.add(new ExtractedSegment(headings[deepestDirty], null, parentBreadcrumb));
            Arrays.fill(dirtyHeadings, false);
        }
    }
}
