package com.documind.service.auth;

import java.time.Instant;

/**
 * Tracks revoked JWT IDs ({@code jti}) until their natural access token expiration.
 */
public interface TokenBlacklistService {

    void blacklist(String jti, Instant expiresAt);

    boolean isBlacklisted(String jti);
}
