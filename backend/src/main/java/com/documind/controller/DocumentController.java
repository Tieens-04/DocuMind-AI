package com.documind.controller;

import com.documind.controller.dto.DocumentPreviewUrlResponse;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.UpdateDocumentMetadataRequest;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.service.document.CallerContext;
import com.documind.service.document.CallerContextResolver;
import com.documind.service.document.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;
import java.util.UUID;

/**
 * REST API endpoints for streaming document uploads, version re-indexing, presigned preview URLs,
 * status polling, RBAC-scoped listing, and deletion.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(
    name = "Documents",
    description = "Document upload, version re-indexing, RBAC-enforced presigned preview URLs, ingestion status polling, listing, and hard deletion"
)
public class DocumentController {

    private final DocumentService documentService;
    private final CallerContextResolver callerContextResolver;

    public DocumentController(DocumentService documentService, CallerContextResolver callerContextResolver) {
        this.documentService = Objects.requireNonNull(documentService, "documentService must not be null");
        this.callerContextResolver = Objects.requireNonNull(callerContextResolver, "callerContextResolver must not be null");
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Upload document",
        description = "Validates file signature, streams to MinIO object storage, records metadata, and queues asynchronous ingestion"
    )
    public ResponseEntity<DocumentResponse> uploadDocument(
        @RequestPart("file") MultipartFile file,
        @RequestParam(name = "accessLevel", required = false) AccessLevel accessLevel,
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        DocumentResponse response = documentService.uploadDocument(file, accessLevel, departmentId, caller);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping
    @Operation(
        summary = "List documents",
        description = "Returns paginated documents visible to the caller sorted by creation timestamp descending with optional filters"
    )
    public PageResponse<DocumentResponse> listDocuments(
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        @RequestParam(name = "status", required = false) DocumentStatus status,
        @RequestParam(name = "accessLevel", required = false) AccessLevel accessLevel,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return documentService.listDocuments(departmentId, status, accessLevel, page, size, caller);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Get document metadata",
        description = "Returns metadata for a single document when visible to the caller's role and department scope"
    )
    public DocumentResponse getDocument(
        @PathVariable("id") UUID id,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return documentService.getDocument(id, caller);
    }

    @GetMapping("/{id}/status")
    @Operation(
        summary = "Get document ingestion status",
        description = "Returns current ingestion lifecycle state, indexed chunk count, and version for a document"
    )
    public DocumentStatusResponse getDocumentStatus(
        @PathVariable("id") UUID id,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return documentService.getDocumentStatus(id, caller);
    }

    @GetMapping("/{id}/preview-url")
    @Operation(
        summary = "Get presigned document preview URL",
        description = "Verifies RBAC read permissions before issuing a short-lived inline MinIO presigned GET URL with optional PDF #page=X anchor"
    )
    @ApiResponse(responseCode = "200", description = "Short-lived inline presigned preview URL issued")
    public DocumentPreviewUrlResponse getDocumentPreviewUrl(
        @PathVariable("id") UUID id,
        @RequestParam(name = "page", required = false) Integer page,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return documentService.getDocumentPreviewUrl(id, page, caller);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Replace document file and re-index",
        description = "Validates and streams a new file version to MinIO, increments currentVersion, queues async vector re-indexing, and purges obsolete chunks and storage objects cleanly"
    )
    @ApiResponse(responseCode = "202", description = "Document replacement accepted for asynchronous re-indexing")
    public ResponseEntity<DocumentResponse> replaceDocument(
        @PathVariable("id") UUID id,
        @RequestPart("file") MultipartFile file,
        @RequestParam(name = "accessLevel", required = false) AccessLevel accessLevel,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        DocumentResponse response = documentService.replaceDocument(id, file, accessLevel, caller);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PatchMapping("/{id}")
    @Operation(
        summary = "Update document metadata",
        description = "Updates document name, access level, or (for SYSTEM_ADMIN) department ownership and propagates chunk metadata"
    )
    public DocumentResponse updateDocumentMetadata(
        @PathVariable("id") UUID id,
        @Valid @RequestBody UpdateDocumentMetadataRequest body,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        return documentService.updateDocumentMetadata(id, body, caller);
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Delete document",
        description = "Hard-deletes document metadata and vector chunks, then removes the storage object after commit"
    )
    public ResponseEntity<Void> deleteDocument(
        @PathVariable("id") UUID id,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(request);
        documentService.deleteDocument(id, caller);
        return ResponseEntity.noContent().build();
    }
}
