package com.documind.infrastructure.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free strictly monotonic epoch-millisecond generator shared across JWT issuance and user-wide
 * revocation markers so causal ordering holds even when token minting and revocation happen in the same millisecond.
 */
@Component
public class MonotonicClockMillis {

    private final AtomicLong lastIssuedMillis = new AtomicLong(Long.MIN_VALUE);

    public long nextMillis(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        long now = clock.millis();
        return lastIssuedMillis.updateAndGet(prev -> prev == Long.MIN_VALUE ? now : Math.max(now, prev + 1L));
    }

    /**
     * Advances the local monotonic watermark to at least {@code observedMillis} (for example, a cluster-wide
     * revocation cutoff returned by Redis Lua) so subsequent tokens minted on this JVM strictly follow it.
     */
    public void observeAtLeast(long observedMillis) {
        lastIssuedMillis.updateAndGet(prev -> prev == Long.MIN_VALUE ? observedMillis : Math.max(prev, observedMillis));
    }
}
