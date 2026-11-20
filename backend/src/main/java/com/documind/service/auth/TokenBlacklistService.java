package com.documind.service.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks revoked JWT IDs ({@code jti}) and user-wide token revocation cutoffs until natural access token expiration.
 */
public interface TokenBlacklistService {

    void blacklist(String jti, Instant expiresAt);

    void revokeAllUserTokens(UUID userId, Duration ttl);

    boolean isTokenRevoked(String jti, UUID userId, Instant issuedAt);
}
