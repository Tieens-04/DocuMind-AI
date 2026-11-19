package com.documind.service.auth;

import com.documind.exception.AuthStateStoreUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Fail-closed Redis implementation storing revoked access-token {@code jti} keys
 * under {@code auth:blacklist:jti:<jti>} with TTL equal to remaining token lifetime.
 */
@Service
public class RedisTokenBlacklistService implements TokenBlacklistService {

    public static final String KEY_PREFIX = "auth:blacklist:jti:";
    private static final String BLACKLISTED_MARKER = "1";

    private final Supplier<StringRedisTemplate> redisTemplateSupplier;
    private final Clock clock;

    @Autowired
    public RedisTokenBlacklistService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(
            () -> redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null,
            Clock.systemUTC()
        );
    }

    public RedisTokenBlacklistService(StringRedisTemplate redisTemplate, Clock clock) {
        this(() -> Objects.requireNonNull(redisTemplate, "redisTemplate must not be null"), clock);
    }

    private RedisTokenBlacklistService(Supplier<StringRedisTemplate> redisTemplateSupplier, Clock clock) {
        this.redisTemplateSupplier = Objects.requireNonNull(
            redisTemplateSupplier,
            "redisTemplateSupplier must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        StringRedisTemplate redis = requireRedisTemplate();
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti.trim()));
        } catch (DataAccessException ex) {
            throw new AuthStateStoreUnavailableException(
                "Authentication revocation store is temporarily unavailable",
                ex
            );
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
}
