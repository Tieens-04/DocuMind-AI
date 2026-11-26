package com.documind.support;

import com.documind.infrastructure.storage.StorageProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@TestConfiguration(proxyBeanMethods = false)
public class MinioTestcontainersConfig {

    public static final String MINIO_ROOT_USER = "documind_minio";
    public static final String MINIO_ROOT_PASSWORD = "documind_minio_secret";
    public static final String BUCKET_NAME = "documind-documents";
    private static final int MINIO_API_PORT = 9000;
    private static final String MINIO_IMAGE =
        "cgr.dev/chainguard/minio:latest@sha256:634c989a2f1029162fc2676c263caa181213b9410449968532f750a6fb77532d";

    private static final class SharedMinioContainer extends GenericContainer<SharedMinioContainer> {
        SharedMinioContainer(DockerImageName dockerImageName) {
            super(dockerImageName);
        }

        @Override
        public void stop() {
            // Keep JVM-wide singleton container alive across @DirtiesContext closes; Ryuk reaps on JVM exit.
        }
    }

    private static final GenericContainer<?> SHARED_MINIO = new SharedMinioContainer(DockerImageName.parse(MINIO_IMAGE))
        .withEnv("MINIO_ROOT_USER", MINIO_ROOT_USER)
        .withEnv("MINIO_ROOT_PASSWORD", MINIO_ROOT_PASSWORD)
        .withCommand("server", "/data")
        .withExposedPorts(MINIO_API_PORT)
        .waitingFor(Wait.forHttp("/minio/health/live").forPort(MINIO_API_PORT).forStatusCode(200));

    static {
        SHARED_MINIO.start();
    }

    @Bean(destroyMethod = "")
    GenericContainer<?> minioContainer() {
        return SHARED_MINIO;
    }

    @Bean
    DynamicPropertyRegistrar minioDynamicProperties(GenericContainer<?> minioContainer) {
        return registry -> {
            registry.add(
                "documind.storage.endpoint",
                () -> "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(MINIO_API_PORT)
            );
            registry.add("documind.storage.access-key", () -> MINIO_ROOT_USER);
            registry.add("documind.storage.secret-key", () -> MINIO_ROOT_PASSWORD);
            registry.add("documind.storage.bucket", () -> BUCKET_NAME);
        };
    }

    @Bean
    SmartInitializingSingleton ensureMinioBucketExists(S3Client s3Client, StorageProperties storageProperties) {
        return () -> {
            String bucket = storageProperties.bucket();
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException ex) {
                createBucketIdempotently(s3Client, bucket);
            } catch (S3Exception ex) {
                if (ex.statusCode() == 404) {
                    createBucketIdempotently(s3Client, bucket);
                } else {
                    throw ex;
                }
            }
        };
    }

    private static void createBucketIdempotently(S3Client s3Client, String bucket) {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
            // Bucket already provisioned
        }
    }
}
