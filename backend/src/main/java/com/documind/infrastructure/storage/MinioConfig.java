package com.documind.infrastructure.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

@Configuration
public class MinioConfig {

    private static final Duration S3_API_CALL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration S3_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public S3Client s3Client(StorageProperties props) {
        return S3Client.builder()
            .endpointOverride(URI.create(props.endpoint()))
            .credentialsProvider(credentialsProvider(props))
            .region(resolveRegion(props))
            .forcePathStyle(true)
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(S3_API_CALL_TIMEOUT)
                .apiCallAttemptTimeout(S3_API_CALL_ATTEMPT_TIMEOUT)
                .build())
            .build();
    }

    @Bean
    public S3Presigner s3Presigner(StorageProperties props) {
        return S3Presigner.builder()
            .endpointOverride(URI.create(props.effectivePublicEndpoint()))
            .credentialsProvider(credentialsProvider(props))
            .region(resolveRegion(props))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .build();
    }

    private static StaticCredentialsProvider credentialsProvider(StorageProperties props) {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(props.accessKey(), props.secretKey())
        );
    }

    private static Region resolveRegion(StorageProperties props) {
        String raw = props.region();
        String regionName = (raw == null || raw.isBlank()) ? "us-east-1" : raw.trim();
        return Region.of(regionName);
    }
}
