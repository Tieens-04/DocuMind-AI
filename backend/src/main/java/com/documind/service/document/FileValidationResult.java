package com.documind.service.document;

import java.io.BufferedInputStream;

public record FileValidationResult(
    String originalName,
    String sanitizedFileName,
    String fileExtension,
    String canonicalMimeType,
    long declaredSizeBytes,
    BufferedInputStream rewindedStream
) {
}
