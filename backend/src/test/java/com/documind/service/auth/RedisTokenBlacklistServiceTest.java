package com.documind.service.auth;

import com.documind.exception.AuthStateStoreUnavailableException;
import com.documind.infrastructure.security.MonotonicClockMillis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisTokenBlacklistServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-19T10:00:00Z");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MonotonicClockMillis monotonicClockMillis;
    private RedisTokenBlacklistService blacklistService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        monotonicClockMillis = new MonotonicClockMillis();
        blacklistService = new RedisTokenBlacklistService(
            redisTemplate,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
            monotonicClockMillis
        );
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
    void handlesSingleAxisLookupsAndBlankInputs() {
        when(redisTemplate.hasKey("auth:blacklist:jti:revoked-jti")).thenReturn(Boolean.TRUE);
        when(redisTemplate.hasKey("auth:blacklist:jti:active-jti")).thenReturn(Boolean.FALSE);

        assertThat(blacklistService.isTokenRevoked("revoked-jti", null, FIXED_NOW)).isTrue();
        assertThat(blacklistService.isTokenRevoked("active-jti", null, FIXED_NOW)).isFalse();
        assertThat(blacklistService.isTokenRevoked("   ", null, FIXED_NOW)).isFalse();
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

        assertThatThrownBy(() -> blacklistService.isTokenRevoked("jti-2", null, FIXED_NOW))
            .isInstanceOf(AuthStateStoreUnavailableException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void revokesAllUserTokensViaClusterMonotonicLuaScriptAndAdvancesLocalWatermark() {
        UUID userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Duration ttl = Duration.ofMinutes(15);
        long redisAheadCutoff = FIXED_NOW.toEpochMilli() + 25L;

        when(redisTemplate.execute(
            any(RedisScript.class),
            eq(List.of("auth:revoke-before:user:" + userId)),
            eq(String.valueOf(FIXED_NOW.toEpochMilli())),
            eq(String.valueOf(ttl.toMillis()))
        )).thenReturn(redisAheadCutoff);

        blacklistService.revokeAllUserTokens(userId, ttl);

        verify(redisTemplate).execute(
            any(RedisScript.class),
            eq(List.of("auth:revoke-before:user:" + userId)),
            eq(String.valueOf(FIXED_NOW.toEpochMilli())),
            eq(String.valueOf(ttl.toMillis()))
        );
        // Next token minted on this JVM strictly follows the cluster-wide Redis cutoff
        assertThat(monotonicClockMillis.nextMillis(Clock.fixed(FIXED_NOW, ZoneOffset.UTC)))
            .isEqualTo(redisAheadCutoff + 1L);
    }

    @Test
    void checksBothJtiAndUserRevocationInSingleRedisMultiGet() {
        UUID userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        List<String> expectedKeys = List.of("auth:blacklist:jti:jti-abc", "auth:revoke-before:user:" + userId);

        // 1. Neither blacklisted nor user-revoked
        when(valueOperations.multiGet(expectedKeys)).thenReturn(Arrays.asList(null, null));
        assertThat(blacklistService.isTokenRevoked("jti-abc", userId, FIXED_NOW)).isFalse();
        verify(valueOperations, times(1)).multiGet(expectedKeys);

        // 2. jti blacklisted directly
        when(valueOperations.multiGet(expectedKeys)).thenReturn(Arrays.asList("1", null));
        assertThat(blacklistService.isTokenRevoked("jti-abc", userId, FIXED_NOW)).isTrue();

        // 3. Token issued before or at user revocation cutoff -> revoked
        String cutoff = String.valueOf(FIXED_NOW.toEpochMilli());
        when(valueOperations.multiGet(expectedKeys)).thenReturn(Arrays.asList(null, cutoff));
        assertThat(blacklistService.isTokenRevoked("jti-abc", userId, FIXED_NOW)).isTrue();
        assertThat(blacklistService.isTokenRevoked("jti-abc", userId, FIXED_NOW.minusMillis(1))).isTrue();

        // 4. Token issued strictly after user revocation cutoff -> valid
        assertThat(blacklistService.isTokenRevoked("jti-abc", userId, FIXED_NOW.plusMillis(1))).isFalse();

        // 5. Corrupt non-numeric cutoff marker fails closed (treated as revoked)
        when(valueOperations.multiGet(expectedKeys)).thenReturn(Arrays.asList(null, "corrupt-timestamp"));
        assertThat(blacklistService.isTokenRevoked("jti-abc", userId, FIXED_NOW.plusSeconds(30))).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsClosedWhenRedisErrorsOrReturnsNullOnUserRevocationOrMultiGetCheck() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.execute(
            any(RedisScript.class),
            eq(List.of("auth:revoke-before:user:" + userId)),
            any(),
            any()
        )).thenThrow(new DataAccessResourceFailureException("Redis down"));

        assertThatThrownBy(() -> blacklistService.revokeAllUserTokens(userId, Duration.ofMinutes(15)))
            .isInstanceOf(AuthStateStoreUnavailableException.class);

        when(redisTemplate.execute(
            any(RedisScript.class),
            eq(List.of("auth:revoke-before:user:" + userId)),
            any(),
            any()
        )).thenReturn(null);

        assertThatThrownBy(() -> blacklistService.revokeAllUserTokens(userId, Duration.ofMinutes(15)))
            .isInstanceOf(AuthStateStoreUnavailableException.class);

        when(valueOperations.multiGet(anyList()))
            .thenThrow(new DataAccessResourceFailureException("Redis down"));

        assertThatThrownBy(() -> blacklistService.isTokenRevoked("jti-1", userId, FIXED_NOW))
            .isInstanceOf(AuthStateStoreUnavailableException.class);
    }
}
