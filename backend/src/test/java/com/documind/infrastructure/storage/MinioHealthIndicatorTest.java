package com.documind.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioHealthIndicatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsUpWhenBucketExists() {
        S3Client s3 = mock(S3Client.class);
        AtomicReference<String> probedBucket = new AtomicReference<>();
        when(s3.headBucket(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<HeadBucketRequest.Builder> consumer = invocation.getArgument(0);
            HeadBucketRequest.Builder builder = HeadBucketRequest.builder();
            consumer.accept(builder);
            probedBucket.set(builder.build().bucket());
            return HeadBucketResponse.builder().build();
        });

        var indicator = new MinioHealthIndicator(
            s3,
            new StorageProperties("http://localhost:9000", "ak", "sk", "documind-documents", "us-east-1")
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bucket", "documind-documents");
        assertThat(probedBucket.get()).isEqualTo("documind-documents");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsDownWithBucketDetailWhenBucketMissingOrUnreachable() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(Consumer.class)))
            .thenThrow(NoSuchBucketException.builder().message("missing").build());

        var indicator = new MinioHealthIndicator(
            s3,
            new StorageProperties("http://localhost:9000", "ak", "sk", "documind-documents", "us-east-1")
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("bucket", "documind-documents");
    }
}
