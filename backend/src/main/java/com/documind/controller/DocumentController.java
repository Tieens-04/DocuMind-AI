package com.documind.controller;

import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.controller.dto.PageResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.service.document.CallerContext;
import com.documind.service.document.CallerContextResolver;
import com.documind.service.document.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;
import java.util.UUID;

/**
 * REST API endpoints for streaming document uploads, status polling, filtered listing, and deletion.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Document upload, ingestion status polling, filtered listing, and hard deletion")
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
        CallerContext caller = callerContextResolver.resolve(departmentId, request);
        DocumentResponse response = documentService.uploadDocument(file, accessLevel, caller);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping
    @Operation(
        summary = "List documents",
        description = "Returns paginated documents sorted by creation timestamp descending with optional filters"
    )
    public PageResponse<DocumentResponse> listDocuments(
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        @RequestParam(name = "status", required = false) DocumentStatus status,
        @RequestParam(name = "accessLevel", required = false) AccessLevel accessLevel,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(departmentId, request);
        return documentService.listDocuments(departmentId, status, accessLevel, page, size, caller);
    }

    @GetMapping("/{id}/status")
    @Operation(
        summary = "Get document ingestion status",
        description = "Returns current ingestion lifecycle state, indexed chunk count, and version for a document"
    )
    public DocumentStatusResponse getDocumentStatus(
        @PathVariable("id") UUID id,
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(departmentId, request);
        return documentService.getDocumentStatus(id, caller);
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Delete document",
        description = "Hard-deletes document metadata and vector chunks, then removes the storage object after commit"
    )
    public ResponseEntity<Void> deleteDocument(
        @PathVariable("id") UUID id,
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        HttpServletRequest request
    ) {
        CallerContext caller = callerContextResolver.resolve(departmentId, request);
        documentService.deleteDocument(id, caller);
        return ResponseEntity.noContent().build();
    }
}
