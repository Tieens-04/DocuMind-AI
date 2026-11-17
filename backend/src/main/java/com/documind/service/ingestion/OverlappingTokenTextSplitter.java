package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link TokenTextSplitter} extension implementing sliding-window token overlap with UTF-8 multi-byte safety
 * and linguistic boundary snapping for Vietnamese and English enterprise documents.
 */
public class OverlappingTokenTextSplitter extends TokenTextSplitter {

    private static final double BOUNDARY_FLOOR_RATIO = 0.60d;
    private static final int MAX_UTF8_BOUNDARY_ADJUST_TOKENS = 4;
    private static final List<String> SENTENCE_TERMINATORS = List.of(
        ". ", "? ", "! ", "… ", "; ", ".\n", "?\n", "!\n"
    );

    private final Encoding encoding;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int maxChunks;

    public OverlappingTokenTextSplitter() {
        this(
            IngestionProperties.DEFAULT_CHUNK_SIZE,
            IngestionProperties.DEFAULT_CHUNK_OVERLAP,
            IngestionProperties.DEFAULT_MAX_CHUNKS_PER_DOCUMENT
        );
    }

    public OverlappingTokenTextSplitter(int chunkSize, int chunkOverlap, int maxChunks) {
        super(chunkSize, 1, 1, maxChunks, true);
        if (chunkSize < 32) {
            throw new IllegalArgumentException("chunkSize must be >= 32");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize");
        }
        if (maxChunks < 1) {
            throw new IllegalArgumentException("maxChunks must be >= 1");
        }
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.maxChunks = maxChunks;
    }

    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    @Override
    protected List<String> splitText(String text) {
        return splitTextWithBudget(text, chunkSize, chunkOverlap);
    }

    public List<String> splitTextWithBudget(String text, int effectiveChunkSize, int effectiveOverlap) {
        if (effectiveChunkSize < 16) {
            throw new IllegalArgumentException("effectiveChunkSize must be >= 16");
        }
        if (effectiveOverlap < 0 || effectiveOverlap >= effectiveChunkSize) {
            throw new IllegalArgumentException("effectiveOverlap must be >= 0 and < effectiveChunkSize");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = IngestionTextNormalizer.normalizeText(text);
        if (normalized.isEmpty()) {
            return List.of();
        }

        IntArrayList allTokens = encoding.encode(normalized);
        int total = allTokens.size();
        if (total <= effectiveChunkSize) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < total) {
            start = advancePastReplacementPrefix(allTokens, start, total);
            if (start >= total) {
                break;
            }

            int end = Math.min(start + effectiveChunkSize, total);
            end = trimTrailingReplacementTokens(allTokens, start, end, total);

            String windowText = decodeSlice(allTokens, start, end);
            if (windowText.isBlank()) {
                start = end;
                continue;
            }

            int cutTokenEnd = end;
            String chunkText = windowText.trim();

            if (end < total) {
                int cutCharOffset = findNaturalBoundaryOffset(windowText);
                if (cutCharOffset > 0 && cutCharOffset < windowText.length()) {
                    String snappedPrefix = windowText.substring(0, cutCharOffset);
                    String trimmedSnapped = snappedPrefix.trim();
                    if (!trimmedSnapped.isEmpty()) {
                        chunkText = trimmedSnapped;
                        cutTokenEnd = resolveTokenEndForCharLength(allTokens, start, end, cutCharOffset);
                    }
                }
            }

            chunkText = sanitizeReplacementEdges(chunkText);
            while (encoding.countTokens(chunkText) > effectiveChunkSize && cutTokenEnd > start + 1) {
                cutTokenEnd--;
                chunkText = sanitizeReplacementEdges(decodeSlice(allTokens, start, cutTokenEnd).trim());
            }

            if (!chunkText.isEmpty()) {
                if (chunks.size() >= maxChunks) {
                    throw new DocumentExtractionException(
                        DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                        "Chunk count exceeded maximum allowed limit of " + maxChunks
                    );
                }
                chunks.add(chunkText);
            }

            if (cutTokenEnd >= total) {
                break;
            }

            int rawNextStart = Math.max(start + 1, cutTokenEnd - effectiveOverlap);
            int nextStart = snapOverlapStartToCleanBoundary(allTokens, rawNextStart, cutTokenEnd, total);
            if (nextStart <= start) {
                nextStart = Math.min(total, start + Math.max(1, cutTokenEnd - start));
            }
            if (total - nextStart <= effectiveOverlap && cutTokenEnd >= total) {
                break;
            }
            start = nextStart;
        }

        return List.copyOf(chunks);
    }

    private int advancePastReplacementPrefix(IntArrayList allTokens, int start, int total) {
        int candidate = start;
        int limit = Math.min(total, start + MAX_UTF8_BOUNDARY_ADJUST_TOKENS);
        while (candidate < limit) {
            String probe = decodeSlice(allTokens, candidate, Math.min(total, candidate + MAX_UTF8_BOUNDARY_ADJUST_TOKENS));
            if (!probe.startsWith("\uFFFD")) {
                break;
            }
            candidate++;
        }
        return candidate;
    }

    private int trimTrailingReplacementTokens(IntArrayList allTokens, int start, int end, int total) {
        if (end >= total) {
            return end;
        }
        int candidate = end;
        int minEnd = Math.max(start + 1, end - MAX_UTF8_BOUNDARY_ADJUST_TOKENS);
        while (candidate > minEnd) {
            String decoded = decodeSlice(allTokens, start, candidate);
            if (!decoded.endsWith("\uFFFD")) {
                return candidate;
            }
            candidate--;
        }
        return end;
    }

    private int resolveTokenEndForCharLength(IntArrayList allTokens, int start, int maxEnd, int targetChars) {
        int probeEnd = maxEnd;
        while (probeEnd > start + 1) {
            int len = decodeSlice(allTokens, start, probeEnd).length();
            if (len <= targetChars) {
                return probeEnd;
            }
            probeEnd--;
        }
        return start + 1;
    }

    private int snapOverlapStartToCleanBoundary(IntArrayList allTokens, int rawNextStart, int cutTokenEnd, int total) {
        int cleanStart = advancePastReplacementPrefix(allTokens, rawNextStart, total);
        if (cleanStart >= cutTokenEnd) {
            return Math.min(cutTokenEnd, rawNextStart);
        }

        if (cleanStart > 0 && startsOnWordBoundary(allTokens, cleanStart, total)) {
            return cleanStart;
        }

        int searchLimit = cutTokenEnd;
        for (int candidate = cleanStart + 1; candidate < searchLimit; candidate++) {
            String probe = decodeSlice(allTokens, candidate, Math.min(total, candidate + MAX_UTF8_BOUNDARY_ADJUST_TOKENS));
            if (probe.startsWith("\uFFFD")) {
                continue;
            }
            if (startsOnWordBoundary(allTokens, candidate, total)) {
                return candidate;
            }
        }
        return cleanStart;
    }

    private boolean startsOnWordBoundary(IntArrayList allTokens, int tokenIndex, int total) {
        String current = decodeSlice(allTokens, tokenIndex, Math.min(total, tokenIndex + 1));
        if (!current.isEmpty() && Character.isWhitespace(current.charAt(0))) {
            return true;
        }
        if (tokenIndex <= 0) {
            return true;
        }
        String previous = decodeSlice(allTokens, tokenIndex - 1, tokenIndex);
        return !previous.isEmpty() && Character.isWhitespace(previous.charAt(previous.length() - 1));
    }

    private static int findNaturalBoundaryOffset(String windowText) {
        int len = windowText.length();
        int floor = (int) (len * BOUNDARY_FLOOR_RATIO);

        int paragraphBreak = windowText.lastIndexOf("\n\n");
        if (paragraphBreak >= floor) {
            return paragraphBreak;
        }

        int lineBreak = windowText.lastIndexOf('\n');
        if (lineBreak >= floor) {
            return lineBreak;
        }

        int bestSentenceCut = -1;
        for (String terminator : SENTENCE_TERMINATORS) {
            int idx = windowText.lastIndexOf(terminator);
            if (idx >= floor) {
                bestSentenceCut = Math.max(bestSentenceCut, idx + 1);
            }
        }
        if (bestSentenceCut >= floor) {
            return bestSentenceCut;
        }

        int lastSpace = windowText.lastIndexOf(' ');
        int lastTab = windowText.lastIndexOf('\t');
        int whitespace = Math.max(lastSpace, lastTab);
        if (whitespace >= floor) {
            return whitespace;
        }

        return -1;
    }

    private String decodeSlice(IntArrayList allTokens, int startInclusive, int endExclusive) {
        if (startInclusive >= endExclusive) {
            return "";
        }
        IntArrayList slice = new IntArrayList(endExclusive - startInclusive);
        for (int i = startInclusive; i < endExclusive; i++) {
            slice.add(allTokens.get(i));
        }
        return encoding.decode(slice);
    }

    private static String sanitizeReplacementEdges(String value) {
        if (value.indexOf('\uFFFD') < 0) {
            return value;
        }
        return value.replace("\uFFFD", "").trim();
    }
}
