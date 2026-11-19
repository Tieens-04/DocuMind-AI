package com.documind.infrastructure.security;

import com.documind.exception.AuthenticationRequiredException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Writes RFC 7807 {@link ProblemDetail} {@code 401 Unauthorized} responses with {@code WWW-Authenticate}
 * directly at the servlet filter boundary.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String WWW_AUTHENTICATE_BEARER = "Bearer realm=\"DocuMind AI\"";
    public static final String WWW_AUTHENTICATE_INVALID_TOKEN =
        "Bearer realm=\"DocuMind AI\", error=\"invalid_token\"";
    private static final String ERROR_CODE_PROPERTY = "errorCode";
    private static final String CODE_AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException
    ) throws IOException {
        writeUnauthorized(
            request,
            response,
            CODE_AUTHENTICATION_REQUIRED,
            "Authentication Required",
            "Authenticated security principal is required"
        );
    }

    public void writeUnauthorized(
        HttpServletRequest request,
        HttpServletResponse response,
        String errorCode,
        String title,
        String detail
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String safeTitle = (title != null && !title.isBlank()) ? title : "Authentication Required";
        String safeDetail = (detail != null && !detail.isBlank()) ? detail : safeTitle;
        String safeErrorCode = (errorCode != null && !errorCode.isBlank()) ? errorCode : CODE_AUTHENTICATION_REQUIRED;

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, safeDetail);
        problem.setTitle(safeTitle);
        problem.setProperty(ERROR_CODE_PROPERTY, safeErrorCode);
        if (request != null && request.getRequestURI() != null && !request.getRequestURI().isBlank()) {
            try {
                problem.setInstance(URI.create(request.getRequestURI()));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed request URI when building ProblemDetail instance
            }
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String wwwAuthenticate = CODE_AUTHENTICATION_REQUIRED.equals(safeErrorCode)
            ? WWW_AUTHENTICATE_BEARER
            : WWW_AUTHENTICATE_INVALID_TOKEN;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
