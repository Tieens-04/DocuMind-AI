package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionException;
import com.documind.exception.DocumentStorageException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.client.RestClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Formats ingestion failures into bounded, user-safe {@code documents.error_message} strings
 * without exposing stack traces, SQL statements, API keys, or host filesystem paths.
 */
public final class IngestionFailureFormatter {

    public static final int MAX_ERROR_MESSAGE_LENGTH = 1_000;
    public static final String INTERRUPTED_ERROR_MESSAGE =
        "[INGESTION_INTERRUPTED] Document ingestion was interrupted before completion; please delete and re-upload.";

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final Pattern CONTROL_AND_WHITESPACE = Pattern.compile("[\\p{Cntrl}\\s]+");

    private static final String STORAGE_OBJECT_MISSING_MESSAGE =
        "[STORAGE_OBJECT_MISSING] Document file was not found in object storage.";
    private static final String STORAGE_ERROR_MESSAGE =
        "[STORAGE_ERROR] Failed to read document from object storage after retry attempts.";
    private static final String EMBEDDING_ERROR_MESSAGE =
        "[EMBEDDING_ERROR] Failed to generate vector embeddings for document chunks.";
    private static final String METADATA_CONFLICT_MESSAGE =
        "[METADATA_CONFLICT] Document metadata changed or conflicted during ingestion.";
    private static final String GENERIC_ERROR_MESSAGE =
        "[INGESTION_ERROR] Unexpected error while processing document.";

    private IngestionFailureFormatter() {
    }

    public static String format(Throwable error) {
        if (error == null) {
            return GENERIC_ERROR_MESSAGE;
        }

        DocumentExtractionException extractionException = findCause(error, DocumentExtractionException.class);
        if (extractionException != null) {
            String sanitizedDetail = sanitizeText(extractionException.getMessage());
            String prefix = "[" + extractionException.errorCode().name() + "] ";
            String message = sanitizedDetail.isEmpty()
                ? prefix + "Document extraction failed."
                : prefix + sanitizedDetail;
            return truncate(message);
        }

        if (findCause(error, NoSuchKeyException.class) != null) {
            return STORAGE_OBJECT_MISSING_MESSAGE;
        }

        if (findCause(error, DocumentStorageException.class) != null) {
            return STORAGE_ERROR_MESSAGE;
        }

        if (findCause(error, DataIntegrityViolationException.class) != null) {
            return METADATA_CONFLICT_MESSAGE;
        }

        if (findCause(error, TransientAiException.class) != null
            || findCause(error, NonTransientAiException.class) != null
            || findCause(error, RestClientException.class) != null) {
            return EMBEDDING_ERROR_MESSAGE;
        }

        return GENERIC_ERROR_MESSAGE;
    }

    private static <T extends Throwable> T findCause(Throwable root, Class<T> targetType) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = root;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            if (targetType.isInstance(current)) {
                return targetType.cast(current);
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static String sanitizeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String withoutNulls = raw.replace("\u0000", "");
        return CONTROL_AND_WHITESPACE.matcher(withoutNulls).replaceAll(" ").trim();
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return text;
        }
        int cut = MAX_ERROR_MESSAGE_LENGTH - 3;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + "...";
    }
}
