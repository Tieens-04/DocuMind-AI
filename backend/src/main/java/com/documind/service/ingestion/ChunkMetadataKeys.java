package com.documind.service.ingestion;

/**
 * Canonical metadata keys stamped onto Spring AI {@code Document} chunks to satisfy pgvector RBAC and Flyway triggers.
 */
public final class ChunkMetadataKeys {

    public static final String DOCUMENT_ID = "document_id";
    public static final String DEPARTMENT_ID = "department_id";
    public static final String ACCESS_LEVEL = "access_level";
    public static final String FILE_NAME = "file_name";
    public static final String DOC_VERSION = "doc_version";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String PAGE_NUMBER = "page_number";
    public static final String SECTION_HEADING = "section_heading";

    private ChunkMetadataKeys() {
    }
}
