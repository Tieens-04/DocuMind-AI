package com.documind.controller;

import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.controller.dto.auth.UserProfileResponse;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Real-container E2E proof of Fail-Closed {@code 503 AUTH_STATE_STORE_UNAVAILABLE} behavior (R-07)
 * and bounded Redis command timeout (R-04) when Redis is paused or stopped — without mocking
 * {@code StringRedisTemplate} and without disturbing the JVM-wide shared Redis container.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=false"
    }
)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    AuthFailClosedRedisUnavailableIntegrationTest.IsolatedRedisConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class
})
class AuthFailClosedRedisUnavailableIntegrationTest {

    private static final String HR_MANAGER_EMAIL = "manager.hr@documind.ai";
    private static final String SEED_DEV_PASSWORD = "DocuMind@2026!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private GenericContainer<?> isolatedRedisContainer;

    @BeforeEach
    void setUp() throws InterruptedException {
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            null,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            null,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
    }

    @Test
    void returns503FailClosedFastWhenRedisPausedOrStoppedWhileStillAllowingRefreshWithStaleBearer() {
        ResponseEntity<AuthTokenResponse> loginResp = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", HR_MANAGER_EMAIL, "password", SEED_DEV_PASSWORD),
            AuthTokenResponse.class
        );
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthTokenResponse tokens = Objects.requireNonNull(loginResp.getBody());

        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(tokens.accessToken());

        // Sanity check while Redis is healthy
        ResponseEntity<UserProfileResponse> healthyMe = restTemplate.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(bearer),
            UserProfileResponse.class
        );
        assertThat(healthyMe.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 1. Pause Redis container to prove bounded 500ms Redis command timeout (R-04) + fail-closed 503 (R-07)
        String containerId = isolatedRedisContainer.getContainerId();
        isolatedRedisContainer.getDockerClient().pauseContainerCmd(containerId).exec();
        try {
            ResponseEntity<ProblemDetail> pausedMe = assertTimeoutPreemptively(
                Duration.ofSeconds(8),
                () -> restTemplate.exchange(
                    "/api/v1/auth/me",
                    HttpMethod.GET,
                    new HttpEntity<>(bearer),
                    ProblemDetail.class
                )
            );
            assertFailClosed503(pausedMe);

            ResponseEntity<ProblemDetail> pausedDocs = assertTimeoutPreemptively(
                Duration.ofSeconds(8),
                () -> restTemplate.exchange(
                    "/api/v1/documents",
                    HttpMethod.GET,
                    new HttpEntity<>(bearer),
                    ProblemDetail.class
                )
            );
            assertFailClosed503(pausedDocs);

            // R-03: /auth/refresh skips JwtAuthenticationFilter so even with a Bearer header it does not depend on Redis
            String initialRefreshToken = tokens.refreshToken();
            HttpHeaders refreshHeaders = new HttpHeaders();
            refreshHeaders.setBearerAuth(tokens.accessToken());
            refreshHeaders.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<AuthTokenResponse> refreshWhileRedisPaused = assertTimeoutPreemptively(
                Duration.ofSeconds(8),
                () -> restTemplate.postForEntity(
                    "/api/v1/auth/refresh",
                    new HttpEntity<>(Map.of("refreshToken", initialRefreshToken), refreshHeaders),
                    AuthTokenResponse.class
                )
            );
            assertThat(refreshWhileRedisPaused.getStatusCode()).isEqualTo(HttpStatus.OK);
            AuthTokenResponse refreshed = Objects.requireNonNull(refreshWhileRedisPaused.getBody());
            bearer.setBearerAuth(refreshed.accessToken());
        } finally {
            isolatedRedisContainer.getDockerClient().unpauseContainerCmd(containerId).exec();
        }

        // 2. Stop Redis container (connection refused) -> fail-closed 503 on both /me and /logout without mutating DB
        isolatedRedisContainer.stop();

        ResponseEntity<ProblemDetail> stoppedLogout = assertTimeoutPreemptively(
            Duration.ofSeconds(8),
            () -> restTemplate.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(bearer),
                ProblemDetail.class
            )
        );
        assertFailClosed503(stoppedLogout);

        Integer activeTokens = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            AbstractIntegrationTest.HR_MANAGER_USER_ID
        );
        assertThat(activeTokens).isEqualTo(1);
    }

    private static void assertFailClosed503(ResponseEntity<ProblemDetail> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        ProblemDetail body = Objects.requireNonNull(response.getBody());
        assertThat(body.getProperties()).containsEntry("errorCode", "AUTH_STATE_STORE_UNAVAILABLE");
        assertThat(body.getDetail())
            .doesNotContain("localhost")
            .doesNotContain("127.0.0.1")
            .doesNotContain("6379");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedRedisConfig {

        @Bean(initMethod = "start", destroyMethod = "stop")
        @ServiceConnection(name = "redis")
        GenericContainer<?> isolatedRedisContainer() {
            return new GenericContainer<>(DockerImageName.parse(RedisTestcontainersConfig.REDIS_IMAGE))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());
        }
    }
}
