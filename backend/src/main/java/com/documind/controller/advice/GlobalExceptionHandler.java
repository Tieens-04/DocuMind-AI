package com.documind.controller.advice;

import com.documind.exception.AccountDisabledException;
import com.documind.exception.AdminConflictException;
import com.documind.exception.AuthStateStoreUnavailableException;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.DocumentNotFoundException;
import com.documind.exception.DocumentStorageException;
import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.DuplicateDocumentException;
import com.documind.exception.InvalidAdminRequestException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.InvalidCredentialsException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.exception.InvalidTokenException;
import com.documind.exception.LlmProviderException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.exception.UnsupportedDocumentTypeException;
import com.documind.infrastructure.storage.HashingBoundedInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
    private static final String WWW_AUTHENTICATE_BEARER = "Bearer realm=\"DocuMind AI\"";
    private static final String WWW_AUTHENTICATE_INVALID_TOKEN =
        "Bearer realm=\"DocuMind AI\", error=\"invalid_token\"";

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
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_BEARER)
            .body(problem);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(
        InvalidCredentialsException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.UNAUTHORIZED,
            "Invalid Credentials",
            ex.getMessage(),
            "INVALID_CREDENTIALS",
            request
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_BEARER)
            .body(problem);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidToken(
        InvalidTokenException ex,
        HttpServletRequest request
    ) {
        String title = switch (ex.errorCode()) {
            case InvalidTokenException.CODE_TOKEN_EXPIRED -> "Token Expired";
            case InvalidTokenException.CODE_TOKEN_REVOKED -> "Token Revoked";
            case InvalidTokenException.CODE_REFRESH_TOKEN_REUSED -> "Refresh Token Reused";
            default -> "Invalid Token";
        };
        ProblemDetail problem = buildProblem(
            HttpStatus.UNAUTHORIZED,
            title,
            ex.getMessage(),
            ex.errorCode(),
            request
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_INVALID_TOKEN)
            .body(problem);
    }

    @ExceptionHandler(AccountDisabledException.class)
    public ResponseEntity<ProblemDetail> handleAccountDisabled(
        AccountDisabledException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.FORBIDDEN,
            "Account Disabled",
            ex.getMessage(),
            "ACCOUNT_DISABLED",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(AuthStateStoreUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAuthStateStoreUnavailable(
        AuthStateStoreUnavailableException ex,
        HttpServletRequest request
    ) {
        log.error("Auth revocation store error on {}", request != null ? request.getRequestURI() : "unknown", ex);
        ProblemDetail problem = buildProblem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Authentication Service Unavailable",
            "Authentication revocation store is temporarily unavailable",
            "AUTH_STATE_STORE_UNAVAILABLE",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleSpringAccessDenied(
        AccessDeniedException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.FORBIDDEN,
            "Access Denied",
            "Insufficient permissions to access this resource",
            "ACCESS_DENIED",
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

    @ExceptionHandler(InvalidChatRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidChatRequest(
        InvalidChatRequestException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "Invalid Chat Request",
            ex.getMessage(),
            "INVALID_CHAT_REQUEST",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(InvalidAdminRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAdminRequest(
        InvalidAdminRequestException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "Invalid Admin Request",
            ex.getMessage(),
            "INVALID_ADMIN_REQUEST",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(AdminConflictException.class)
    public ResponseEntity<ProblemDetail> handleAdminConflict(
        AdminConflictException ex,
        HttpServletRequest request
    ) {
        String title = switch (ex.errorCode()) {
            case AdminConflictException.CODE_DUPLICATE_DEPARTMENT_CODE -> "Duplicate Department Code";
            case AdminConflictException.CODE_DEPARTMENT_IN_USE -> "Department In Use";
            case AdminConflictException.CODE_DUPLICATE_EMAIL -> "Duplicate User Email";
            case AdminConflictException.CODE_ADMIN_SELF_MODIFICATION_FORBIDDEN -> "Admin Self Modification Forbidden";
            case AdminConflictException.CODE_LAST_ACTIVE_ADMIN -> "Last Active Admin Protected";
            default -> "Administrative Conflict";
        };
        ProblemDetail problem = buildProblem(
            HttpStatus.CONFLICT,
            title,
            ex.getMessage(),
            ex.errorCode(),
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpServletRequest request
    ) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String detail = (fieldError != null && fieldError.getDefaultMessage() != null)
            ? fieldError.getDefaultMessage()
            : "Request validation failed";
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "Validation Failed",
            detail,
            "VALIDATION_ERROR",
            request
        );
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMessageNotReadable(
        HttpMessageNotReadableException ex,
        HttpServletRequest request
    ) {
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "Malformed JSON Request",
            "Request body is missing or malformed JSON",
            "MALFORMED_JSON_REQUEST",
            request
        );
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

    @ExceptionHandler(LlmProviderException.class)
    public ResponseEntity<ProblemDetail> handleLlmProviderUnavailable(
        LlmProviderException ex,
        HttpServletRequest request
    ) {
        log.error("LLM provider error on {}", request != null ? request.getRequestURI() : "unknown", ex);
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_GATEWAY,
            "LLM Provider Unavailable",
            "Language model service is temporarily unavailable",
            "LLM_PROVIDER_UNAVAILABLE",
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
            return ResponseEntity.status(status)
                .headers(errorResponse.getHeaders())
                .body(problem);
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
            try {
                problem.setInstance(URI.create(request.getRequestURI()));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed request URI when building ProblemDetail instance
            }
        }
        problem.setProperty(ERROR_CODE_PROPERTY, errorCode);
        return problem;
    }
}
