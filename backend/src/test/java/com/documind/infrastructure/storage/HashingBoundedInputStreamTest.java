package com.documind.infrastructure.storage;

import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.DocumentNotFoundException;
import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.DuplicateDocumentException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.exception.UnsupportedDocumentTypeException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashingBoundedInputStreamTest {

    @Test
    void singlePassOverUtf8BytesComputesExactSha256LowercaseHexAndBytesRead() throws IOException, NoSuchAlgorithmException {
        byte[] payload = "Quy chế nhân sự DocuMind AI".getBytes(StandardCharsets.UTF_8);
        String expectedHex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));

        try (var stream = new HashingBoundedInputStream(
            new ByteArrayInputStream(payload),
            HashingBoundedInputStream.MAX_FILE_SIZE_BYTES
        )) {
            byte[] readBack = stream.readAllBytes();

            assertThat(readBack).isEqualTo(payload);
            assertThat(stream.bytesRead()).isEqualTo(payload.length);
            String actualHex = stream.sha256Hex();
            assertThat(actualHex)
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(expectedHex);
            // Idempotent repeat reads of sha256Hex()
            assertThat(stream.sha256Hex()).isEqualTo(expectedHex);
        }
    }

    @Test
    void readingExactMaxSizeBytesSucceeds() throws IOException, NoSuchAlgorithmException {
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);
        String expectedHex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));

        try (var stream = new HashingBoundedInputStream(new ByteArrayInputStream(payload), 10L)) {
            byte[] readBack = stream.readAllBytes();
            assertThat(readBack).isEqualTo(payload);
            assertThat(stream.bytesRead()).isEqualTo(10L);
            assertThat(stream.sha256Hex()).isEqualTo(expectedHex);
        }
    }

    @Test
    void readingMaxSizeBytesPlusOneViaBufferedReadThrowsImmediatelyAtBoundary() throws IOException {
        long maxSizeBytes = 16L;
        byte[] oversizedPayload = new byte[1024];

        try (var stream = new HashingBoundedInputStream(new ByteArrayInputStream(oversizedPayload), maxSizeBytes)) {
            byte[] buffer = new byte[512];
            assertThatThrownBy(() -> {
                while (stream.read(buffer, 0, buffer.length) != -1) {
                    // consume until boundary exceeded
                }
            })
                .isInstanceOf(DocumentTooLargeException.class)
                .satisfies(ex -> assertThat(((DocumentTooLargeException) ex).maxSizeBytes()).isEqualTo(maxSizeBytes));

            // Stopped immediately at maxSizeBytes + 1 without reading the rest of the 1024-byte stream
            assertThat(stream.bytesRead()).isEqualTo(maxSizeBytes + 1);
        }
    }

    @Test
    void readingMaxSizeBytesPlusOneViaSingleByteReadThrowsImmediatelyAtBoundary() throws IOException {
        long maxSizeBytes = 4L;
        byte[] oversizedPayload = new byte[]{1, 2, 3, 4, 5, 6};

        try (var stream = new HashingBoundedInputStream(new ByteArrayInputStream(oversizedPayload), maxSizeBytes)) {
            for (int i = 0; i < maxSizeBytes; i++) {
                assertThat(stream.read()).isEqualTo(oversizedPayload[i]);
            }
            assertThat(stream.bytesRead()).isEqualTo(maxSizeBytes);

            assertThatThrownBy(stream::read)
                .isInstanceOf(DocumentTooLargeException.class)
                .satisfies(ex -> assertThat(((DocumentTooLargeException) ex).maxSizeBytes()).isEqualTo(maxSizeBytes));
            assertThat(stream.bytesRead()).isEqualTo(maxSizeBytes + 1);
        }
    }

    @Test
    void sha256HexOnZeroByteStreamThrowsInvalidDocumentRequestException() throws IOException {
        try (var stream = new HashingBoundedInputStream(new ByteArrayInputStream(new byte[0]), 1024L)) {
            assertThat(stream.readAllBytes()).isEmpty();
            assertThat(stream.bytesRead()).isZero();

            assertThatThrownBy(stream::sha256Hex)
                .isInstanceOf(InvalidDocumentRequestException.class);
        }
    }

    @Test
    void skipHashesSkippedBytesAndMarkResetIsDisabled() throws IOException, NoSuchAlgorithmException {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String expectedHex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));

        try (var stream = new HashingBoundedInputStream(new ByteArrayInputStream(payload), 16L)) {
            assertThat(stream.markSupported()).isFalse();
            assertThatThrownBy(stream::reset).isInstanceOf(IOException.class);

            long skipped = stream.skip(6L);
            assertThat(skipped).isEqualTo(6L);
            byte[] remaining = stream.readAllBytes();
            assertThat(remaining).hasSize(10);
            assertThat(stream.bytesRead()).isEqualTo(16L);
            assertThat(stream.sha256Hex()).isEqualTo(expectedHex);
        }
    }

    @Test
    void domainExceptionsExposeStructuredFieldsAndMessages() {
        UUID docId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        var unsupported = new UnsupportedDocumentTypeException("application/x-msdownload", "Unsupported type");
        assertThat(unsupported.detectedMimeType()).isEqualTo("application/x-msdownload");
        assertThat(unsupported.getMessage()).isEqualTo("Unsupported type");

        var duplicate = new DuplicateDocumentException(docId, "abc123", "Duplicate document");
        assertThat(duplicate.existingDocumentId()).isEqualTo(docId);
        assertThat(duplicate.fileHash()).isEqualTo("abc123");
        assertThat(duplicate.getMessage()).isEqualTo("Duplicate document");

        var notFound = new DocumentNotFoundException(docId);
        assertThat(notFound.documentId()).isEqualTo(docId);
        assertThat(notFound.getMessage()).contains(docId.toString());

        var resourceNotFound = new ResourceNotFoundException("Department not found");
        assertThat(resourceNotFound.getMessage()).isEqualTo("Department not found");

        var accessDenied = new DocumentAccessDeniedException("Cross-department access denied");
        assertThat(accessDenied.getMessage()).isEqualTo("Cross-department access denied");
    }
}
