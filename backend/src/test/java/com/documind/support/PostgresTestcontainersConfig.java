package com.documind.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresTestcontainersConfig.ContainerOnly.class)
public class PostgresTestcontainersConfig {

    public static final int EMBEDDING_DIMENSIONS = 1024;
    public static final String PGVECTOR_IMAGE =
        "pgvector/pgvector:pg16@sha256:484e838076e04939d27d3e176e91c86eea96236126023be8d3059761122e579f";

    private static final class SharedPostgresContainer extends PostgreSQLContainer<SharedPostgresContainer> {
        SharedPostgresContainer(DockerImageName dockerImageName) {
            super(dockerImageName);
        }

        @Override
        public void stop() {
            // Keep JVM-wide singleton container alive across @DirtiesContext closes; Ryuk reaps on JVM exit.
        }
    }

    private static final PostgreSQLContainer<?> SHARED_POSTGRES = new SharedPostgresContainer(
        DockerImageName.parse(PGVECTOR_IMAGE).asCompatibleSubstituteFor("postgres")
    ).withCommand("postgres", "-c", "max_connections=300");

    static {
        SHARED_POSTGRES.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ContainerOnly {

        @Bean(destroyMethod = "")
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return SHARED_POSTGRES;
        }
    }

    @Bean
    @Primary
    EmbeddingModel deterministicTestEmbeddingModel() {
        return new DeterministicTestEmbeddingModel();
    }

    private static final class DeterministicTestEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> instructions = request.getInstructions();
            List<Embedding> embeddings = new ArrayList<>(instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(deterministicVector(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            String text = (document != null && document.getText() != null) ? document.getText() : "";
            return deterministicVector(text);
        }

        @Override
        public int dimensions() {
            return EMBEDDING_DIMENSIONS;
        }

        private static float[] deterministicVector(String text) {
            String safeText = text == null ? "" : text;
            byte[] seed = sha256(safeText.getBytes(StandardCharsets.UTF_8));
            long state = ByteBuffer.wrap(seed, 0, Long.BYTES).getLong();
            if (state == 0L) {
                state = 0x9E3779B97F4A7C15L;
            }

            float[] vector = new float[EMBEDDING_DIMENSIONS];
            double sumSquares = 0.0;
            for (int i = 0; i < EMBEDDING_DIMENSIONS; i++) {
                // SplitMix64 step for deterministic unit-normalized pseudo-random components
                state += 0x9E3779B97F4A7C15L;
                long z = state;
                z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                z = z ^ (z >>> 31);
                float component = ((z >>> 8) / (float) (1L << 56)) * 2.0f - 1.0f;
                vector[i] = component;
                sumSquares += (double) component * component;
            }

            double norm = Math.sqrt(sumSquares);
            if (norm == 0.0) {
                vector[0] = 1.0f;
                return vector;
            }
            for (int i = 0; i < EMBEDDING_DIMENSIONS; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
            return vector;
        }

        private static byte[] sha256(byte[] input) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(input);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
            }
        }
    }
}
