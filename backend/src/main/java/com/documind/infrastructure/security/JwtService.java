package com.documind.infrastructure.security;

import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues and verifies HMAC-SHA signed JWT access tokens using {@code jjwt 0.12}.
 */
@Service
public class JwtService {

    private static final int MIN_HMAC_SECRET_BYTES = 32;
    private static final String CLAIM_DEPT_ID = "deptId";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_FULL_NAME = "fullName";
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties properties;
    private final SecretKey signingKey;
    private final JwtParser jwtParser;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties properties, Environment environment) {
        this(properties, environment, Clock.systemUTC());
    }

    public JwtService(JwtProperties properties, Environment environment, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        validateDurations(properties);
        this.signingKey = validateAndBuildSigningKey(properties.secret(), environment);
        this.jwtParser = Jwts.parser()
            .verifyWith(this.signingKey)
            .requireIssuer(properties.issuer())
            .clock(() -> Date.from(this.clock.instant()))
            .build();
    }

    /**
     * Generates a signed stateless JWT access token embedding canonical user and RBAC claims.
     */
    public IssuedAccessToken generateAccessToken(UserEntity user) {
        Objects.requireNonNull(user, "user must not be null");
        UUID userId = Objects.requireNonNull(user.getId(), "user.id must not be null");
        UUID departmentId = Objects.requireNonNull(user.getDepartmentId(), "user.departmentId must not be null");
        Role role = Objects.requireNonNull(user.getRole(), "user.role must not be null");

        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
            .id(jti)
            .issuer(properties.issuer())
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .claim(CLAIM_DEPT_ID, departmentId.toString())
            .claim(CLAIM_EMAIL, user.getEmail())
            .claim(CLAIM_FULL_NAME, user.getFullName())
            .claim(CLAIM_ROLE, role.name())
            .signWith(signingKey)
            .compact();

        return new IssuedAccessToken(
            token,
            jti,
            OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
            properties.accessTokenTtl().toSeconds()
        );
    }

    /**
     * Parses and validates signature, issuer, expiration, and required RBAC claims of an access JWT.
     */
    public AuthenticatedTokenClaims parseAndValidateAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException(InvalidTokenException.CODE_INVALID_TOKEN, "Access token is missing");
        }
        try {
            Claims claims = jwtParser.parseSignedClaims(token.trim()).getPayload();
            String jti = requireNonBlank(claims.getId(), "jti");
            UUID userId = parseUuid(claims.getSubject(), "sub");
            UUID departmentId = parseUuid(claims.get(CLAIM_DEPT_ID, String.class), CLAIM_DEPT_ID);
            String email = requireNonBlank(claims.get(CLAIM_EMAIL, String.class), CLAIM_EMAIL);
            String fullName = requireNonBlank(claims.get(CLAIM_FULL_NAME, String.class), CLAIM_FULL_NAME);
            Role role = parseRole(claims.get(CLAIM_ROLE, String.class));
            Date issuedAtDate = claims.getIssuedAt();
            Date expirationDate = claims.getExpiration();
            if (issuedAtDate == null || expirationDate == null) {
                throw new InvalidTokenException(InvalidTokenException.CODE_INVALID_TOKEN, "Access token timestamps are missing");
            }
            return new AuthenticatedTokenClaims(
                jti,
                userId,
                departmentId,
                email,
                fullName,
                role,
                issuedAtDate.toInstant(),
                expirationDate.toInstant()
            );
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException(InvalidTokenException.CODE_TOKEN_EXPIRED, "Access token has expired", ex);
        } catch (InvalidTokenException ex) {
            throw ex;
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException(InvalidTokenException.CODE_INVALID_TOKEN, "Access token is invalid", ex);
        }
    }

    public Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }

    private static void validateDurations(JwtProperties properties) {
        if (properties.accessTokenTtl() == null
            || properties.accessTokenTtl().isZero()
            || properties.accessTokenTtl().isNegative()) {
            throw new IllegalStateException("documind.security.jwt.access-token-ttl must be positive");
        }
        if (properties.refreshTokenTtl() == null
            || properties.refreshTokenTtl().isZero()
            || properties.refreshTokenTtl().isNegative()) {
            throw new IllegalStateException("documind.security.jwt.refresh-token-ttl must be positive");
        }
    }

    private static SecretKey validateAndBuildSigningKey(String rawSecret, Environment environment) {
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
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private static String requireNonBlank(String value, String claimName) {
        if (value == null || value.isBlank()) {
            throw new InvalidTokenException(
                InvalidTokenException.CODE_INVALID_TOKEN,
                "Access token claim '" + claimName + "' is missing"
            );
        }
        return value;
    }

    private static UUID parseUuid(String rawUuid, String claimName) {
        try {
            return UUID.fromString(requireNonBlank(rawUuid, claimName));
        } catch (IllegalArgumentException ex) {
            throw new InvalidTokenException(
                InvalidTokenException.CODE_INVALID_TOKEN,
                "Access token claim '" + claimName + "' is invalid",
                ex
            );
        }
    }

    private static Role parseRole(String rawRole) {
        try {
            return Role.valueOf(requireNonBlank(rawRole, CLAIM_ROLE));
        } catch (IllegalArgumentException ex) {
            throw new InvalidTokenException(
                InvalidTokenException.CODE_INVALID_TOKEN,
                "Access token claim 'role' is invalid",
                ex
            );
        }
    }

    public record IssuedAccessToken(
        String token,
        String jti,
        OffsetDateTime expiresAt,
        long expiresInSeconds
    ) {
    }
}
