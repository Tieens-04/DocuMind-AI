package com.documind.infrastructure.ratelimit;

import com.documind.domain.enums.Role;
import com.documind.exception.RateLimitStoreUnavailableException;
import com.documind.service.document.CallerContext;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.RedisCodec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    private static final RedisURI TEST_URI = RedisURI.builder()
        .withHost("localhost")
        .withPort(6380)
        .withTimeout(Duration.ofMillis(300))
        .build();

    private static final CallerContext CALLER = new CallerContext(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Role.STANDARD_EMPLOYEE,
        "127.0.0.1"
    );

    @Test
    @SuppressWarnings("unchecked")
    void preservesOpenMultiplexedConnectionPreventsAbaEvictionAndBlocksReopenAfterClose() {
        RedisClient redisClient = mock(RedisClient.class);
        StatefulRedisConnection<String, byte[]> conn1 = mock(StatefulRedisConnection.class);
        StatefulRedisConnection<String, byte[]> conn2 = mock(StatefulRedisConnection.class);
        when(conn1.async()).thenReturn(mock(RedisAsyncCommands.class));
        when(conn2.async()).thenReturn(mock(RedisAsyncCommands.class));
        AtomicBoolean conn1Open = new AtomicBoolean(true);
        AtomicBoolean conn2Open = new AtomicBoolean(true);
        when(conn1.isOpen()).thenAnswer(inv -> conn1Open.get());
        when(conn2.isOpen()).thenAnswer(inv -> conn2Open.get());
        when(redisClient.connect(any(RedisCodec.class), any(RedisURI.class)))
            .thenReturn(conn1, conn2);

        RateLimitService service = new RateLimitService(
            redisClient,
            TEST_URI,
            () -> null,
            RateLimitProperties.defaults(),
            Duration.ofMillis(500)
        );

        RateLimitService.ActiveLimiterState state1 = service.ensureState();
        // Transient timeout on an open multiplexed connection must NOT tear it down
        service.evictBrokenConnectionIfSame(state1);
        verify(conn1, never()).close();
        assertThat(service.ensureState()).isSameAs(state1);

        // Simulate conn1 actually dropping -> another thread opens state2 -> delayed ABA eviction of state1 must NOT close state2
        conn1Open.set(false);
        RateLimitService.ActiveLimiterState state2 = service.ensureState();
        assertThat(state2).isNotSameAs(state1);
        verify(conn1, times(1)).close();

        service.evictBrokenConnectionIfSame(state1);
        verify(conn2, never()).close();
        assertThat(service.ensureState()).isSameAs(state2);

        // After @PreDestroy close(), service must never reopen a leaked connection
        service.close();
        verify(conn2, times(1)).close();
        assertThatThrownBy(() -> service.consume(RateLimitOperation.CHAT, CALLER, CALLER.departmentId(), "/api/v1/chat/ask"))
            .isInstanceOf(RateLimitStoreUnavailableException.class);
        verify(redisClient, times(2)).connect(any(RedisCodec.class), any(RedisURI.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectFailureArmsShortBackoffSoConcurrentCallersFailFastWithoutLockConvoy() {
        RedisClient redisClient = mock(RedisClient.class);
        when(redisClient.connect(any(RedisCodec.class), any(RedisURI.class)))
            .thenThrow(new RedisConnectionException("Connection refused"));

        try (RateLimitService service = new RateLimitService(
            redisClient,
            TEST_URI,
            () -> null,
            RateLimitProperties.defaults(),
            Duration.ofSeconds(5)
        )) {
            assertThatThrownBy(() -> service.consume(RateLimitOperation.CHAT, CALLER, CALLER.departmentId(), "/api/v1/chat/ask"))
                .isInstanceOf(RateLimitStoreUnavailableException.class);
            assertThatThrownBy(() -> service.consume(RateLimitOperation.CHAT, CALLER, CALLER.departmentId(), "/api/v1/chat/ask"))
                .isInstanceOf(RateLimitStoreUnavailableException.class);
            assertThatThrownBy(() -> service.consume(RateLimitOperation.CHAT, CALLER, CALLER.departmentId(), "/api/v1/chat/ask"))
                .isInstanceOf(RateLimitStoreUnavailableException.class);

            verify(redisClient, times(1)).connect(any(RedisCodec.class), any(RedisURI.class));
        }
    }
}
