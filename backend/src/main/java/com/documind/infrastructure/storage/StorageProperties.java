package com.documind.infrastructure.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "documind.storage")
public record StorageProperties(
    @NotBlank String endpoint,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @NotBlank String bucket,
    String region,
    @NotNull Duration uploadTimeout,
    @NotNull Duration presignedExpiry
) {

    private static final Duration DEFAULT_UPLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_PRESIGNED_EXPIRY = Duration.ofMinutes(15);

    @ConstructorBinding
    public StorageProperties {
        uploadTimeout = uploadTimeout != null ? uploadTimeout : DEFAULT_UPLOAD_TIMEOUT;
        presignedExpiry = presignedExpiry != null ? presignedExpiry : DEFAULT_PRESIGNED_EXPIRY;
    }

    public StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region
    ) {
        this(endpoint, accessKey, secretKey, bucket, region, DEFAULT_UPLOAD_TIMEOUT, DEFAULT_PRESIGNED_EXPIRY);
    }
}
