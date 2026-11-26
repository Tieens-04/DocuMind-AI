package com.documind.infrastructure.ratelimit;

import com.documind.domain.enums.Role;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.RateLimitExceededException;
import com.documind.exception.RateLimitStoreUnavailableException;
import com.documind.service.audit.AuditLogService;
import com.documind.service.document.CallerContext;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeoutException;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.VerboseResult;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.BucketNotFoundException;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Distributed Bucket4j + Lettuce Redis rate limiter keyed by operation and authenticated user ID,
 * with lazy connection setup, ABA-safe connection recovery, connect-failure backoff, and dynamic
 * role-change reconciliation.
 */
@Service
public class RateLimitService implements AutoCloseable {

    public static final String KEY_PREFIX = "documind:ratelimit:";
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMillis(500);
    private static final Duration DEFAULT_CONNECT_BACKOFF = Duration.ofMillis(500);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final ObjectProvider<LettuceConnectionFactory> connectionFactoryProvider;
    private final Supplier<AuditLogService> auditLogServiceSupplier;
    private final RateLimitProperties properties;
    private final RedisClient fixedRedisClient;
    private final RedisURI fixedRedisUri;
    private final Duration connectFailureBackoff;
    private final ReentrantLock connectionLock = new ReentrantLock();

    private RedisClient ownedFallbackClient;
    private volatile ActiveLimiterState activeState;
    private volatile boolean closed;
    private volatile long connectBackoffUntilNanos;

    record ActiveLimiterState(
        StatefulRedisConnection<String, byte[]> connection,
        LettuceBasedProxyManager<String> proxyManager
    ) {
    }

    @Autowired
    public RateLimitService(
        ObjectProvider<LettuceConnectionFactory> connectionFactoryProvider,
        ObjectProvider<AuditLogService> auditLogServiceProvider,
        RateLimitProperties properties
    ) {
        this.connectionFactoryProvider = Objects.requireNonNull(
            connectionFactoryProvider,
            "connectionFactoryProvider must not be null"
        );
        this.auditLogServiceSupplier = auditLogServiceProvider != null
            ? auditLogServiceProvider::getIfAvailable
            : () -> null;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.fixedRedisClient = null;
        this.fixedRedisUri = null;
        this.connectFailureBackoff = DEFAULT_CONNECT_BACKOFF;
    }

    public RateLimitService(
        RedisClient redisClient,
        RedisURI redisUri,
        AuditLogService auditLogService,
        RateLimitProperties properties
    ) {
        this(redisClient, redisUri, () -> auditLogService, properties, DEFAULT_CONNECT_BACKOFF);
    }

    RateLimitService(
        RedisClient redisClient,
        RedisURI redisUri,
        Supplier<AuditLogService> auditLogServiceSupplier,
        RateLimitProperties properties,
        Duration connectFailureBackoff
    ) {
        this.connectionFactoryProvider = null;
        this.auditLogServiceSupplier = auditLogServiceSupplier != null ? auditLogServiceSupplier : () -> null;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.fixedRedisClient = Objects.requireNonNull(redisClient, "redisClient must not be null");
        this.fixedRedisUri = Objects.requireNonNull(redisUri, "redisUri must not be null");
        this.connectFailureBackoff = connectFailureBackoff != null && !connectFailureBackoff.isNegative()
            ? connectFailureBackoff
            : DEFAULT_CONNECT_BACKOFF;
    }

    public void consume(
        RateLimitOperation operation,
        CallerContext caller,
        UUID auditDepartmentId,
        String endpoint
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        if (caller == null || caller.role() == null) {
            throw new AuthenticationRequiredException("Authenticated caller context is required");
        }
        if (!properties.enabled()) {
            return;
        }

        long startedAtNanos = System.nanoTime();
        int limit = properties.limitFor(operation, caller.role());
        Duration period = properties.periodFor(operation);
        String key = buildBucketKey(operation, caller);

        BucketConfiguration desiredConfig = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(limit)
                .refillIntervally(limit, period)
                .build())
            .build();

        ConsumptionProbe probe;
        ActiveLimiterState observedState = null;
        try {
            observedState = ensureState();
            BucketProxy bucket = observedState.proxyManager().builder().build(key, () -> desiredConfig);
            VerboseResult<ConsumptionProbe> verbose = bucket.asVerbose().tryConsumeAndReturnRemaining(1L);
            probe = verbose.getValue();

            BucketConfiguration existingConfig = verbose.getConfiguration();
            if (!matchesConfiguration(existingConfig, desiredConfig)) {
                probe = reconcileRoleChange(bucket, existingConfig, desiredConfig, limit, probe);
            }
        } catch (RedisException | TimeoutException ex) {
            evictBrokenConnectionIfSame(observedState);
            throw new RateLimitStoreUnavailableException("Rate limiting store is temporarily unavailable", ex);
        }

        if (!probe.isConsumed()) {
            long nanosToWait = Math.max(1L, probe.getNanosToWaitForRefill());
            long retryAfterSeconds = Math.max(1L, (nanosToWait + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND);
            recordRateLimitAudit(operation, caller, auditDepartmentId, endpoint, startedAtNanos);
            throw new RateLimitExceededException(
                retryAfterSeconds,
                "Rate limit exceeded for " + operation.keySegment() + ". Retry after " + retryAfterSeconds + " seconds"
            );
        }
    }

    private static ConsumptionProbe reconcileRoleChange(
        BucketProxy bucket,
        BucketConfiguration existingConfig,
        BucketConfiguration desiredConfig,
        int desiredLimit,
        ConsumptionProbe initialProbe
    ) {
        long oldCapacity = extractCapacity(existingConfig);
        if (desiredLimit > oldCapacity) {
            try {
                bucket.replaceConfiguration(desiredConfig, TokensInheritanceStrategy.ADDITIVE);
            } catch (BucketNotFoundException ignored) {
                // Key expired right after read; retry below recreates it with desiredConfig
            }
            return initialProbe.isConsumed() ? initialProbe : bucket.tryConsumeAndReturnRemaining(1L);
        }

        long remainingAfterOldProbe = initialProbe.getRemainingTokens();
        long preCallAvailable = remainingAfterOldProbe + (initialProbe.isConsumed() ? 1L : 0L);
        long spentInWindow = Math.max(0L, oldCapacity - preCallAvailable);
        long allowedBeforeCall = Math.max(0L, (long) desiredLimit - spentInWindow);

        try {
            bucket.replaceConfiguration(desiredConfig, TokensInheritanceStrategy.AS_IS);
        } catch (BucketNotFoundException ignored) {
            return initialProbe.isConsumed() ? initialProbe : bucket.tryConsumeAndReturnRemaining(1L);
        }

        if (allowedBeforeCall == 0L) {
            // Caller already met or exceeded the demoted limit in this window: drain leftover tokens and reject
            bucket.tryConsumeAsMuchAsPossible();
            return bucket.tryConsumeAndReturnRemaining(1L);
        }

        if (initialProbe.isConsumed()) {
            long targetRemainingAfterCall = allowedBeforeCall - 1L;
            long clampedAfterAsIs = Math.min(remainingAfterOldProbe, desiredLimit);
            long excessToDrain = clampedAfterAsIs - targetRemainingAfterCall;
            if (excessToDrain > 0L) {
                bucket.tryConsumeAsMuchAsPossible(excessToDrain);
            }
            return initialProbe;
        }
        return bucket.tryConsumeAndReturnRemaining(1L);
    }

    public static String buildBucketKey(RateLimitOperation operation, CallerContext caller) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(caller, "caller must not be null");
        if (caller.userId() != null) {
            return KEY_PREFIX + operation.keySegment() + ":" + caller.userId();
        }
        String deptSegment = caller.departmentId() != null ? caller.departmentId().toString() : "none";
        String roleSegment = caller.role() != null ? caller.role().name() : Role.STANDARD_EMPLOYEE.name();
        return KEY_PREFIX + operation.keySegment() + ":anon:" + deptSegment + ":" + roleSegment;
    }

    @PreDestroy
    @Override
    public void close() {
        connectionLock.lock();
        try {
            closed = true;
            closeConnectionLocked();
            if (ownedFallbackClient != null) {
                try {
                    ownedFallbackClient.shutdown();
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup of fallback client on shutdown
                } finally {
                    ownedFallbackClient = null;
                }
            }
        } finally {
            connectionLock.unlock();
        }
    }

    ActiveLimiterState ensureState() {
        if (closed) {
            throw new RateLimitStoreUnavailableException("Rate limiting store is shutting down");
        }
        ActiveLimiterState current = activeState;
        if (current != null && current.connection().isOpen()) {
            return current;
        }
        if (isInConnectBackoff()) {
            throw new RateLimitStoreUnavailableException("Rate limiting store is temporarily unavailable");
        }
        connectionLock.lock();
        try {
            if (closed) {
                throw new RateLimitStoreUnavailableException("Rate limiting store is shutting down");
            }
            ActiveLimiterState rechecked = activeState;
            if (rechecked != null && rechecked.connection().isOpen()) {
                return rechecked;
            }
            if (isInConnectBackoff()) {
                throw new RateLimitStoreUnavailableException("Rate limiting store is temporarily unavailable");
            }
            closeConnectionLocked();

            OpenedConnection opened;
            try {
                opened = openConnectionLocked();
            } catch (RedisException | TimeoutException ex) {
                armConnectBackoff();
                throw ex;
            }
            LettuceBasedProxyManager<String> builtManager = Bucket4jLettuce.casBasedBuilder(opened.connection())
                .requestTimeout(opened.commandTimeout())
                .expirationAfterWrite(
                    ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(properties.expirationBuffer())
                )
                .build();
            ActiveLimiterState created = new ActiveLimiterState(opened.connection(), builtManager);
            activeState = created;
            connectBackoffUntilNanos = 0L;
            return created;
        } finally {
            connectionLock.unlock();
        }
    }

    void evictBrokenConnectionIfSame(ActiveLimiterState observed) {
        if (observed == null || observed.connection().isOpen()) {
            return;
        }
        connectionLock.lock();
        try {
            if (activeState == observed) {
                closeConnectionLocked();
            }
        } finally {
            connectionLock.unlock();
        }
    }

    private boolean isInConnectBackoff() {
        long backoffUntil = connectBackoffUntilNanos;
        return backoffUntil != 0L && (backoffUntil - System.nanoTime()) > 0L;
    }

    private void armConnectBackoff() {
        if (!connectFailureBackoff.isZero()) {
            long until = System.nanoTime() + connectFailureBackoff.toNanos();
            connectBackoffUntilNanos = (until == 0L) ? 1L : until;
        }
    }

    private OpenedConnection openConnectionLocked() {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        if (fixedRedisClient != null) {
            Duration timeout = resolveEffectiveTimeout(fixedRedisUri.getTimeout());
            return new OpenedConnection(fixedRedisClient.connect(codec, fixedRedisUri), timeout);
        }
        LettuceConnectionFactory factory = connectionFactoryProvider != null
            ? connectionFactoryProvider.getIfAvailable()
            : null;
        if (factory == null) {
            throw new RateLimitStoreUnavailableException("Redis connection factory is not configured");
        }
        RedisURI redisUri = buildRedisUri(factory);
        Duration timeout = resolveEffectiveTimeout(redisUri.getTimeout());
        AbstractRedisClient nativeClient = factory.getNativeClient();
        if (nativeClient instanceof RedisClient client) {
            return new OpenedConnection(client.connect(codec, redisUri), timeout);
        }
        if (nativeClient == null) {
            if (ownedFallbackClient == null) {
                ownedFallbackClient = RedisClient.create(redisUri);
            }
            return new OpenedConnection(ownedFallbackClient.connect(codec, redisUri), timeout);
        }
        throw new RateLimitStoreUnavailableException(
            "Unsupported Lettuce native client type: " + nativeClient.getClass().getSimpleName()
        );
    }

    private static Duration resolveEffectiveTimeout(Duration candidate) {
        return (candidate != null && !candidate.isZero() && !candidate.isNegative())
            ? candidate
            : DEFAULT_COMMAND_TIMEOUT;
    }

    private record OpenedConnection(StatefulRedisConnection<String, byte[]> connection, Duration commandTimeout) {
    }

    private static RedisURI buildRedisUri(LettuceConnectionFactory factory) {
        RedisStandaloneConfiguration standalone = factory.getStandaloneConfiguration();
        LettuceClientConfiguration clientConfig = factory.getClientConfiguration();
        String host = standalone.getHostName();
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        int port = standalone.getPort() > 0 ? standalone.getPort() : RedisURI.DEFAULT_REDIS_PORT;
        int database = standalone.getDatabase();

        Duration timeout = resolveEffectiveTimeout(clientConfig != null ? clientConfig.getCommandTimeout() : null);
        RedisURI.Builder builder = RedisURI.builder()
            .withHost(host)
            .withPort(port)
            .withDatabase(database)
            .withTimeout(timeout);

        if (clientConfig != null) {
            builder.withSsl(clientConfig.isUseSsl());
            builder.withStartTls(clientConfig.isStartTls());
            if (clientConfig.getVerifyMode() != null) {
                builder.withVerifyPeer(clientConfig.getVerifyMode());
            }
        }

        RedisPassword password = standalone.getPassword();
        if (password != null && password.isPresent()) {
            String username = standalone.getUsername();
            if (username != null && !username.isBlank()) {
                builder.withAuthentication(username, password.get());
            } else {
                builder.withPassword(password.get());
            }
        }
        return builder.build();
    }

    private void closeConnectionLocked() {
        ActiveLimiterState closing = activeState;
        activeState = null;
        if (closing != null) {
            try {
                closing.connection().close();
            } catch (RuntimeException ignored) {
                // Best-effort close on stale connection
            }
        }
    }

    private void recordRateLimitAudit(
        RateLimitOperation operation,
        CallerContext caller,
        UUID auditDepartmentId,
        String endpoint,
        long startedAtNanos
    ) {
        AuditLogService auditLogService = auditLogServiceSupplier.get();
        if (auditLogService == null) {
            return;
        }
        UUID effectiveDeptId = auditDepartmentId != null ? auditDepartmentId : caller.departmentId();
        long latencyMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
        String detail = "RATE_LIMIT:" + operation.name()
            + (endpoint != null && !endpoint.isBlank() ? ":" + endpoint : "");
        auditLogService.recordRateLimitRejectionAsync(new AuditLogService.RateLimitAuditPayload(
            caller.userId(),
            effectiveDeptId,
            detail,
            latencyMs,
            caller.ipAddress(),
            OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        ));
    }

    private static boolean matchesConfiguration(BucketConfiguration existing, BucketConfiguration desired) {
        if (existing == null || desired == null) {
            return false;
        }
        Bandwidth[] currentLimits = existing.getBandwidths();
        Bandwidth[] desiredLimits = desired.getBandwidths();
        if (currentLimits == null || desiredLimits == null || currentLimits.length != desiredLimits.length) {
            return false;
        }
        for (int i = 0; i < currentLimits.length; i++) {
            Bandwidth cur = currentLimits[i];
            Bandwidth target = desiredLimits[i];
            if (cur.getCapacity() != target.getCapacity()
                || cur.getRefillTokens() != target.getRefillTokens()
                || cur.getRefillPeriodNanos() != target.getRefillPeriodNanos()
                || cur.isRefillIntervally() != target.isRefillIntervally()) {
                return false;
            }
        }
        return true;
    }

    private static long extractCapacity(BucketConfiguration config) {
        if (config == null || config.getBandwidths() == null || config.getBandwidths().length == 0) {
            return 0L;
        }
        return config.getBandwidths()[0].getCapacity();
    }
}
