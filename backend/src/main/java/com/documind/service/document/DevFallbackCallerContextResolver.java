package com.documind.service.document;

import com.documind.exception.InvalidDocumentRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Pre-JWT {@link CallerContextResolver} resolving department/user headers with seeded dev fallbacks
 * outside the {@code prod} profile.
 */
@Component
public class DevFallbackCallerContextResolver implements CallerContextResolver {

    public static final UUID DEFAULT_DEV_DEPARTMENT_ID =
        UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID DEFAULT_DEV_USER_ID =
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    public static final String HEADER_DEPARTMENT_ID = "X-Department-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_IP_LENGTH = 45;

    private final Environment environment;

    public DevFallbackCallerContextResolver(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    @Override
    public CallerContext resolve(UUID requestDepartmentId, HttpServletRequest request) {
        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));

        UUID departmentId;
        boolean explicitDepartment;
        if (requestDepartmentId != null) {
            departmentId = requestDepartmentId;
            explicitDepartment = true;
        } else {
            UUID headerDepartmentId = parseOptionalUuidHeader(request, HEADER_DEPARTMENT_ID);
            if (headerDepartmentId != null) {
                departmentId = headerDepartmentId;
                explicitDepartment = true;
            } else if (isProd) {
                throw new InvalidDocumentRequestException(
                    "Department ID is required in production mode (provide departmentId or X-Department-Id)"
                );
            } else {
                departmentId = DEFAULT_DEV_DEPARTMENT_ID;
                explicitDepartment = false;
            }
        }

        UUID headerUserId = parseOptionalUuidHeader(request, HEADER_USER_ID);
        UUID userId = headerUserId != null ? headerUserId : (isProd ? null : DEFAULT_DEV_USER_ID);

        String ipAddress = resolveClientIp(request);
        return new CallerContext(departmentId, userId, explicitDepartment, ipAddress);
    }

    private static UUID parseOptionalUuidHeader(HttpServletRequest request, String headerName) {
        if (request == null) {
            return null;
        }
        String rawValue = request.getHeader(headerName);
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidDocumentRequestException(
                "Invalid " + headerName + " header: must be a valid UUID"
            );
        }
    }

    private static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedFor = request.getHeader(HEADER_FORWARDED_FOR);
        String candidate = null;
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int commaIdx = forwardedFor.indexOf(',');
            candidate = (commaIdx >= 0 ? forwardedFor.substring(0, commaIdx) : forwardedFor).trim();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = request.getRemoteAddr();
        }
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();
        return trimmed.length() > MAX_IP_LENGTH ? trimmed.substring(0, MAX_IP_LENGTH) : trimmed;
    }
}
