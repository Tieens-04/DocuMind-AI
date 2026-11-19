package com.documind.infrastructure.security;

import com.documind.domain.enums.Role;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified claims extracted from a signed JWT access token without hitting the database.
 */
public record AuthenticatedTokenClaims(
    String jti,
    UUID userId,
    UUID departmentId,
    String email,
    String fullName,
    Role role,
    Instant issuedAt,
    Instant expiresAt
) {
}
