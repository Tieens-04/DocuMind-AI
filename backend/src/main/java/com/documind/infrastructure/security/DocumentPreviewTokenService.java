package com.documind.infrastructure.security;

import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.service.document.CallerContext;
import com.documind.service.document.DevCallerFallbackEnabledCondition;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues and verifies short-lived, domain-separated HMAC-SHA256 preview tokens for download-time RBAC
 * enforcement on {@code GET /api/v1/documents/{id}/content?token=...}.
 *
 * <p>Cryptographically separated from bearer access tokens via {@code SHA-256("documind-preview-v1:" + secret)}
 * and JWS header {@code typ = "doc_preview"} so cross-token confusion is impossible.
 */
@Service
public class DocumentPreviewTokenService {

    public static final String HEADER_TYP_DOC_PREVIEW = "doc_preview";

    private static final int MIN_HMAC_SECRET_BYTES = 32;
    private static final String DOMAIN_SEPARATION_PREFIX = "documind-preview-v1:";
    private static final String CLAIM_DOC_ID = "docId";
    private static final String CLAIM_DOC_VERSION = "ver";
    private static final String CLAIM_DEPT_ID = "deptId";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_JWT_AUTHENTICATED = "jwt";
    private static final String CLAIM_ACCESS_TOKEN_JTI = "ajti";
    private static final String CLAIM_ISSUED_AT_MILLIS = "iatMs";

    private final JwtProperties jwtProperties;
    private final Environment environment;
    private final PreviewBaseUrlResolver baseUrlResolver;
    private final Duration defaultExpiry;
    private final Clock clock;
    private final MonotonicClockMillis monotonicClockMillis;
    private final SecretKey previewSigningKey;
    private final JwtParser previewParser;

    @Autowired
    public DocumentPreviewTokenService(
        JwtProperties jwtProperties,
        Environment environment,
        PreviewBaseUrlResolver baseUrlResolver,
        StorageProperties storageProperties,
        MonotonicClockMillis monotonicClockMillis
    ) {
        this(
            jwtProperties,
            environment,
            baseUrlResolver,
            storageProperties != null ? storageProperties.presignedExpiry() : Duration.ofMinutes(15),
            Clock.systemUTC(),
            monotonicClockMillis
        );
    }

    public DocumentPreviewTokenService(
        JwtProperties jwtProperties,
        Environment environment,
        PreviewBaseUrlResolver baseUrlResolver,
        Duration defaultExpiry,
        Clock clock
    ) {
        this(jwtProperties, environment, baseUrlResolver, defaultExpiry, clock, new MonotonicClockMillis());
    }

    public DocumentPreviewTokenService(
        JwtProperties jwtProperties,
        Environment environment,
        PreviewBaseUrlResolver baseUrlResolver,
        Duration defaultExpiry,
        Clock clock,
        MonotonicClockMillis monotonicClockMillis
    ) {
        this.jwtProperties = Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.baseUrlResolver = Objects.requireNonNull(baseUrlResolver, "baseUrlResolver must not be null");
        this.defaultExpiry = Objects.requireNonNull(defaultExpiry, "defaultExpiry must not be null");
        if (defaultExpiry.isZero() || defaultExpiry.isNegative()) {
            throw new IllegalStateException("Document preview token expiry must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.monotonicClockMillis = Objects.requireNonNull(monotonicClockMillis, "monotonicClockMillis must not be null");
        this.previewSigningKey = derivePreviewSigningKey(jwtProperties.secret(), environment);
        this.previewParser = Jwts.parser()
            .verifyWith(this.previewSigningKey)
            .requireIssuer(jwtProperties.issuer())
            .clock(() -> Date.from(this.clock.instant()))
            .build();
    }

    public Duration defaultExpiry() {
        return defaultExpiry;
    }

    /**
     * Mints a signed download-gated preview base URL ({@code .../api/v1/documents/{id}/content?token=...})
     * binding the document ID, exact document version, and caller identity/revocation lineage.
     */
    public IssuedPreviewUrl issuePreviewUrl(UUID documentId, int docVersion, CallerContext caller) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(caller, "caller must not be null");
        Role role = Objects.requireNonNull(caller.role(), "caller.role must not be null");
        if (docVersion < 1) {
            throw new IllegalArgumentException("docVersion must be >= 1");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean jwtAuthenticated = false;
        String accessTokenJti = null;
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails cud) {
            jwtAuthenticated = true;
            accessTokenJti = cud.tokenJti();
        } else if (!DevCallerFallbackEnabledCondition.isDevFallbackActive(environment)) {
            throw new DocumentAccessDeniedException("Authenticated JWT principal is required to issue preview URL");
        }

        long issuedAtMillis = monotonicClockMillis.nextMillis(clock);
        Instant now = Instant.ofEpochMilli(issuedAtMillis);
        Instant expiresAt = now.plus(defaultExpiry);

        var builder = Jwts.builder()
            .header().type(HEADER_TYP_DOC_PREVIEW).and()
            .id(UUID.randomUUID().toString())
            .issuer(jwtProperties.issuer())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .claim(CLAIM_DOC_ID, documentId.toString())
            .claim(CLAIM_DOC_VERSION, docVersion)
            .claim(CLAIM_ROLE, role.name())
            .claim(CLAIM_JWT_AUTHENTICATED, jwtAuthenticated)
            .claim(CLAIM_ISSUED_AT_MILLIS, issuedAtMillis);

        if (caller.userId() != null) {
            builder.subject(caller.userId().toString());
        }
        if (caller.departmentId() != null) {
            builder.claim(CLAIM_DEPT_ID, caller.departmentId().toString());
        }
        if (accessTokenJti != null && !accessTokenJti.isBlank()) {
            builder.claim(CLAIM_ACCESS_TOKEN_JTI, accessTokenJti);
        }

        String token = builder.signWith(previewSigningKey).compact();
        String baseUrl = baseUrlResolver.resolveBaseUrl() + "/api/v1/documents/" + documentId + "/content?token=" + token;
        return new IssuedPreviewUrl(
            baseUrl,
            token,
            OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        );
    }

    /**
     * Cryptographically verifies a preview query token and asserts it matches {@code expectedDocumentId}.
     */
    public VerifiedPreviewClaims verifyToken(String rawToken, UUID expectedDocumentId) {
        if (expectedDocumentId == null || rawToken == null || rawToken.isBlank()) {
            throw new DocumentAccessDeniedException("Invalid or missing document preview token");
        }
        try {
            Jws<Claims> jws = previewParser.parseSignedClaims(rawToken.trim());
            if (!HEADER_TYP_DOC_PREVIEW.equals(jws.getHeader().getType())) {
                throw new DocumentAccessDeniedException("Invalid document preview token type");
            }
            Claims claims = jws.getPayload();
            UUID tokenDocId = parseRequiredUuid(claims.get(CLAIM_DOC_ID, String.class));
            if (!expectedDocumentId.equals(tokenDocId)) {
                throw new DocumentAccessDeniedException("Document preview token does not match requested document");
            }

            Number rawVersion = claims.get(CLAIM_DOC_VERSION, Number.class);
            if (rawVersion == null || rawVersion.intValue() < 1) {
                throw new DocumentAccessDeniedException("Document preview token version is invalid");
            }

            Role role = parseRequiredRole(claims.get(CLAIM_ROLE, String.class));
            UUID userId = parseOptionalUuid(claims.getSubject());
            UUID departmentId = parseOptionalUuid(claims.get(CLAIM_DEPT_ID, String.class));
            Boolean jwtAuthenticated = claims.get(CLAIM_JWT_AUTHENTICATED, Boolean.class);
            String accessTokenJti = claims.get(CLAIM_ACCESS_TOKEN_JTI, String.class);

            Date expiration = claims.getExpiration();
            Number rawIatMs = claims.get(CLAIM_ISSUED_AT_MILLIS, Number.class);
            if (expiration == null || rawIatMs == null) {
                throw new DocumentAccessDeniedException("Document preview token timestamps are invalid");
            }

            boolean isJwt = Boolean.TRUE.equals(jwtAuthenticated);
            if (isJwt && userId == null) {
                throw new DocumentAccessDeniedException("Document preview token subject is missing");
            }
            if (!isJwt && !DevCallerFallbackEnabledCondition.isDevFallbackActive(environment)) {
                throw new DocumentAccessDeniedException("Non-JWT preview tokens are not permitted");
            }

            return new VerifiedPreviewClaims(
                tokenDocId,
                rawVersion.intValue(),
                userId,
                departmentId,
                role,
                isJwt,
                accessTokenJti,
                Instant.ofEpochMilli(rawIatMs.longValue()),
                expiration.toInstant()
            );
        } catch (ExpiredJwtException ex) {
            throw new DocumentAccessDeniedException("Document preview URL has expired");
        } catch (DocumentAccessDeniedException ex) {
            throw ex;
        } catch (JwtException | IllegalArgumentException ex) {
            throw new DocumentAccessDeniedException("Invalid document preview token");
        }
    }

    private static SecretKey derivePreviewSigningKey(String rawSecret, Environment environment) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalStateException("documind.security.jwt.secret must not be blank");
        }
        String trimmed = rawSecret.trim();
        boolean devOrTestProfileActive = environment != null
            && !environment.acceptsProfiles(Profiles.of("prod"))
            && environment.acceptsProfiles(Profiles.of("dev", "local", "test"));
        if (JwtProperties.DEFAULT_DEV_SECRET.equals(trimmed) && !devOrTestProfileActive) {
            throw new IllegalStateException(
                "documind.security.jwt.secret must be overridden outside dev/local/test profiles"
            );
        }
        byte[] keyBytes = trimmed.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_HMAC_SECRET_BYTES) {
            throw new IllegalStateException(
                "documind.security.jwt.secret must be at least " + MIN_HMAC_SECRET_BYTES + " UTF-8 bytes"
            );
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(DOMAIN_SEPARATION_PREFIX.getBytes(StandardCharsets.UTF_8));
            byte[] derived = sha256.digest(keyBytes);
            return Keys.hmacShaKeyFor(derived);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", ex);
        }
    }

    private static UUID parseRequiredUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DocumentAccessDeniedException("Missing UUID claim in preview token");
        }
        return UUID.fromString(raw.trim());
    }

    private static UUID parseOptionalUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return UUID.fromString(raw.trim());
    }

    private static Role parseRequiredRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DocumentAccessDeniedException("Missing role claim in preview token");
        }
        return Role.valueOf(raw.trim());
    }

    public static java.util.Optional<String> tryIssuePreviewBaseUrl(
        DocumentPreviewTokenService tokenService,
        UUID documentId,
        Integer currentVersion,
        CallerContext caller,
        Logger log
    ) {
        if (tokenService == null || documentId == null || caller == null) {
            return java.util.Optional.empty();
        }
        try {
            int version = currentVersion != null ? currentVersion : 1;
            IssuedPreviewUrl issued = tokenService.issuePreviewUrl(documentId, version, caller);
            return issued != null && issued.baseUrl() != null
                ? java.util.Optional.of(issued.baseUrl())
                : java.util.Optional.empty();
        } catch (RuntimeException ex) {
            if (log != null) {
                log.warn("Failed to generate citation preview URL for document {}: {}", documentId, ex.getMessage());
            }
            return java.util.Optional.empty();
        }
    }

    public record IssuedPreviewUrl(
        String baseUrl,
        String token,
        OffsetDateTime expiresAt
    ) {
    }

    public record VerifiedPreviewClaims(
        UUID documentId,
        int docVersion,
        UUID userId,
        UUID departmentId,
        Role role,
        boolean jwtAuthenticated,
        String accessTokenJti,
        Instant issuedAt,
        Instant expiresAt
    ) {
    }
}
