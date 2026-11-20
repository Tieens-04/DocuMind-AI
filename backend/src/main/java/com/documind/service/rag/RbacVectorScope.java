package com.documind.service.rag;

import com.documind.domain.enums.AccessLevel;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.service.document.CallerContext;
import com.documind.service.document.DocumentVisibilityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable RBAC retrieval scope resolved strictly from {@code SecurityContextHolder}, bundling
 * the {@code pgvector} {@link Filter.Expression}, canonical cache scope key, and Java Defense-in-Depth guards.
 */
public record RbacVectorScope(
    CallerContext caller,
    UUID filterDepartmentId,
    Filter.Expression filterExpression,
    String cacheScopeKey
) {

    private static final Logger log = LoggerFactory.getLogger(RbacVectorScope.class);

    public RbacVectorScope {
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(caller.role(), "caller.role must not be null");
        Objects.requireNonNull(filterExpression, "filterExpression must not be null");
        if (cacheScopeKey == null || cacheScopeKey.isBlank()) {
            throw new IllegalArgumentException("cacheScopeKey must not be blank");
        }
        if (!caller.isSystemAdmin() && caller.departmentId() == null) {
            throw new IllegalArgumentException("Non-admin RbacVectorScope requires non-null caller.departmentId");
        }
        RbacVectorFilterFactory.assertAdminDepartmentFilterAllowed(caller, filterDepartmentId);
    }

    /**
     * Evaluates whether a resource belonging to {@code resourceDepartmentId} with {@code resourceAccessLevel}
     * is authorized under this scope (combining role visibility policy and optional admin department scoping).
     */
    public boolean allows(UUID resourceDepartmentId, AccessLevel resourceAccessLevel) {
        if (resourceDepartmentId == null || resourceAccessLevel == null) {
            return false;
        }
        if (filterDepartmentId != null && !Objects.equals(filterDepartmentId, resourceDepartmentId)) {
            return false;
        }
        return DocumentVisibilityPolicy.canRead(caller, resourceDepartmentId, resourceAccessLevel);
    }

    /**
     * Hard pre-prompt Defense-in-Depth barrier verifying every chunk is non-null and authorized before
     * any document text enters {@link GroundedSystemPromptService} or the LLM prompt.
     */
    public void assertAllAuthorized(List<RetrievedChunk> chunks) {
        if (chunks == null) {
            return;
        }
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null || !allows(chunk.departmentId(), chunk.accessLevel())) {
                log.error(
                    "SECURITY_ALERT action=PRE_PROMPT_RBAC_GUARD_BLOCKED actorUserId={} actorRole={} actorDeptId={} filterDeptId={} chunkId={} documentId={} chunkDeptId={} chunkAccessLevel={}",
                    caller.userId(),
                    caller.role(),
                    caller.departmentId(),
                    filterDepartmentId,
                    chunk != null ? chunk.chunkId() : null,
                    chunk != null ? chunk.documentId() : null,
                    chunk != null ? chunk.departmentId() : null,
                    chunk != null ? chunk.accessLevel() : null
                );
                throw new DocumentAccessDeniedException("Unauthorized chunk blocked before prompt assembly");
            }
        }
    }
}
