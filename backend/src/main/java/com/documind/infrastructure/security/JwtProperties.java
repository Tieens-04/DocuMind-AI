package com.documind.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * Externalized configuration for stateless JWT signing and token lifetimes.
 */
@ConfigurationProperties(prefix = "documind.security.jwt")
public record JwtProperties(
    String secret,
    String issuer,
    Duration accessTokenTtl,
    Duration refreshTokenTtl
) {

    public static final String DEFAULT_DEV_SECRET = "documind-local-dev-jwt-hmac-secret-key-min-32-bytes-2026";
    public static final String DEFAULT_ISSUER = "documind-ai";
    public static final Duration DEFAULT_ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    public static final Duration DEFAULT_REFRESH_TOKEN_TTL = Duration.ofDays(7);

    @ConstructorBinding
    public JwtProperties {
        issuer = (issuer == null || issuer.isBlank()) ? DEFAULT_ISSUER : issuer.trim();
        accessTokenTtl = accessTokenTtl != null ? accessTokenTtl : DEFAULT_ACCESS_TOKEN_TTL;
        refreshTokenTtl = refreshTokenTtl != null ? refreshTokenTtl : DEFAULT_REFRESH_TOKEN_TTL;
    }
}
