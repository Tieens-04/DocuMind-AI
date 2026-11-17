package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionException;
import com.documind.exception.DocumentStorageException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies exceptions across extraction and batch vector embedding stages so transient I/O or upstream
 * service glitches retry with exponential backoff while deterministic failures abort immediately.
 */
@Component("ingestionRetryClassifier")
public class IngestionRetryClassifier {

    private static final int MAX_CAUSE_DEPTH = 16;

    private final IngestionProperties.WorkerProperties workerProperties;

    public IngestionRetryClassifier(IngestionProperties properties) {
        this.workerProperties = Objects.requireNonNull(properties, "properties must not be null").worker();
    }

    public boolean shouldRetryExtraction(Throwable error) {
        if (error == null) {
            return false;
        }
        boolean hasStorageError = false;
        for (Throwable current : collectCauses(error)) {
            if (current instanceof NoSuchKeyException
                || current instanceof DocumentExtractionException
                || current instanceof IllegalArgumentException) {
                return false;
            }
            if (current instanceof DocumentStorageException) {
                hasStorageError = true;
            }
        }
        return hasStorageError;
    }

    public boolean shouldRetryBatch(Throwable error) {
        if (error == null) {
            return false;
        }
        boolean retryableFound = false;
        for (Throwable current : collectCauses(error)) {
            if (current instanceof NonTransientAiException nonTransientAi) {
                if (isRetryableAiClientStatus(nonTransientAi.getMessage())) {
                    retryableFound = true;
                    continue;
                }
                return false;
            }
            if (current instanceof DocumentExtractionException
                || current instanceof NonTransientDataAccessException
                || current instanceof NoSuchKeyException
                || current instanceof IllegalArgumentException) {
                return false;
            }
            if (current instanceof HttpClientErrorException clientError) {
                int status = clientError.getStatusCode().value();
                if (status == HttpStatus.TOO_MANY_REQUESTS.value() || status == HttpStatus.REQUEST_TIMEOUT.value()) {
                    retryableFound = true;
                } else {
                    return false;
                }
            }
            if (current instanceof TransientAiException
                || current instanceof ResourceAccessException
                || current instanceof HttpServerErrorException
                || current instanceof TransientDataAccessException
                || current instanceof RecoverableDataAccessException) {
                retryableFound = true;
            }
        }
        return retryableFound;
    }

    public int maxAttempts() {
        return workerProperties.maxAttempts();
    }

    public long initialBackoffMillis() {
        return workerProperties.initialBackoff().toMillis();
    }

    public double backoffMultiplier() {
        return workerProperties.backoffMultiplier();
    }

    public long maxBackoffMillis() {
        return workerProperties.maxBackoff().toMillis();
    }

    private static boolean isRetryableAiClientStatus(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.stripLeading();
        return trimmed.startsWith("429 ") || trimmed.startsWith("429-")
            || trimmed.startsWith("408 ") || trimmed.startsWith("408-");
    }

    private static List<Throwable> collectCauses(Throwable root) {
        List<Throwable> causes = new ArrayList<>(4);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = root;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            causes.add(current);
            current = current.getCause();
            depth++;
        }
        return causes;
    }
}
