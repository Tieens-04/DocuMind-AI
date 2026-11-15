package com.documind.infrastructure.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "documind.storage")
public record StorageProperties(
    @NotBlank String endpoint,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @NotBlank String bucket,
    String region
) {
}
