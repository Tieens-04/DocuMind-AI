package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import com.documind.exception.DocumentStorageException;
import com.documind.infrastructure.storage.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Dispatches multi-format document extraction across {@code .pdf}, {@code .docx}, {@code .md}, and {@code .txt}
 * using hybrid temp-file spooling for binary containers and direct UTF-8 streaming for text formats.
 */
@Service
public class CompositeDocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(CompositeDocumentExtractor.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "docx", "md", "txt");
    private static final int SPOOL_BUFFER_BYTES = 8_192;

    private final MinioStorageService minioStorageService;
    private final IngestionProperties properties;
    private final PdfDocumentExtractor pdfDocumentExtractor;
    private final DocxDocumentExtractor docxDocumentExtractor;
    private final MarkdownSectionExtractor markdownSectionExtractor;
    private final PlainTextSectionExtractor plainTextSectionExtractor;
    private final Path tempDirectory;

    @Autowired
    public CompositeDocumentExtractor(
        MinioStorageService minioStorageService,
        IngestionProperties properties,
        PdfDocumentExtractor pdfDocumentExtractor,
        DocxDocumentExtractor docxDocumentExtractor,
        MarkdownSectionExtractor markdownSectionExtractor,
        PlainTextSectionExtractor plainTextSectionExtractor
    ) {
        this(
            minioStorageService,
            properties,
            pdfDocumentExtractor,
            docxDocumentExtractor,
            markdownSectionExtractor,
            plainTextSectionExtractor,
            null
        );
    }

    CompositeDocumentExtractor(
        MinioStorageService minioStorageService,
        IngestionProperties properties,
        PdfDocumentExtractor pdfDocumentExtractor,
        DocxDocumentExtractor docxDocumentExtractor,
        MarkdownSectionExtractor markdownSectionExtractor,
        PlainTextSectionExtractor plainTextSectionExtractor,
        Path tempDirectory
    ) {
        this.minioStorageService = Objects.requireNonNull(minioStorageService, "minioStorageService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.pdfDocumentExtractor = Objects.requireNonNull(pdfDocumentExtractor, "pdfDocumentExtractor must not be null");
        this.docxDocumentExtractor = Objects.requireNonNull(docxDocumentExtractor, "docxDocumentExtractor must not be null");
        this.markdownSectionExtractor = Objects.requireNonNull(
            markdownSectionExtractor,
            "markdownSectionExtractor must not be null"
        );
        this.plainTextSectionExtractor = Objects.requireNonNull(
            plainTextSectionExtractor,
            "plainTextSectionExtractor must not be null"
        );
        this.tempDirectory = tempDirectory;
    }

    public List<ExtractedSegment> extract(DocumentIngestionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        String displayName = resolveDisplayName(context.originalName());
        String normalizedExtension = normalizeAndValidateExtension(context.fileExtension(), displayName);
        return switch (normalizedExtension) {
            case "pdf" -> extractBinaryFromMinio(
                context.storageKey(),
                ".pdf",
                displayName,
                tempFile -> pdfDocumentExtractor.extract(tempFile, displayName)
            );
            case "docx" -> extractBinaryFromMinio(
                context.storageKey(),
                ".docx",
                displayName,
                tempFile -> docxDocumentExtractor.extract(tempFile, displayName)
            );
            case "md" -> extractTextFromMinio(
                context.storageKey(),
                stream -> markdownSectionExtractor.extract(stream, displayName)
            );
            case "txt" -> extractTextFromMinio(
                context.storageKey(),
                stream -> plainTextSectionExtractor.extract(stream, displayName)
            );
            default -> throw new DocumentExtractionException(
                DocumentExtractionErrorCode.UNSUPPORTED_FORMAT,
                "Unsupported document format '." + normalizedExtension + "' for " + displayName
            );
        };
    }

    public List<ExtractedSegment> extractFromStream(String originalName, String fileExtension, InputStream rawStream) {
        String displayName = resolveDisplayName(originalName);
        String normalizedExtension = normalizeAndValidateExtension(fileExtension, displayName);
        if (rawStream == null) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "Document input stream must not be null for " + displayName
            );
        }

        return switch (normalizedExtension) {
            case "pdf" -> spoolAndExtract(rawStream, ".pdf", displayName,
                tempFile -> pdfDocumentExtractor.extract(tempFile, displayName));
            case "docx" -> spoolAndExtract(rawStream, ".docx", displayName,
                tempFile -> docxDocumentExtractor.extract(tempFile, displayName));
            case "md" -> markdownSectionExtractor.extract(rawStream, displayName);
            case "txt" -> plainTextSectionExtractor.extract(rawStream, displayName);
            default -> throw new DocumentExtractionException(
                DocumentExtractionErrorCode.UNSUPPORTED_FORMAT,
                "Unsupported document format '." + normalizedExtension + "' for " + displayName
            );
        };
    }

    private List<ExtractedSegment> extractBinaryFromMinio(
        String storageKey,
        String suffix,
        String displayName,
        Function<Path, List<ExtractedSegment>> extractor
    ) {
        Path tempFile = createTempFile(suffix, displayName);
        try {
            long totalBytes = spoolMinioObjectToTempFile(storageKey, tempFile, displayName);
            if (totalBytes == 0L) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                    "Uploaded binary document stream is empty (0 bytes): " + displayName
                );
            }
            return extractor.apply(tempFile);
        } finally {
            deleteTempFileQuietly(tempFile);
        }
    }

    private long spoolMinioObjectToTempFile(String storageKey, Path tempFile, String displayName) {
        ResponseInputStream<GetObjectResponse> s3Stream = minioStorageService.openStream(storageKey);
        Throwable primaryFailure = null;
        try {
            InputStream storageStream = new StorageTranslatingInputStream(s3Stream, storageKey);
            return spoolBoundedToTempFile(storageStream, tempFile, displayName);
        } catch (RuntimeException | Error ex) {
            primaryFailure = ex;
            throw ex;
        } finally {
            closeS3StreamAfterRead(s3Stream, storageKey, primaryFailure);
        }
    }

    private List<ExtractedSegment> extractTextFromMinio(
        String storageKey,
        Function<InputStream, List<ExtractedSegment>> extractor
    ) {
        ResponseInputStream<GetObjectResponse> s3Stream = minioStorageService.openStream(storageKey);
        Throwable primaryFailure = null;
        try {
            InputStream storageStream = new StorageTranslatingInputStream(s3Stream, storageKey);
            return extractor.apply(storageStream);
        } catch (RuntimeException | Error ex) {
            primaryFailure = ex;
            throw ex;
        } finally {
            closeS3StreamAfterRead(s3Stream, storageKey, primaryFailure);
        }
    }

    private List<ExtractedSegment> spoolAndExtract(
        InputStream rawStream,
        String suffix,
        String displayName,
        Function<Path, List<ExtractedSegment>> extractor
    ) {
        Path tempFile = createTempFile(suffix, displayName);
        try {
            long totalBytes;
            try {
                totalBytes = spoolBoundedToTempFile(rawStream, tempFile, displayName);
            } finally {
                closeInputStreamQuietly(rawStream, displayName);
            }
            if (totalBytes == 0L) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                    "Uploaded binary document stream is empty (0 bytes): " + displayName
                );
            }
            return extractor.apply(tempFile);
        } finally {
            deleteTempFileQuietly(tempFile);
        }
    }

    private long spoolBoundedToTempFile(InputStream rawStream, Path tempFile, String displayName) {
        long maxSpoolBytes = properties.extraction().maxSpoolBytes();
        long totalBytes = 0L;
        byte[] buffer = new byte[SPOOL_BUFFER_BYTES];

        try (OutputStream out = Files.newOutputStream(tempFile)) {
            int read;
            while ((read = rawStream.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maxSpoolBytes) {
                    throw new DocumentExtractionException(
                        DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                        "Spooled file exceeded max size of " + maxSpoolBytes + " bytes for " + displayName
                    );
                }
                out.write(buffer, 0, read);
            }
            out.flush();
            return totalBytes;
        } catch (DocumentExtractionException | DocumentStorageException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Failed while spooling document stream for extraction: " + displayName,
                ex
            );
        }
    }

    private Path createTempFile(String suffix, String displayName) {
        try {
            return (tempDirectory != null)
                ? Files.createTempFile(tempDirectory, "documind-extract-", suffix)
                : Files.createTempFile("documind-extract-", suffix);
        } catch (IOException ex) {
            throw new DocumentStorageException("Failed to create temporary extraction file for " + displayName, ex);
        }
    }

    private static void closeS3StreamAfterRead(
        ResponseInputStream<GetObjectResponse> s3Stream,
        String storageKey,
        Throwable primaryFailure
    ) {
        if (s3Stream == null) {
            return;
        }
        try {
            s3Stream.close();
        } catch (IOException ex) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(ex);
            } else {
                log.warn("Failed to close MinIO stream cleanly after reading '{}': {}", storageKey, ex.getMessage());
            }
        }
    }

    private static void closeInputStreamQuietly(InputStream rawStream, String displayName) {
        if (rawStream == null) {
            return;
        }
        try {
            rawStream.close();
        } catch (IOException ex) {
            log.warn("Failed to close input stream cleanly after spooling '{}': {}", displayName, ex.getMessage());
        }
    }

    private static void deleteTempFileQuietly(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ex) {
            log.warn("Failed to delete temporary extraction file '{}': {}", tempFile, ex.getMessage());
        }
    }

    private static String resolveDisplayName(String originalName) {
        return (originalName == null || originalName.isBlank()) ? "document" : originalName;
    }

    private static String normalizeAndValidateExtension(String fileExtension, String displayName) {
        if (fileExtension == null || fileExtension.isBlank()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.UNSUPPORTED_FORMAT,
                "Missing file extension for " + displayName
            );
        }
        String normalized = fileExtension.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        if (!SUPPORTED_EXTENSIONS.contains(normalized)) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.UNSUPPORTED_FORMAT,
                "Unsupported document format '." + normalized + "' for " + displayName
            );
        }
        return normalized;
    }

    private static final class StorageTranslatingInputStream extends FilterInputStream {

        private final String storageKey;

        private StorageTranslatingInputStream(InputStream delegate, String storageKey) {
            super(delegate);
            this.storageKey = storageKey;
        }

        @Override
        public int read() {
            try {
                return super.read();
            } catch (IOException ex) {
                throw new DocumentStorageException(
                    "Failed while streaming document from MinIO for key: " + storageKey,
                    ex
                );
            }
        }

        @Override
        public int read(byte[] b, int off, int len) {
            try {
                return super.read(b, off, len);
            } catch (IOException ex) {
                throw new DocumentStorageException(
                    "Failed while streaming document from MinIO for key: " + storageKey,
                    ex
                );
            }
        }

        @Override
        public void close() {
            // Closed explicitly by closeS3StreamAfterRead once extraction/spooling completes.
        }
    }
}
