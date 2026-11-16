package com.documind.support;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shared streaming multipart upload helpers and lazy PDF generators for integration and smoke tests.
 */
public final class StreamingMultipartTestSupport {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private StreamingMultipartTestSupport() {
    }

    /**
     * Creates an {@link HttpClient} pinned to HTTP/1.1 with standard connect timeout; callers must close it.
     */
    public static HttpClient newHttp11Client() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
            .build();
    }

    public static String newBoundary(String prefix) {
        String effectivePrefix = (prefix != null && !prefix.isBlank()) ? prefix : "----DocuMindBoundary";
        return effectivePrefix + UUID.randomUUID().toString().replace("-", "");
    }

    public static HttpRequest.BodyPublisher multipartFileBodyPublisher(
        String boundary,
        String filename,
        String contentType,
        Supplier<InputStream> contentStreamSupplier
    ) {
        Objects.requireNonNull(boundary, "boundary must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(contentStreamSupplier, "contentStreamSupplier must not be null");

        byte[] preamble = (
            "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "\r\n"
        ).getBytes(StandardCharsets.UTF_8);
        byte[] epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        return HttpRequest.BodyPublishers.ofInputStream(() -> new SequenceInputStream(Collections.enumeration(List.of(
            new ByteArrayInputStream(preamble),
            Objects.requireNonNull(contentStreamSupplier.get(), "contentStreamSupplier must not return null"),
            new ByteArrayInputStream(epilogue)
        ))));
    }

    public static HttpRequest buildMultipartUploadRequest(
        URI uri,
        String boundaryPrefix,
        String filename,
        String contentType,
        Supplier<InputStream> contentStreamSupplier,
        Duration timeout
    ) {
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        String boundary = newBoundary(boundaryPrefix);
        return HttpRequest.newBuilder(uri)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .timeout(timeout)
            .POST(multipartFileBodyPublisher(boundary, filename, contentType, contentStreamSupplier))
            .build();
    }

    /**
     * Lazy {@link InputStream} emitting a deterministic valid {@code %PDF-1.4} payload of exact length
     * with an optional per-run marker comment, without materializing a multi-megabyte buffer on the JVM heap.
     */
    public static final class RepeatingPdfInputStream extends InputStream {

        private static final byte[] PDF_SUFFIX = "\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] PATTERN_CHUNK = "DOCUMIND_STREAM_PDF_PAYLOAD_0123456789ABCDEF\n"
            .repeat(180)
            .getBytes(StandardCharsets.US_ASCII);

        private final byte[] pdfPrefix;
        private final long totalLength;
        private final long suffixStart;
        private long position;

        public RepeatingPdfInputStream(long totalLength) {
            this(totalLength, null);
        }

        public RepeatingPdfInputStream(long totalLength, String runMarker) {
            String prefixText = (runMarker != null && !runMarker.isBlank())
                ? "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n% run=" + runMarker + "\n"
                : "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n% ";
            this.pdfPrefix = prefixText.getBytes(StandardCharsets.US_ASCII);
            if (totalLength < this.pdfPrefix.length + PDF_SUFFIX.length) {
                throw new IllegalArgumentException("totalLength too small for PDF framing");
            }
            this.totalLength = totalLength;
            this.suffixStart = totalLength - PDF_SUFFIX.length;
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count == -1 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (position >= totalLength) {
                return -1;
            }

            int totalCopied = 0;
            while (totalCopied < len && position < totalLength) {
                int remainingOut = len - totalCopied;
                if (position < pdfPrefix.length) {
                    int prefixOffset = (int) position;
                    int toCopy = Math.min(remainingOut, pdfPrefix.length - prefixOffset);
                    System.arraycopy(pdfPrefix, prefixOffset, b, off + totalCopied, toCopy);
                    position += toCopy;
                    totalCopied += toCopy;
                } else if (position < suffixStart) {
                    long bodyOffset = position - pdfPrefix.length;
                    int patternOffset = (int) (bodyOffset % PATTERN_CHUNK.length);
                    long bytesUntilSuffix = suffixStart - position;
                    int toCopy = (int) Math.min(remainingOut, Math.min(PATTERN_CHUNK.length - patternOffset, bytesUntilSuffix));
                    System.arraycopy(PATTERN_CHUNK, patternOffset, b, off + totalCopied, toCopy);
                    position += toCopy;
                    totalCopied += toCopy;
                } else {
                    int suffixOffset = (int) (position - suffixStart);
                    int toCopy = Math.min(remainingOut, PDF_SUFFIX.length - suffixOffset);
                    System.arraycopy(PDF_SUFFIX, suffixOffset, b, off + totalCopied, toCopy);
                    position += toCopy;
                    totalCopied += toCopy;
                }
            }
            return totalCopied;
        }
    }
}
