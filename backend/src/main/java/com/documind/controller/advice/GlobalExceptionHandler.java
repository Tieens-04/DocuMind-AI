package com.documind.controller.advice;

import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.DocumentNotFoundException;
import com.documind.exception.DocumentStorageException;
import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.DuplicateDocumentException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.exception.UnsupportedDocumentTypeException;
import com.documind.infrastructure.storage.HashingBoundedInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.net.URI;

/**
 * Maps document domain, validation, multipart, and storage exceptions to RFC 7807 {@link ProblemDetail} responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String ERROR_CODE_PROPERTY = "errorCode";
    private static final String DEFAULT_OCTET_STREAM = "application/octet-stream";

    @ExceptionHandler(InvalidDocumentRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(
        InvalidDocumentRequestException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "Invalid Document Request",
            ex.getMessage(),
            "INVALID_DOCUMENT_REQUEST",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestPart(
        MissingServletRequestPartException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "Invalid Document Request",
            "Required multipart part '" + ex.getRequestPartName() + "' is missing",
            "INVALID_DOCUMENT_REQUEST",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
        MethodArgumentTypeMismatchException ex,
        HttpServletRequest request
    ) {
        String detail = "Invalid value for parameter '" + ex.getName() + "'";
        if (ex.getRequiredType() != null) {
            detail += ": expected " + ex.getRequiredType().getSimpleName();
        }
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "Invalid Document Request",
            detail,
            "INVALID_DOCUMENT_REQUEST",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> handleMultipartException(
        MultipartException ex,
        HttpServletRequest request
    ) {
        if (ex instanceof MaxUploadSizeExceededException maxUploadEx) {
            return handleMaxUploadSizeExceeded(maxUploadEx, request);
        }
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "Invalid Document Request",
            "Malformed multipart request",
            "INVALID_DOCUMENT_REQUEST",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationRequired(
        AuthenticationRequiredException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.UNAUTHORIZED,
            "Authentication Required",
            ex.getMessage(),
            "AUTHENTICATION_REQUIRED",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(DocumentAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
        DocumentAccessDeniedException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.FORBIDDEN,
            "Document Access Denied",
            ex.getMessage(),
            "DOCUMENT_ACCESS_DENIED",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler({DocumentNotFoundException.class, ResourceNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(
        RuntimeException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.NOT_FOUND,
            "Resource Not Found",
            ex.getMessage(),
            "RESOURCE_NOT_FOUND",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(DuplicateDocumentException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateDocument(
        DuplicateDocumentException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.CONFLICT,
            "Duplicate Document",
            ex.getMessage(),
            "DUPLICATE_DOCUMENT",
            request
        );
        if (ex.existingDocumentId() != null) {
            problem.setProperty("existingDocumentId", ex.existingDocumentId());
        }
        if (ex.fileHash() != null) {
            problem.setProperty("fileHash", ex.fileHash());
        }
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(DocumentTooLargeException.class)
    public ResponseEntity<ProblemDetail> handleDocumentTooLarge(
        DocumentTooLargeException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "Document Too Large",
            ex.getMessage(),
            "DOCUMENT_TOO_LARGE",
            request
        );
        problem.setProperty("maxSizeBytes", ex.maxSizeBytes());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSizeExceeded(
        MaxUploadSizeExceededException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "Document Too Large",
            "Uploaded payload exceeds maximum allowed size of " + HashingBoundedInputStream.MAX_FILE_SIZE_BYTES + " bytes",
            "DOCUMENT_TOO_LARGE",
            request
        );
        problem.setProperty("maxSizeBytes", HashingBoundedInputStream.MAX_FILE_SIZE_BYTES);
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedType(
        UnsupportedDocumentTypeException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported Document Type",
            ex.getMessage(),
            "UNSUPPORTED_DOCUMENT_TYPE",
            request
        );
        String detectedMime = (ex.detectedMimeType() != null && !ex.detectedMimeType().isBlank())
            ? ex.detectedMimeType()
            : DEFAULT_OCTET_STREAM;
        problem.setProperty("detectedMimeType", detectedMime);
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<ProblemDetail> handleStorageUnavailable(
        DocumentStorageException ex,
        HttpServletRequest request
    ) {
        log.error("Document storage error on {}", request != null ? request.getRequestURI() : "unknown", ex);
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_GATEWAY,
            "Storage Unavailable",
            "Document storage service is temporarily unavailable",
            "STORAGE_UNAVAILABLE",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnhandledException(
        Exception ex,
        HttpServletRequest request
    ) {
        if (ex instanceof ErrorResponse errorResponse) {
            int rawStatus = errorResponse.getStatusCode().value();
            HttpStatus resolved = HttpStatus.resolve(rawStatus);
            HttpStatus status = resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
            boolean clientError = status.is4xxClientError();
            if (!clientError) {
                log.error("Framework error ({}) on {}", rawStatus, request != null ? request.getRequestURI() : "unknown", ex);
            }
            String title = status.getReasonPhrase();
            String detail = clientError ? errorResponse.getBody().getDetail() : "An unexpected internal error occurred";
            String errorCode = clientError ? "REQUEST_ERROR" : "INTERNAL_ERROR";
            ProblemDetail problem = buildProblem(status, title, detail, errorCode, request);
            return ResponseEntity.of(problem).build();
        }
        log.error("Unhandled exception on {}", request != null ? request.getRequestURI() : "unknown", ex);
        ProblemDetail problem = buildProblem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            "An unexpected internal error occurred",
            "INTERNAL_ERROR",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    private static ProblemDetail buildProblem(
        HttpStatus status,
        String title,
        String detail,
        String errorCode,
        HttpServletRequest request
    ) {
        String safeDetail = (detail != null && !detail.isBlank()) ? detail : title;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, safeDetail);
        problem.setTitle(title);
        if (request != null && request.getRequestURI() != null && !request.getRequestURI().isBlank()) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        problem.setProperty(ERROR_CODE_PROPERTY, errorCode);
        return problem;
    }
}
