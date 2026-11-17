package com.documind.service.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Generates deterministic version-3 UUIDs for document chunks so {@code PgVectorStore} batch retries remain idempotent.
 */
public final class ChunkIdGenerator {

    private ChunkIdGenerator() {
    }

    public static UUID generate(UUID documentId, int docVersion, int chunkIndex) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }
        if (docVersion < 1) {
            throw new IllegalArgumentException("docVersion must be >= 1");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0");
        }
        String key = "documind:chunk:" + documentId + ":" + docVersion + ":" + chunkIndex;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
