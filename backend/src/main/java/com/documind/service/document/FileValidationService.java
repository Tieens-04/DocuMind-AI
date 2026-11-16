package com.documind.service.document;

import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.exception.UnsupportedDocumentTypeException;
import com.documind.infrastructure.storage.HashingBoundedInputStream;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Validates document filenames, declared sizes, and magic signatures via a bounded {@code 64 KiB} header prefix
 * so upload streams rewind cleanly without full-file buffering. Full-stream ZIP-bomb inflation ratio limits
 * ({@code ZipSecureFile}) are enforced downstream when {@code DA-8} parses OOXML content.
 */
@Service
public class FileValidationService {

    private static final Logger log = LoggerFactory.getLogger(FileValidationService.class);
    public static final int HEADER_INSPECTION_BYTES = 65_536;
    private static final int CONTENT_TYPES_MAX_INSPECTION_BYTES = 32_768;

    private static final int MAX_ORIGINAL_NAME_LENGTH = 255;
    private static final int MAX_SANITIZED_NAME_LENGTH = 180;
    private static final String FALLBACK_STEM = "document";

    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_DOCX =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String MIME_TXT = "text/plain";
    private static final String MIME_MD = "text/markdown";
    private static final String MIME_OCTET_STREAM = "application/octet-stream";

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");
    private static final Set<String> ALLOWED_DOCX_TIKA_MIMES = Set.of(
        "application/zip",
        "application/x-tika-ooxml",
        MIME_DOCX
    );
    private static final Set<String> ALLOWED_TEXTUAL_APPLICATION_MIMES = Set.of(
        "application/xml",
        "application/xhtml+xml",
        "application/json",
        "application/ld+json",
        "application/x-sh",
        "application/x-csh",
        "application/javascript",
        "application/ecmascript",
        "application/sql",
        "application/yaml",
        "application/x-yaml",
        "application/toml"
    );

    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\p{Cntrl}/\\\\#?%\\s]+");
    private static final Pattern MULTIPLE_DOTS = Pattern.compile("\\.{2,}");

    private final Detector detector = TikaConfig.getDefaultConfig().getDetector();

    public FileValidationResult validateAndPrepareStream(
        String rawOriginalFilename,
        long declaredSizeBytes,
        InputStream rawStream
    ) {
        String originalName = normalizeAndValidateOriginalName(rawOriginalFilename);
        int lastDot = validateAndFindExtensionDot(originalName);
        String fileExtension = extractValidatedExtension(originalName, lastDot);
        String sanitizedFileName = buildSanitizedFileName(originalName.substring(0, lastDot), fileExtension);

        validateDeclaredSize(declaredSizeBytes);
        if (rawStream == null) {
            throw new InvalidDocumentRequestException("Document stream must not be null");
        }

        BufferedInputStream rewindedStream = new BufferedInputStream(rawStream, HEADER_INSPECTION_BYTES);
        byte[] headerBytes = readAndRewindHeader(rewindedStream);
        if (headerBytes.length == 0) {
            throw new InvalidDocumentRequestException("Uploaded document stream must not be empty (0 bytes)");
        }

        MediaType detectedMediaType = detectHeaderMediaType(headerBytes);
        String canonicalMimeType = verifyMagicAndResolveCanonicalMime(fileExtension, headerBytes, detectedMediaType);

        return new FileValidationResult(
            originalName,
            sanitizedFileName,
            fileExtension,
            canonicalMimeType,
            declaredSizeBytes,
            rewindedStream
        );
    }

    private static String normalizeAndValidateOriginalName(String rawOriginalFilename) {
        if (rawOriginalFilename == null || rawOriginalFilename.isBlank()) {
            throw new InvalidDocumentRequestException("Original filename must not be blank");
        }
        int lastSeparator = Math.max(rawOriginalFilename.lastIndexOf('/'), rawOriginalFilename.lastIndexOf('\\'));
        String basename = (lastSeparator >= 0)
            ? rawOriginalFilename.substring(lastSeparator + 1)
            : rawOriginalFilename;
        String originalName = basename.trim();

        if (originalName.isBlank() || originalName.indexOf('\0') >= 0) {
            throw new InvalidDocumentRequestException("Original filename must not be blank or contain null bytes");
        }
        if (".".equals(originalName) || "..".equals(originalName)) {
            throw new InvalidDocumentRequestException("Invalid filename: " + originalName);
        }
        if (originalName.length() > MAX_ORIGINAL_NAME_LENGTH) {
            throw new InvalidDocumentRequestException(
                "Original filename must not exceed " + MAX_ORIGINAL_NAME_LENGTH + " characters"
            );
        }
        return originalName;
    }

    private static int validateAndFindExtensionDot(String originalName) {
        int lastDot = originalName.lastIndexOf('.');
        if (lastDot == 0 || (lastDot > 0 && originalName.substring(0, lastDot).replace(".", "").isBlank())) {
            throw new InvalidDocumentRequestException(
                "Filename must include a non-blank name before the extension: " + originalName
            );
        }
        if (lastDot < 0 || lastDot == originalName.length() - 1) {
            throw new UnsupportedDocumentTypeException(
                MIME_OCTET_STREAM,
                "Missing file extension in filename: " + originalName
            );
        }
        return lastDot;
    }

    private static String extractValidatedExtension(String originalName, int lastDot) {
        String extension = originalName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new UnsupportedDocumentTypeException(
                MIME_OCTET_STREAM,
                "Unsupported document extension: ." + extension
            );
        }
        return extension;
    }

    private static String buildSanitizedFileName(String rawStem, String fileExtension) {
        String sanitizedStem = UNSAFE_FILENAME_CHARS.matcher(rawStem).replaceAll("_");
        sanitizedStem = MULTIPLE_DOTS.matcher(sanitizedStem).replaceAll("_");
        sanitizedStem = stripLeadingAndTrailingDots(sanitizedStem);
        if (sanitizedStem.isEmpty()) {
            sanitizedStem = FALLBACK_STEM;
        }

        int maxStemLength = MAX_SANITIZED_NAME_LENGTH - 1 - fileExtension.length();
        if (sanitizedStem.length() > maxStemLength) {
            int cut = maxStemLength;
            if (Character.isHighSurrogate(sanitizedStem.charAt(cut - 1))) {
                cut--;
            }
            sanitizedStem = stripLeadingAndTrailingDots(sanitizedStem.substring(0, cut));
            if (sanitizedStem.isEmpty()) {
                sanitizedStem = FALLBACK_STEM;
            }
        }
        return sanitizedStem + "." + fileExtension;
    }

    private static String stripLeadingAndTrailingDots(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && input.charAt(start) == '.') {
            start++;
        }
        while (end > start && input.charAt(end - 1) == '.') {
            end--;
        }
        return input.substring(start, end);
    }

    private static void validateDeclaredSize(long declaredSizeBytes) {
        if (declaredSizeBytes <= 0) {
            throw new InvalidDocumentRequestException("Declared file size must be greater than 0 bytes");
        }
        if (declaredSizeBytes > HashingBoundedInputStream.MAX_FILE_SIZE_BYTES) {
            throw new DocumentTooLargeException(
                HashingBoundedInputStream.MAX_FILE_SIZE_BYTES,
                "Document exceeds maximum allowed size of " + HashingBoundedInputStream.MAX_FILE_SIZE_BYTES + " bytes"
            );
        }
    }

    private static byte[] readAndRewindHeader(BufferedInputStream rewindedStream) {
        try {
            rewindedStream.mark(HEADER_INSPECTION_BYTES);
            byte[] headerBytes = rewindedStream.readNBytes(HEADER_INSPECTION_BYTES);
            rewindedStream.reset();
            return headerBytes;
        } catch (DocumentTooLargeException | InvalidDocumentRequestException ex) {
            throw ex;
        } catch (IOException ex) {
            log.warn("Failed to read uploaded document header bytes", ex);
            throw new InvalidDocumentRequestException("Failed to read uploaded document header");
        }
    }

    private MediaType detectHeaderMediaType(byte[] headerBytes) {
        try (TikaInputStream tikaStream = TikaInputStream.get(headerBytes)) {
            return detector.detect(tikaStream, new Metadata());
        } catch (IOException ex) {
            log.warn("Failed to detect MIME type from uploaded document header", ex);
            throw new UnsupportedDocumentTypeException(
                MIME_OCTET_STREAM,
                "Failed to detect MIME type from document header"
            );
        }
    }

    private String verifyMagicAndResolveCanonicalMime(
        String fileExtension,
        byte[] headerBytes,
        MediaType detectedMediaType
    ) {
        String baseMimeType = detectedMediaType.getBaseType().toString();
        return switch (fileExtension) {
            case "pdf" -> {
                if (!hasPdfMagicPrefix(headerBytes) || !MIME_PDF.equals(baseMimeType)) {
                    throw new UnsupportedDocumentTypeException(
                        baseMimeType,
                        "Document content does not match PDF signature; detected " + baseMimeType
                    );
                }
                yield MIME_PDF;
            }
            case "docx" -> {
                if (!hasZipLocalHeaderPrefix(headerBytes) || !ALLOWED_DOCX_TIKA_MIMES.contains(baseMimeType)) {
                    throw new UnsupportedDocumentTypeException(
                        baseMimeType,
                        "Document content does not match OOXML/ZIP signature; detected " + baseMimeType
                    );
                }
                validateOoxmlContainerHeader(headerBytes, baseMimeType);
                yield MIME_DOCX;
            }
            case "txt" -> {
                validatePlainTextHeader(headerBytes, detectedMediaType, baseMimeType);
                yield MIME_TXT;
            }
            case "md" -> {
                validatePlainTextHeader(headerBytes, detectedMediaType, baseMimeType);
                yield MIME_MD;
            }
            default -> throw new UnsupportedDocumentTypeException(
                baseMimeType,
                "Unsupported document extension: ." + fileExtension
            );
        };
    }

    private static boolean hasPdfMagicPrefix(byte[] bytes) {
        return bytes.length >= 5
            && bytes[0] == '%'
            && bytes[1] == 'P'
            && bytes[2] == 'D'
            && bytes[3] == 'F'
            && bytes[4] == '-';
    }

    private static boolean hasZipLocalHeaderPrefix(byte[] bytes) {
        return bytes.length >= 4
            && bytes[0] == 'P'
            && bytes[1] == 'K'
            && bytes[2] == 0x03
            && bytes[3] == 0x04;
    }

    private static void validatePlainTextHeader(
        byte[] headerBytes,
        MediaType detectedMediaType,
        String baseMimeType
    ) {
        for (byte b : headerBytes) {
            if (b == 0x00) {
                throw new UnsupportedDocumentTypeException(
                    baseMimeType,
                    "Binary null byte detected in text/markdown upload"
                );
            }
        }
        if (!"text".equals(detectedMediaType.getType()) && !ALLOWED_TEXTUAL_APPLICATION_MIMES.contains(baseMimeType)) {
            throw new UnsupportedDocumentTypeException(
                baseMimeType,
                "Non-text MIME type detected for text/markdown upload: " + baseMimeType
            );
        }
    }

    private static void validateOoxmlContainerHeader(byte[] headerBytes, String baseMimeType) {
        boolean truncatedWindow = headerBytes.length == HEADER_INSPECTION_BYTES;
        ByteArrayInputStream bais = new ByteArrayInputStream(headerBytes);
        boolean foundWordDocumentMarker = false;
        int entriesSeen = 0;

        try (ZipInputStream zis = new ZipInputStream(bais, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entriesSeen++;
                String entryName = entry.getName();
                if (hasDotDotPathSegment(entryName)) {
                    log.warn("Rejected ZIP path-traversal entry '{}' in DOCX upload", entryName);
                    throw new UnsupportedDocumentTypeException(
                        baseMimeType,
                        "Rejected ZIP path-traversal entry in DOCX upload"
                    );
                }
                String normalized = normalizeZipEntryName(entryName);
                if (normalized.startsWith("xl/") || normalized.startsWith("ppt/")) {
                    throw new UnsupportedDocumentTypeException(
                        baseMimeType,
                        "Non-Word OOXML container rejected for .docx upload"
                    );
                }
                if (normalized.startsWith("word/")) {
                    foundWordDocumentMarker = true;
                } else if ("[Content_Types].xml".equals(normalized)) {
                    byte[] contentTypesPrefix = readBoundedEntryPrefix(
                        zis,
                        CONTENT_TYPES_MAX_INSPECTION_BYTES,
                        truncatedWindow,
                        bais
                    );
                    String xmlLower = new String(contentTypesPrefix, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    boolean hasOtherOoxml = xmlLower.contains("spreadsheetml") || xmlLower.contains("presentationml");
                    boolean hasWordProcessing =
                        xmlLower.contains("wordprocessingml.document") || xmlLower.contains("/word/");
                    if (hasOtherOoxml && !hasWordProcessing) {
                        throw new UnsupportedDocumentTypeException(
                            baseMimeType,
                            "Non-Word OOXML container rejected for .docx upload"
                        );
                    }
                    if (hasWordProcessing) {
                        foundWordDocumentMarker = true;
                    }
                }
            }
        } catch (UnsupportedDocumentTypeException ex) {
            throw ex;
        } catch (EOFException | ZipException ex) {
            if (!(truncatedWindow && entriesSeen > 0 && bais.available() == 0)) {
                log.warn("Corrupt ZIP container in DOCX upload", ex);
                throw new UnsupportedDocumentTypeException(
                    baseMimeType,
                    "Corrupt ZIP container in DOCX upload"
                );
            }
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Invalid ZIP container in DOCX upload", ex);
            throw new UnsupportedDocumentTypeException(
                baseMimeType,
                "Invalid ZIP container in DOCX upload"
            );
        }

        if (entriesSeen == 0 || !foundWordDocumentMarker) {
            throw new UnsupportedDocumentTypeException(
                baseMimeType,
                "ZIP container does not contain valid OOXML Word document entries"
            );
        }
    }

    private static byte[] readBoundedEntryPrefix(
        ZipInputStream zis,
        int maxBytes,
        boolean truncatedWindow,
        ByteArrayInputStream backingStream
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        byte[] buffer = new byte[4096];
        int remaining = maxBytes;
        try {
            while (remaining > 0) {
                int read = zis.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (EOFException | ZipException ex) {
            if (!(truncatedWindow && backingStream.available() == 0)) {
                throw ex;
            }
        }
        return out.toByteArray();
    }

    private static boolean hasDotDotPathSegment(String rawEntryName) {
        String normalized = rawEntryName.replace('\\', '/');
        int start = 0;
        while (start <= normalized.length()) {
            int slash = normalized.indexOf('/', start);
            String segment = (slash >= 0) ? normalized.substring(start, slash) : normalized.substring(start);
            if ("..".equals(segment)) {
                return true;
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        return false;
    }

    private static String normalizeZipEntryName(String rawEntryName) {
        String normalized = rawEntryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
