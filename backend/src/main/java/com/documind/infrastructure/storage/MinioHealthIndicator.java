package com.documind.infrastructure.storage;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

@Component("minio")
@ConditionalOnEnabledHealthIndicator("minio")
public class MinioHealthIndicator implements HealthIndicator {

    private final S3Client s3Client;
    private final StorageProperties storageProperties;

    public MinioHealthIndicator(S3Client s3Client, StorageProperties storageProperties) {
        this.s3Client = s3Client;
        this.storageProperties = storageProperties;
    }

    @Override
    public Health health() {
        String bucket = storageProperties.bucket();
        try {
            s3Client.headBucket(builder -> builder.bucket(bucket));
            return Health.up()
                .withDetail("bucket", bucket)
                .build();
        } catch (SdkException ex) {
            return Health.down(ex)
                .withDetail("bucket", bucket)
                .build();
        }
    }
}
