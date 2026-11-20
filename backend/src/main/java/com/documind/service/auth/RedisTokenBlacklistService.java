package com.documind.service.auth;

import com.documind.exception.AuthStateStoreUnavailableException;
import com.documind.infrastructure.security.MonotonicClockMillis;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fail-closed Redis implementation storing revoked access-token {@code jti} keys under
 * {@code auth:blacklist:jti:<jti>} and user-wide revocation cutoffs under
 * {@code auth:revoke-before:user:<userId>} checked together in a single Redis {@code MGET}.
 */
@Service
public class RedisTokenBlacklistService implements TokenBlacklistService {

    public static final String KEY_PREFIX = "auth:blacklist:jti:";
    public static final String USER_REVOKE_KEY_PREFIX = "auth:revoke-before:user:";
    private static final String BLACKLISTED_MARKER = "1";
    private static final DefaultRedisScript<Long> USER_REVOKE_MONOTONIC_SCRIPT = createUserRevokeScript();

    private final Supplier<StringRedisTemplate> redisTemplateSupplier;
    private final Clock clock;
    private final MonotonicClockMillis monotonicClockMillis;

    @Autowired
    public RedisTokenBlacklistService(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        MonotonicClockMillis monotonicClockMillis
    ) {
        this(
            () -> redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null,
            Clock.systemUTC(),
            monotonicClockMillis
        );
    }

    public RedisTokenBlacklistService(StringRedisTemplate redisTemplate, Clock clock) {
        this(redisTemplate, clock, new MonotonicClockMillis());
    }

    public RedisTokenBlacklistService(
        StringRedisTemplate redisTemplate,
        Clock clock,
        MonotonicClockMillis monotonicClockMillis
    ) {
        this(
            () -> Objects.requireNonNull(redisTemplate, "redisTemplate must not be null"),
            clock,
            monotonicClockMillis
        );
    }

    private RedisTokenBlacklistService(
        Supplier<StringRedisTemplate> redisTemplateSupplier,
        Clock clock,
        MonotonicClockMillis monotonicClockMillis
    ) {
        this.redisTemplateSupplier = Objects.requireNonNull(
            redisTemplateSupplier,
            "redisTemplateSupplier must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.monotonicClockMillis = Objects.requireNonNull(
            monotonicClockMillis,
            "monotonicClockMillis must not be null"
        );
    }

    @Override
    public void blacklist(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        StringRedisTemplate redis = requireRedisTemplate();
        try {
            redis.opsForValue().set(KEY_PREFIX + jti.trim(), BLACKLISTED_MARKER, ttl);
        } catch (DataAccessException ex) {
            throw new AuthStateStoreUnavailableException(
                "Authentication revocation store is temporarily unavailable",
                ex
            );
        }
    }

    @Override
    public void revokeAllUserTokens(UUID userId, Duration ttl) {
        if (userId == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        StringRedisTemplate redis = requireRedisTemplate();
        long candidateMillis = monotonicClockMillis.nextMillis(clock);
        long ttlMillis = Math.max(1L, ttl.toMillis());
        try {
            Long committedCutoff = redis.execute(
                USER_REVOKE_MONOTONIC_SCRIPT,
                List.of(USER_REVOKE_KEY_PREFIX + userId),
                String.valueOf(candidateMillis),
                String.valueOf(ttlMillis)
            );
            if (committedCutoff == null) {
                throw new AuthStateStoreUnavailableException(
                    "Authentication revocation store returned an empty revocation timestamp"
                );
            }
            monotonicClockMillis.observeAtLeast(committedCutoff);
        } catch (DataAccessException ex) {
            throw new AuthStateStoreUnavailableException(
                "Authentication revocation store is temporarily unavailable",
                ex
            );
        }
    }

    @Override
    public boolean isTokenRevoked(String jti, UUID userId, Instant issuedAt) {
        if ((jti == null || jti.isBlank()) && userId == null) {
            return false;
        }
        StringRedisTemplate redis = requireRedisTemplate();
        try {
            if (userId == null) {
                return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti.trim()));
            }
            if (jti == null || jti.isBlank()) {
                String userMarker = redis.opsForValue().get(USER_REVOKE_KEY_PREFIX + userId);
                return isIssuedAtRevoked(userMarker, issuedAt);
            }

            List<String> values = redis.opsForValue().multiGet(List.of(
                KEY_PREFIX + jti.trim(),
                USER_REVOKE_KEY_PREFIX + userId
            ));
            if (values == null || values.size() < 2) {
                throw new AuthStateStoreUnavailableException(
                    "Authentication revocation store returned an incomplete response"
                );
            }
            if (values.get(0) != null) {
                return true;
            }
            return isIssuedAtRevoked(values.get(1), issuedAt);
        } catch (DataAccessException ex) {
            throw new AuthStateStoreUnavailableException(
                "Authentication revocation store is temporarily unavailable",
                ex
            );
        }
    }

    private static boolean isIssuedAtRevoked(String rawCutoffMillis, Instant issuedAt) {
        if (rawCutoffMillis == null) {
            return false;
        }
        if (issuedAt == null) {
            return true;
        }
        try {
            long revokedAtMillis = Long.parseLong(rawCutoffMillis.trim());
            return issuedAt.toEpochMilli() <= revokedAtMillis;
        } catch (NumberFormatException ex) {
            // Fail closed on corrupt revocation markers
            return true;
        }
    }

    private StringRedisTemplate requireRedisTemplate() {
        StringRedisTemplate redis = redisTemplateSupplier.get();
        if (redis == null) {
            throw new AuthStateStoreUnavailableException(
                "Authentication revocation store is not configured in this environment"
            );
        }
        return redis;
    }

    private static DefaultRedisScript<Long> createUserRevokeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
            local t = redis.call('TIME')
            local redisNowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local candidateMs = tonumber(ARGV[1])
            local ttlMs = tonumber(ARGV[2])
            local existing = tonumber(redis.call('GET', KEYS[1]))
            local cutoff = math.max(redisNowMs, candidateMs)
            if existing ~= nil and existing >= cutoff then
                cutoff = existing + 1
            end
            redis.call('SET', KEYS[1], tostring(cutoff), 'PX', ttlMs)
            return cutoff
            """);
        return script;
    }
}
