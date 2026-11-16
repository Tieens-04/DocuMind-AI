package com.documind.service.document;

import java.util.UUID;

/**
 * Resolved caller identity and department scope for document operations prior to JWT enforcement ({@code DA-12}).
 */
public record CallerContext(
    UUID departmentId,
    UUID userId,
    boolean explicitDepartment,
    String ipAddress
) {
}
