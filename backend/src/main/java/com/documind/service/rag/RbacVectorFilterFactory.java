package com.documind.service.rag;

import com.documind.domain.enums.AccessLevel;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.service.document.CallerContext;
import com.documind.service.document.DevCallerFallbackEnabledCondition;
import com.documind.service.ingestion.ChunkMetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Resolves server-enforced {@link RbacVectorScope} and Spring AI {@link Filter.Expression} strictly from
 * {@link SecurityContextHolder}, enforcing {@code AGENTS.md} §3.1 zero-leakage invariants and admin-only
 * optional department scoping.
 */
@Component
public class RbacVectorFilterFactory {

    private static final Logger log = LoggerFactory.getLogger(RbacVectorFilterFactory.class);

    private final Environment environment;

    public RbacVectorFilterFactory(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    /**
     * Resolves an authoritative {@link RbacVectorScope} from the current {@link SecurityContextHolder},
     * cross-verifying any supplied {@code expectedCaller} against the authenticated security principal
     * and rejecting unauthorized {@code requestedDepartmentId} filters from non-admin callers.
     */
    public RbacVectorScope resolveScope(CallerContext expectedCaller, UUID requestedDepartmentId) {
        CallerContext authenticatedCaller = resolveAuthenticatedCaller(expectedCaller);
        if (expectedCaller != null) {
            verifyCallerMatchesPrincipal(authenticatedCaller, expectedCaller);
        }
        if (requestedDepartmentId != null && !authenticatedCaller.isSystemAdmin()) {
            log.warn(
                "SECURITY_AUDIT action=UNAUTHORIZED_CHAT_DEPARTMENT_FILTER actorUserId={} actorRole={} actorDeptId={} requestedDeptId={}",
                authenticatedCaller.userId(),
                authenticatedCaller.role(),
                authenticatedCaller.departmentId(),
                requestedDepartmentId
            );
            assertAdminDepartmentFilterAllowed(authenticatedCaller, requestedDepartmentId);
        }

        Filter.Expression filterExpression = buildFilterExpression(authenticatedCaller, requestedDepartmentId);
        String cacheScopeKey = buildCacheScopeKey(authenticatedCaller, requestedDepartmentId);
        return new RbacVectorScope(authenticatedCaller, requestedDepartmentId, filterExpression, cacheScopeKey);
    }

    /**
     * Builds the mandatory {@code pgvector} metadata {@link Filter.Expression} strictly matching {@code AGENTS.md} §3.1:
     * <ul>
     *     <li>{@code STANDARD_EMPLOYEE}: {@code access_level == 'PUBLIC' || (department_id == <dept> && access_level == 'DEPARTMENT')}</li>
     *     <li>{@code DEPARTMENT_MANAGER}: {@code access_level == 'PUBLIC' || department_id == <dept>}</li>
     *     <li>{@code SYSTEM_ADMIN}: all access levels globally, or strictly within {@code adminDepartmentFilter} when supplied</li>
     * </ul>
     */
    public static Filter.Expression buildFilterExpression(CallerContext caller, UUID adminDepartmentFilter) {
        if (caller == null || caller.role() == null) {
            throw new InvalidChatRequestException("Caller context and role must not be null");
        }
        assertAdminDepartmentFilterAllowed(caller, adminDepartmentFilter);
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return switch (caller.role()) {
            case SYSTEM_ADMIN -> {
                FilterExpressionBuilder.Op allLevels = builder.in(
                    ChunkMetadataKeys.ACCESS_LEVEL,
                    AccessLevel.PUBLIC.name(),
                    AccessLevel.DEPARTMENT.name(),
                    AccessLevel.CONFIDENTIAL.name()
                );
                if (adminDepartmentFilter != null) {
                    yield builder.and(
                        builder.eq(ChunkMetadataKeys.DEPARTMENT_ID, adminDepartmentFilter.toString()),
                        allLevels
                    ).build();
                }
                yield allLevels.build();
            }
            case DEPARTMENT_MANAGER -> {
                UUID departmentId = requireDepartmentId(caller);
                yield builder.or(
                    builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.PUBLIC.name()),
                    builder.eq(ChunkMetadataKeys.DEPARTMENT_ID, departmentId.toString())
                ).build();
            }
            case STANDARD_EMPLOYEE -> {
                UUID departmentId = requireDepartmentId(caller);
                yield builder.or(
                    builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.PUBLIC.name()),
                    builder.and(
                        builder.eq(ChunkMetadataKeys.DEPARTMENT_ID, departmentId.toString()),
                        builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.DEPARTMENT.name())
                    )
                ).build();
            }
        };
    }

    /**
     * Computes a deterministic RBAC cache-scope identifier ready for L1/L2 cache isolation ({@code DA-19}),
     * failing closed with {@code 403} if a non-admin caller supplies {@code adminDepartmentFilter}.
     */
    public static String buildCacheScopeKey(CallerContext caller, UUID adminDepartmentFilter) {
        if (caller == null || caller.role() == null) {
            throw new InvalidChatRequestException("Caller context and role must not be null");
        }
        assertAdminDepartmentFilterAllowed(caller, adminDepartmentFilter);
        return switch (caller.role()) {
            case SYSTEM_ADMIN -> adminDepartmentFilter != null
                ? "ADMIN:DEPT:" + adminDepartmentFilter
                : "ADMIN:GLOBAL";
            case DEPARTMENT_MANAGER -> "MANAGER:DEPT:" + requireDepartmentId(caller);
            case STANDARD_EMPLOYEE -> "EMPLOYEE:DEPT:" + requireDepartmentId(caller);
        };
    }

    static void assertAdminDepartmentFilterAllowed(CallerContext caller, UUID departmentFilter) {
        if (departmentFilter != null && !caller.isSystemAdmin()) {
            throw new DocumentAccessDeniedException("Only SYSTEM_ADMIN can filter chat retrieval by departmentId");
        }
    }

    private CallerContext resolveAuthenticatedCaller(CallerContext expectedCaller) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationRequiredException("Authenticated security principal is required for vector retrieval");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails userDetails) {
            String ipAddress = expectedCaller != null ? expectedCaller.ipAddress() : null;
            return new CallerContext(
                userDetails.userId(),
                userDetails.departmentId(),
                userDetails.role(),
                ipAddress
            );
        }
        if (principal instanceof CallerContext preAuthenticatedCaller
            && DevCallerFallbackEnabledCondition.isDevFallbackActive(environment)) {
            if (preAuthenticatedCaller.role() == null) {
                throw new AuthenticationRequiredException("Authenticated caller role is required for vector retrieval");
            }
            String ipAddress = preAuthenticatedCaller.ipAddress() != null
                ? preAuthenticatedCaller.ipAddress()
                : (expectedCaller != null ? expectedCaller.ipAddress() : null);
            return new CallerContext(
                preAuthenticatedCaller.userId(),
                preAuthenticatedCaller.departmentId(),
                preAuthenticatedCaller.role(),
                ipAddress
            );
        }
        throw new AuthenticationRequiredException("Authenticated security principal is required for vector retrieval");
    }

    private static void verifyCallerMatchesPrincipal(CallerContext principalCaller, CallerContext expectedCaller) {
        if (expectedCaller.role() != principalCaller.role()
            || !Objects.equals(expectedCaller.departmentId(), principalCaller.departmentId())
            || !Objects.equals(expectedCaller.userId(), principalCaller.userId())) {
            log.warn(
                "SECURITY_AUDIT action=VECTOR_CALLER_CONTEXT_MISMATCH principalUserId={} principalRole={} principalDeptId={} expectedUserId={} expectedRole={} expectedDeptId={}",
                principalCaller.userId(),
                principalCaller.role(),
                principalCaller.departmentId(),
                expectedCaller.userId(),
                expectedCaller.role(),
                expectedCaller.departmentId()
            );
            throw new DocumentAccessDeniedException("Caller context does not match authenticated security principal");
        }
    }

    private static UUID requireDepartmentId(CallerContext caller) {
        if (caller.departmentId() == null) {
            throw new InvalidChatRequestException("Caller departmentId must not be null for non-admin queries");
        }
        return caller.departmentId();
    }
}
