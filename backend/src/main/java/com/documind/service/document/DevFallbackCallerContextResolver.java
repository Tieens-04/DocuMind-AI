package com.documind.service.document;

import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pre-JWT {@link CallerContextResolver} active strictly outside the {@code prod} profile,
 * simulating caller identity via optional {@code X-Department-Id}, {@code X-User-Id}, and {@code X-User-Role} headers
 * with seeded dev defaults.
 */
@Component
@Profile("!prod")
public class DevFallbackCallerContextResolver implements CallerContextResolver {

    public static final UUID DEFAULT_DEV_DEPARTMENT_ID =
        UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID DEFAULT_DEV_USER_ID =
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    public static final String HEADER_DEPARTMENT_ID = "X-Department-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_IP_LENGTH = 45;

    private final Supplier<UserRepository> userRepositorySupplier;

    @Autowired
    public DevFallbackCallerContextResolver(ObjectProvider<UserRepository> userRepositoryProvider) {
        this(() -> userRepositoryProvider != null ? userRepositoryProvider.getIfAvailable() : null);
    }

    public DevFallbackCallerContextResolver() {
        this(() -> null);
    }

    DevFallbackCallerContextResolver(UserRepository userRepository) {
        this(() -> userRepository);
    }

    private DevFallbackCallerContextResolver(Supplier<UserRepository> userRepositorySupplier) {
        this.userRepositorySupplier = Objects.requireNonNull(
            userRepositorySupplier,
            "userRepositorySupplier must not be null"
        );
    }

    @Override
    public CallerContext resolve(HttpServletRequest request) {
        UUID headerDepartmentId = parseOptionalUuidHeader(request, HEADER_DEPARTMENT_ID);
        UUID headerUserId = parseOptionalUuidHeader(request, HEADER_USER_ID);
        Role headerRole = parseOptionalRoleHeader(request);

        UUID userDepartmentId = null;
        Role userRole = null;
        if (headerUserId != null) {
            UserRepository userRepository = userRepositorySupplier.get();
            if (userRepository != null) {
                UserEntity user = userRepository.findById(headerUserId).orElse(null);
                if (user != null) {
                    userDepartmentId = user.getDepartmentId();
                    userRole = user.getRole();
                }
            }
        }

        UUID departmentId = headerDepartmentId != null
            ? headerDepartmentId
            : (userDepartmentId != null ? userDepartmentId : DEFAULT_DEV_DEPARTMENT_ID);

        Role role = headerRole != null
            ? headerRole
            : (userRole != null ? userRole : Role.DEPARTMENT_MANAGER);

        UUID userId;
        if (headerUserId != null) {
            userId = headerUserId;
        } else if (headerDepartmentId == null || DEFAULT_DEV_DEPARTMENT_ID.equals(headerDepartmentId)) {
            userId = DEFAULT_DEV_USER_ID;
        } else {
            userId = null;
        }

        String ipAddress = resolveClientIp(request);
        return new CallerContext(userId, departmentId, role, ipAddress);
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

    private static Role parseOptionalRoleHeader(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String rawValue = request.getHeader(HEADER_USER_ROLE);
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidDocumentRequestException(
                "Invalid " + HEADER_USER_ROLE + " header: must be one of SYSTEM_ADMIN, DEPARTMENT_MANAGER, STANDARD_EMPLOYEE"
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
