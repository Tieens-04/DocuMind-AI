package com.documind.service.auth;

import com.documind.exception.AuthStateStoreUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisTokenBlacklistServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-19T10:00:00Z");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisTokenBlacklistService blacklistService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        blacklistService = new RedisTokenBlacklistService(redisTemplate, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void blacklistsJtiWithExactRemainingTtl() {
        Instant expiresAt = FIXED_NOW.plus(Duration.ofMinutes(12));

        blacklistService.blacklist("jti-123", expiresAt);

        verify(valueOperations).set("auth:blacklist:jti:jti-123", "1", Duration.ofMinutes(12));
    }

    @Test
    void skipsRedisWriteWhenTokenAlreadyExpiredOrJtiBlank() {
        blacklistService.blacklist("jti-expired", FIXED_NOW);
        blacklistService.blacklist("jti-expired-2", FIXED_NOW.minusSeconds(5));
        blacklistService.blacklist("   ", FIXED_NOW.plusSeconds(60));
        blacklistService.blacklist("jti-null-exp", null);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void returnsBlacklistLookupResultAndHandlesBlankJti() {
        when(redisTemplate.hasKey("auth:blacklist:jti:revoked-jti")).thenReturn(Boolean.TRUE);
        when(redisTemplate.hasKey("auth:blacklist:jti:active-jti")).thenReturn(Boolean.FALSE);

        assertThat(blacklistService.isBlacklisted("revoked-jti")).isTrue();
        assertThat(blacklistService.isBlacklisted("active-jti")).isFalse();
        assertThat(blacklistService.isBlacklisted("   ")).isFalse();
    }

    @Test
    void failsClosedWithAuthStateStoreUnavailableExceptionWhenRedisErrors() {
        doThrow(new DataAccessResourceFailureException("Redis down"))
            .when(valueOperations)
            .set("auth:blacklist:jti:jti-1", "1", Duration.ofMinutes(5));

        assertThatThrownBy(() -> blacklistService.blacklist("jti-1", FIXED_NOW.plus(Duration.ofMinutes(5))))
            .isInstanceOf(AuthStateStoreUnavailableException.class);

        when(redisTemplate.hasKey("auth:blacklist:jti:jti-2"))
            .thenThrow(new DataAccessResourceFailureException("Redis down"));

        assertThatThrownBy(() -> blacklistService.isBlacklisted("jti-2"))
            .isInstanceOf(AuthStateStoreUnavailableException.class);
    }
}
