package com.documind.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Writes RFC 7807 {@link ProblemDetail} {@code 403 Forbidden} responses at the servlet filter boundary.
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final String ERROR_CODE_PROPERTY = "errorCode";
    private static final String CODE_ACCESS_DENIED = "ACCESS_DENIED";

    private final ObjectMapper objectMapper;

    public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException accessDeniedException
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            "Insufficient permissions to access this resource"
        );
        problem.setTitle("Access Denied");
        problem.setProperty(ERROR_CODE_PROPERTY, CODE_ACCESS_DENIED);
        if (request != null && request.getRequestURI() != null && !request.getRequestURI().isBlank()) {
            try {
                problem.setInstance(URI.create(request.getRequestURI()));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed request URI when building ProblemDetail instance
            }
        }

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
