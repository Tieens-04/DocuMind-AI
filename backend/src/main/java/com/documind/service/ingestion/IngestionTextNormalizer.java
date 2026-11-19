package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Normalizes extracted Vietnamese text into precomposed NFC form and enforces byte/character bounds on streams.
 */
final class IngestionTextNormalizer {

    private static final int READ_BUFFER_CHARS = 8_192;
    private static final int BOM_PROBE_BYTES = 4;
    private static final Pattern TRAILING_LINE_WHITESPACE = Pattern.compile("[ \\t\\x0B\\f]+(?=\n|$)");
    private static final Pattern EXCESS_NEWLINES = Pattern.compile("\n{3,}");

    private IngestionTextNormalizer() {
    }

    static String readBoundedUtf8(InputStream inputStream, long maxBytes, int maxChars, String originalName) {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        String displayName = (originalName == null || originalName.isBlank()) ? "document" : originalName;

        try (
            InputStream boundedBytes = new ByteLimitedInputStream(inputStream, maxBytes, displayName);
            PushbackInputStream pushback = new PushbackInputStream(boundedBytes, BOM_PROBE_BYTES)
        ) {
            Charset charset = detectBomAndConsume(pushback);
            try (InputStreamReader reader = new InputStreamReader(
                pushback,
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            )) {
                StringBuilder out = new StringBuilder();
                char[] buffer = new char[READ_BUFFER_CHARS];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    if (out.length() + read > maxChars) {
                        throw new DocumentExtractionException(
                            DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                            "Extracted character count exceeded limit of " + maxChars + " for " + displayName
                        );
                    }
                    out.append(buffer, 0, read);
                }
                if (out.indexOf("\0") >= 0) {
                    throw new DocumentExtractionException(
                        DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                        "Binary null byte detected in extracted document text"
                    );
                }
                return normalizeRawText(out.toString(), maxChars, displayName);
            }
        } catch (DocumentExtractionException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Failed to read text from document: " + displayName,
                ex
            );
        }
    }

    private static Charset detectBomAndConsume(PushbackInputStream pushback) throws IOException {
        byte[] bom = new byte[BOM_PROBE_BYTES];
        int n = 0;
        while (n < bom.length) {
            int r = pushback.read(bom, n, bom.length - n);
            if (r == -1) {
                break;
            }
            n += r;
        }
        if (n >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
            if (n > 3) {
                pushback.unread(bom, 3, n - 3);
            }
            return StandardCharsets.UTF_8;
        }
        if (n >= 2 && bom[0] == (byte) 0xFE && bom[1] == (byte) 0xFF) {
            if (n > 2) {
                pushback.unread(bom, 2, n - 2);
            }
            return StandardCharsets.UTF_16BE;
        }
        if (n >= 2 && bom[0] == (byte) 0xFF && bom[1] == (byte) 0xFE
            && !(n == 4 && bom[2] == 0x00 && bom[3] == 0x00)) {
            if (n > 2) {
                pushback.unread(bom, 2, n - 2);
            }
            return StandardCharsets.UTF_16LE;
        }
        if (n > 0) {
            pushback.unread(bom, 0, n);
        }
        return StandardCharsets.UTF_8;
    }

    static String normalizeRawText(String raw, int maxChars, String originalName) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String displayName = (originalName == null || originalName.isBlank()) ? "document" : originalName;
        if (raw.length() > maxChars) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                "Extracted character count exceeded limit of " + maxChars + " for " + displayName
            );
        }
        return normalizeText(raw);
    }

    static String normalizeText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        String cleaned = raw.indexOf('\0') >= 0 ? raw.replace("\0", "") : raw;
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1);
        }
        cleaned = cleaned
            .replace("\uFEFF", "")
            .replace("\u00AD", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n');

        if (!Normalizer.isNormalized(cleaned, Normalizer.Form.NFC)) {
            cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC);
        }

        cleaned = TRAILING_LINE_WHITESPACE.matcher(cleaned).replaceAll("");
        cleaned = EXCESS_NEWLINES.matcher(cleaned).replaceAll("\n\n");
        return cleaned.trim();
    }

    static String formatBreadcrumb(String[] levels) {
        StringBuilder sb = new StringBuilder();
        for (String level : levels) {
            if (level != null && !level.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" > ");
                }
                sb.append(level.trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static final class ByteLimitedInputStream extends FilterInputStream {

        private final long maxBytes;
        private final String displayName;
        private long bytesRead;

        private ByteLimitedInputStream(InputStream delegate, long maxBytes, String displayName) {
            super(delegate);
            this.maxBytes = maxBytes;
            this.displayName = displayName;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                incrementAndCheck(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                incrementAndCheck(read);
            }
            return read;
        }

        private void incrementAndCheck(int delta) {
            bytesRead += delta;
            if (bytesRead > maxBytes) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                    "Document stream exceeded maximum size of " + maxBytes + " bytes for " + displayName
                );
            }
        }
    }
}
