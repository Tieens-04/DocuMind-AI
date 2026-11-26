package com.documind.infrastructure.ratelimit;

import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration properties for distributed Bucket4j + Redis rate limits per operation and RBAC role.
 */
@Validated
@ConfigurationProperties(prefix = "documind.rate-limit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("10s") Duration expirationBuffer,
    ChatLimits chat,
    UploadLimits upload
) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final Duration DEFAULT_EXPIRATION_BUFFER = Duration.ofSeconds(10);
    public static final Duration DEFAULT_CHAT_PERIOD = Duration.ofMinutes(1);
    public static final int DEFAULT_CHAT_VIEWER_LIMIT = 15;
    public static final int DEFAULT_CHAT_MANAGER_LIMIT = 30;
    public static final int DEFAULT_CHAT_ADMIN_LIMIT = 60;
    public static final Duration DEFAULT_UPLOAD_PERIOD = Duration.ofMinutes(1);
    public static final int DEFAULT_UPLOAD_MANAGER_LIMIT = 5;
    public static final int DEFAULT_UPLOAD_ADMIN_LIMIT = 10;

    private static final Duration MIN_PERIOD = Duration.ofMillis(100);
    private static final Duration MAX_PERIOD = Duration.ofHours(24);
    private static final Duration MAX_EXPIRATION_BUFFER = Duration.ofHours(1);
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100_000;

    @ConstructorBinding
    public RateLimitProperties {
        expirationBuffer = expirationBuffer != null ? expirationBuffer : DEFAULT_EXPIRATION_BUFFER;
        if (expirationBuffer.isNegative() || expirationBuffer.compareTo(MAX_EXPIRATION_BUFFER) > 0) {
            throw new IllegalArgumentException("expirationBuffer must be between PT0S and PT1H");
        }
        chat = chat != null ? chat : ChatLimits.defaults();
        upload = upload != null ? upload : UploadLimits.defaults();
    }

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(
            DEFAULT_ENABLED,
            DEFAULT_EXPIRATION_BUFFER,
            ChatLimits.defaults(),
            UploadLimits.defaults()
        );
    }

    public int limitFor(RateLimitOperation operation, Role role) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(role, "role must not be null");
        return switch (operation) {
            case CHAT -> switch (role) {
                case STANDARD_EMPLOYEE -> chat.viewerLimit();
                case DEPARTMENT_MANAGER -> chat.managerLimit();
                case SYSTEM_ADMIN -> chat.adminLimit();
            };
            case UPLOAD -> switch (role) {
                case DEPARTMENT_MANAGER -> upload.managerLimit();
                case SYSTEM_ADMIN -> upload.adminLimit();
                case STANDARD_EMPLOYEE -> throw new DocumentAccessDeniedException(
                    "Insufficient permissions to upload documents"
                );
            };
        };
    }

    public Duration periodFor(RateLimitOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        return switch (operation) {
            case CHAT -> chat.period();
            case UPLOAD -> upload.period();
        };
    }

    public record ChatLimits(
        @DefaultValue("1m") Duration period,
        @DefaultValue("15") int viewerLimit,
        @DefaultValue("30") int managerLimit,
        @DefaultValue("60") int adminLimit
    ) {

        @ConstructorBinding
        public ChatLimits {
            period = validatePeriod("chat.period", period != null ? period : DEFAULT_CHAT_PERIOD);
            validateLimit("chat.viewerLimit", viewerLimit);
            validateLimit("chat.managerLimit", managerLimit);
            validateLimit("chat.adminLimit", adminLimit);
        }

        public static ChatLimits defaults() {
            return new ChatLimits(
                DEFAULT_CHAT_PERIOD,
                DEFAULT_CHAT_VIEWER_LIMIT,
                DEFAULT_CHAT_MANAGER_LIMIT,
                DEFAULT_CHAT_ADMIN_LIMIT
            );
        }
    }

    public record UploadLimits(
        @DefaultValue("1m") Duration period,
        @DefaultValue("5") int managerLimit,
        @DefaultValue("10") int adminLimit
    ) {

        @ConstructorBinding
        public UploadLimits {
            period = validatePeriod("upload.period", period != null ? period : DEFAULT_UPLOAD_PERIOD);
            validateLimit("upload.managerLimit", managerLimit);
            validateLimit("upload.adminLimit", adminLimit);
        }

        public static UploadLimits defaults() {
            return new UploadLimits(
                DEFAULT_UPLOAD_PERIOD,
                DEFAULT_UPLOAD_MANAGER_LIMIT,
                DEFAULT_UPLOAD_ADMIN_LIMIT
            );
        }
    }

    private static Duration validatePeriod(String name, Duration period) {
        if (period.compareTo(MIN_PERIOD) < 0 || period.compareTo(MAX_PERIOD) > 0) {
            throw new IllegalArgumentException(name + " must be between " + MIN_PERIOD + " and " + MAX_PERIOD);
        }
        return period;
    }

    private static void validateLimit(String name, int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(name + " must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
    }
}
