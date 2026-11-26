package com.documind.controller;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.controller.dto.auth.UserProfileResponse;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.infrastructure.security.DevHeaderAuthenticationFilter;
import com.documind.infrastructure.security.JwtProperties;
import com.documind.infrastructure.security.JwtService;
import com.documind.infrastructure.security.ProblemDetailAuthenticationEntryPoint;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.service.auth.RedisTokenBlacklistService;
import com.documind.service.auth.RefreshTokenHasher;
import com.documind.service.document.CallerContextResolver;
import com.documind.service.document.DevFallbackCallerContextResolver;
import com.documind.service.document.ProdCallerContextResolver;
import com.documind.service.rag.RagConstants;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=false",
        "documind.rate-limit.chat.viewer-limit=500",
        "documind.rate-limit.chat.manager-limit=500",
        "documind.rate-limit.chat.admin-limit=500",
        "documind.rate-limit.upload.manager-limit=200",
        "documind.rate-limit.upload.admin-limit=200"
    }
)
@ActiveProfiles("dev")
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class,
    AuthAndJwtSecurityIntegrationTest.SseAsyncTestControllerConfig.class
})
class AuthAndJwtSecurityIntegrationTest {

    private static final String SEED_DEV_PASSWORD = "DocuMind@2026!";
    private static final String ADMIN_EMAIL = "admin@documind.ai";
    private static final String HR_MANAGER_EMAIL = "manager.hr@documind.ai";
    private static final String SALES_EMPLOYEE_EMAIL = "employee.sales@documind.ai";
    private static final UUID HR_MANAGER_USER_ID = AbstractIntegrationTest.HR_MANAGER_USER_ID;
    private static final UUID SALES_EMPLOYEE_USER_ID = AbstractIntegrationTest.SALES_EMPLOYEE_USER_ID;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CallerContextResolver callerContextResolver;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SseAsyncTestControllerConfig.AsyncDispatchAuditProbe asyncDispatchAuditProbe;

    @Autowired
    private SseAsyncTestControllerConfig.PasswordMatchPauseProbe passwordMatchPauseProbe;

    @BeforeEach
    void setUp() throws InterruptedException {
        asyncDispatchAuditProbe.reset();
        passwordMatchPauseProbe.reset();
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
        AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        jdbcTemplate.update("UPDATE users SET is_active = TRUE WHERE id = ?", HR_MANAGER_USER_ID);
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
    }

    @Test
    void devProfileDisablesHeaderFallbackByDefaultAndEnforcesRealJwtAndAuditLogs() {
        assertThat(applicationContext.getBeansOfType(DevFallbackCallerContextResolver.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DevHeaderAuthenticationFilter.class)).isEmpty();
        assertThat(callerContextResolver).isInstanceOf(ProdCallerContextResolver.class);
        FilterRegistrationBean<?> jwtFilterRegistration = applicationContext.getBean(
            "jwtAuthenticationFilterRegistration",
            FilterRegistrationBean.class
        );
        assertThat(jwtFilterRegistration.isEnabled()).isFalse();

        // Public endpoints remain open
        ResponseEntity<String> healthResp = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(healthResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> openApiResp = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(openApiResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openApiResp.getBody()).contains("bearerAuth");

        // Spoofed X-* headers without Bearer token are rejected 401
        HttpHeaders spoofed = AbstractIntegrationTest.buildCallerHeaders(
            AbstractIntegrationTest.IT_DEPT_ID,
            "SYSTEM_ADMIN",
            AbstractIntegrationTest.ADMIN_USER_ID
        );
        ResponseEntity<ProblemDetail> unauthenticatedMe = restTemplate.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(spoofed),
            ProblemDetail.class
        );
        assertThat(unauthenticatedMe.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthenticatedMe.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo("Bearer realm=\"DocuMind AI\"");
        assertThat(Objects.requireNonNull(unauthenticatedMe.getBody()).getProperties())
            .containsEntry("errorCode", "AUTHENTICATION_REQUIRED");

        ResponseEntity<ProblemDetail> unauthenticatedDocs = restTemplate.exchange(
            "/api/v1/documents",
            HttpMethod.GET,
            new HttpEntity<>(spoofed),
            ProblemDetail.class
        );
        assertThat(unauthenticatedDocs.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Successful login stores hashed refresh token and AUTH_LOGIN audit entry
        AuthTokenResponse login = loginAs(HR_MANAGER_EMAIL, SEED_DEV_PASSWORD);
        assertThat(login.tokenType()).isEqualTo("Bearer");
        assertThat(login.expiresIn()).isEqualTo(900L);
        assertThat(login.refreshExpiresIn()).isEqualTo(604800L);
        assertThat(login.user().id()).isEqualTo(HR_MANAGER_USER_ID);
        assertThat(login.user().departmentCode()).isEqualTo("HR");

        String expectedHash = RefreshTokenHasher.sha256Hex(login.refreshToken());
        Integer tokenRowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND token_hash = ? AND revoked_at IS NULL",
            Integer.class,
            HR_MANAGER_USER_ID,
            expectedHash
        );
        assertThat(tokenRowCount).isEqualTo(1);

        Integer loginAudit200 = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action_type = 'AUTH_LOGIN' AND status_code = 200",
            Integer.class,
            HR_MANAGER_USER_ID
        );
        assertThat(loginAudit200).isEqualTo(1);

        // Bearer JWT accesses /me and /documents cleanly
        ResponseEntity<UserProfileResponse> meResp = restTemplate.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(login.accessToken())),
            UserProfileResponse.class
        );
        assertThat(meResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(meResp.getBody()).email()).isEqualTo(HR_MANAGER_EMAIL);

        ResponseEntity<PageResponse<Object>> docsResp = restTemplate.exchange(
            "/api/v1/documents",
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(login.accessToken())),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(docsResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // /api/v1/chat/ask rejects unauthenticated requests with 401 and succeeds with real Bearer JWT
        HttpHeaders unauthChatHeaders = new HttpHeaders();
        unauthChatHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ProblemDetail> unauthChatResp = restTemplate.postForEntity(
            "/api/v1/chat/ask",
            new HttpEntity<>(Map.of("question", "Chính sách thử việc là gì?"), unauthChatHeaders),
            ProblemDetail.class
        );
        assertThat(unauthChatResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthChatResp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_BEARER);

        HttpHeaders authChatHeaders = bearerHeaders(login.accessToken());
        authChatHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ChatAskResponse> authChatResp = restTemplate.postForEntity(
            "/api/v1/chat/ask",
            new HttpEntity<>(Map.of("question", "Chính sách thử việc là gì?"), authChatHeaders),
            ChatAskResponse.class
        );
        assertThat(authChatResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatAskResponse chatBody = Objects.requireNonNull(authChatResp.getBody());
        assertThat(chatBody.abstained()).isTrue();
        assertThat(chatBody.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);

        // @EnableMethodSecurity (@PreAuthorize) enforces 403 ACCESS_DENIED for non-admin and 200 for SYSTEM_ADMIN
        AuthTokenResponse employeeLogin = loginAs(SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD);
        ResponseEntity<ProblemDetail> nonAdminMethodResp = restTemplate.exchange(
            "/api/v1/test-sse/admin-only",
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(employeeLogin.accessToken())),
            ProblemDetail.class
        );
        assertThat(nonAdminMethodResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nonAdminMethodResp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(Objects.requireNonNull(nonAdminMethodResp.getBody()).getProperties())
            .containsEntry("errorCode", "ACCESS_DENIED");

        AuthTokenResponse adminLogin = loginAs(ADMIN_EMAIL, SEED_DEV_PASSWORD);
        ResponseEntity<String> adminMethodResp = restTemplate.exchange(
            "/api/v1/test-sse/admin-only",
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(adminLogin.accessToken())),
            String.class
        );
        assertThat(adminMethodResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminMethodResp.getBody()).isEqualTo("admin-ok");

        // Valid STANDARD_EMPLOYEE Bearer token ignores spoofed X-User-Role: SYSTEM_ADMIN header -> 403 Forbidden
        HttpHeaders employeeUploadHeaders = bearerHeaders(employeeLogin.accessToken());
        employeeUploadHeaders.set("X-User-Role", "SYSTEM_ADMIN");
        employeeUploadHeaders.set("X-Department-Id", AbstractIntegrationTest.IT_DEPT_ID.toString());
        employeeUploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
        ByteArrayResource txtResource = new ByteArrayResource("hello".getBytes()) {
            @Override
            public String getFilename() {
                return "spoof-attempt.txt";
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.TEXT_PLAIN);
        multipartBody.add("file", new HttpEntity<>(txtResource, partHeaders));
        ResponseEntity<ProblemDetail> spoofedUploadResp = restTemplate.postForEntity(
            "/api/v1/documents/upload",
            new HttpEntity<>(multipartBody, employeeUploadHeaders),
            ProblemDetail.class
        );
        assertThat(spoofedUploadResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Wrong password -> 401 + AUTH_LOGIN audit 401
        ResponseEntity<ProblemDetail> wrongPassResp = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", HR_MANAGER_EMAIL, "password", "WrongPassword123!"),
            ProblemDetail.class
        );
        assertThat(wrongPassResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Objects.requireNonNull(wrongPassResp.getBody()).getProperties())
            .containsEntry("errorCode", "INVALID_CREDENTIALS");

        // Disabled user: wrong password -> 401, right password -> 403 ACCOUNT_DISABLED
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE id = ?", HR_MANAGER_USER_ID);
        ResponseEntity<ProblemDetail> disabledWrongPass = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", HR_MANAGER_EMAIL, "password", "WrongPassword123!"),
            ProblemDetail.class
        );
        assertThat(disabledWrongPass.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<ProblemDetail> disabledRightPass = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", HR_MANAGER_EMAIL, "password", SEED_DEV_PASSWORD),
            ProblemDetail.class
        );
        assertThat(disabledRightPass.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(Objects.requireNonNull(disabledRightPass.getBody()).getProperties())
            .containsEntry("errorCode", "ACCOUNT_DISABLED");

        Integer loginAudit401 = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action_type = 'AUTH_LOGIN' AND status_code = 401",
            Integer.class,
            HR_MANAGER_USER_ID
        );
        Integer loginAudit403 = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action_type = 'AUTH_LOGIN' AND status_code = 403",
            Integer.class,
            HR_MANAGER_USER_ID
        );
        assertThat(loginAudit401).isEqualTo(2);
        assertThat(loginAudit403).isEqualTo(1);

        // /me reflects fresh DB disabled status -> 401 with RFC 6750 WWW-Authenticate
        ResponseEntity<ProblemDetail> meWhenDisabled = restTemplate.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(login.accessToken())),
            ProblemDetail.class
        );
        assertThat(meWhenDisabled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(meWhenDisabled.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_INVALID_TOKEN);
        assertThat(Objects.requireNonNull(meWhenDisabled.getBody()).getProperties())
            .containsEntry("errorCode", "INVALID_TOKEN");

        // R-01 Regression: Disabled user calling /refresh with a live RT must NOT self-deadlock on real PostgreSQL
        ResponseEntity<ProblemDetail> refreshWhenDisabled = assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                Map.of("refreshToken", login.refreshToken()),
                ProblemDetail.class
            )
        );
        assertThat(refreshWhenDisabled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshWhenDisabled.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_INVALID_TOKEN);
        assertThat(Objects.requireNonNull(refreshWhenDisabled.getBody()).getProperties())
            .containsEntry("errorCode", "INVALID_TOKEN");

        Integer activeTokensAfterDisable = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            HR_MANAGER_USER_ID
        );
        assertThat(activeTokensAfterDisable).isZero();
    }

    @Test
    void refreshRotatesTokenTriggersReuseDetectionAndSerializesConcurrentVirtualThreads() throws Exception {
        AuthTokenResponse initial = loginAs(HR_MANAGER_EMAIL, SEED_DEV_PASSWORD);

        ResponseEntity<AuthTokenResponse> rotatedResp = restTemplate.postForEntity(
            "/api/v1/auth/refresh",
            Map.of("refreshToken", initial.refreshToken()),
            AuthTokenResponse.class
        );
        assertThat(rotatedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthTokenResponse rotated = Objects.requireNonNull(rotatedResp.getBody());
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());

        String oldHash = RefreshTokenHasher.sha256Hex(initial.refreshToken());
        String newHash = RefreshTokenHasher.sha256Hex(rotated.refreshToken());
        String recordedReplacement = jdbcTemplate.queryForObject(
            "SELECT replaced_by_hash FROM refresh_tokens WHERE token_hash = ?",
            String.class,
            oldHash
        );
        assertThat(recordedReplacement).isEqualTo(newHash);

        // Replaying old RT1 detects reuse and revokes RT2 in PostgreSQL within a single transaction
        ResponseEntity<ProblemDetail> replayResp = restTemplate.postForEntity(
            "/api/v1/auth/refresh",
            Map.of("refreshToken", initial.refreshToken()),
            ProblemDetail.class
        );
        assertThat(replayResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replayResp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_INVALID_TOKEN);
        assertThat(Objects.requireNonNull(replayResp.getBody()).getProperties())
            .containsEntry("errorCode", "REFRESH_TOKEN_REUSED");

        Integer remainingActive = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            HR_MANAGER_USER_ID
        );
        assertThat(remainingActive).isZero();

        ResponseEntity<ProblemDetail> revokedChildResp = restTemplate.postForEntity(
            "/api/v1/auth/refresh",
            Map.of("refreshToken", rotated.refreshToken()),
            ProblemDetail.class
        );
        assertThat(revokedChildResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokedChildResp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_INVALID_TOKEN);
        assertThat(Objects.requireNonNull(revokedChildResp.getBody()).getProperties())
            .containsEntry("errorCode", "TOKEN_REVOKED");

        // DA-12 Reuse Revocation Contract: previously issued access JWTs (both initial and rotated) are immediately revoked 401
        assertInvalidBearerEndToEnd("Bearer " + initial.accessToken(), "TOKEN_REVOKED");
        assertInvalidBearerEndToEnd("Bearer " + rotated.accessToken(), "TOKEN_REVOKED");

        // Concurrent Virtual Thread refresh race on the same fresh refresh token
        AuthTokenResponse raceInitial = loginAs(SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<ResponseEntity<String>> task = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return restTemplate.postForEntity(
                    "/api/v1/auth/refresh",
                    Map.of("refreshToken", raceInitial.refreshToken()),
                    String.class
                );
            };
            Future<ResponseEntity<String>> f1 = executor.submit(task);
            Future<ResponseEntity<String>> f2 = executor.submit(task);
            ResponseEntity<String> r1 = f1.get(10, TimeUnit.SECONDS);
            ResponseEntity<String> r2 = f2.get(10, TimeUnit.SECONDS);

            List<Integer> statuses = List.of(r1.getStatusCode().value(), r2.getStatusCode().value());
            assertThat(statuses).doesNotContain(500);
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        }

        // Verify PostgreSQL state after concurrent race: loser triggered reuse revocation, zero active child branch survives
        Integer raceTotalRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?",
            Integer.class,
            SALES_EMPLOYEE_USER_ID
        );
        Integer raceActiveRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            SALES_EMPLOYEE_USER_ID
        );
        assertThat(raceTotalRows).isEqualTo(2);
        assertThat(raceActiveRows).isZero();
    }

    @Test
    void logoutBlacklistsAccessTokenInRedisRevokesRefreshTokenAndBlocksSubsequentAccess() {
        AuthTokenResponse session1 = loginAs(HR_MANAGER_EMAIL, SEED_DEV_PASSWORD);
        AuthTokenResponse session2 = loginAs(HR_MANAGER_EMAIL, SEED_DEV_PASSWORD);
        String jti1 = jwtService.parseAndValidateAccessToken(session1.accessToken()).jti();

        // Single-session logout with body {refreshToken}
        HttpHeaders logoutHeaders = bearerHeaders(session1.accessToken());
        logoutHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> logoutResp = restTemplate.exchange(
            "/api/v1/auth/logout",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("refreshToken", session1.refreshToken()), logoutHeaders),
            Void.class
        );
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Redis blacklist key exists with positive TTL <= 900s
        String redisKey = RedisTokenBlacklistService.KEY_PREFIX + jti1;
        assertThat(stringRedisTemplate.hasKey(redisKey)).isTrue();
        Long ttlSeconds = stringRedisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull().isPositive().isLessThanOrEqualTo(900L);

        // Logged-out access token is blocked 401 TOKEN_REVOKED immediately with RFC 6750 header
        ResponseEntity<ProblemDetail> revokedMe = restTemplate.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(session1.accessToken())),
            ProblemDetail.class
        );
        assertThat(revokedMe.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokedMe.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_INVALID_TOKEN);
        assertThat(Objects.requireNonNull(revokedMe.getBody()).getProperties())
            .containsEntry("errorCode", "TOKEN_REVOKED");

        ResponseEntity<ProblemDetail> revokedDocs = restTemplate.exchange(
            "/api/v1/documents",
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(session1.accessToken())),
            ProblemDetail.class
        );
        assertThat(revokedDocs.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokedDocs.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_INVALID_TOKEN);

        // R-10 Regression: Logout with another user's RT returns 403 ACCESS_DENIED (no WWW-Authenticate)
        // and does NOT blacklist the caller's valid access token or revoke the victim's token
        AuthTokenResponse otherUserSession = loginAs(SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD);
        HttpHeaders crossLogoutHeaders = bearerHeaders(session2.accessToken());
        crossLogoutHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ProblemDetail> crossLogoutResp = restTemplate.exchange(
            "/api/v1/auth/logout",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("refreshToken", otherUserSession.refreshToken()), crossLogoutHeaders),
            ProblemDetail.class
        );
        assertThat(crossLogoutResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(crossLogoutResp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(Objects.requireNonNull(crossLogoutResp.getBody()).getProperties())
            .containsEntry("errorCode", "ACCESS_DENIED");

        // Caller's session2 access token was NOT blacklisted in Redis and still works on /me
        ResponseEntity<UserProfileResponse> session2Me = restTemplate.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(session2.accessToken())),
            UserProfileResponse.class
        );
        assertThat(session2Me.getStatusCode()).isEqualTo(HttpStatus.OK);
        Integer victimActiveTokens = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            SALES_EMPLOYEE_USER_ID
        );
        assertThat(victimActiveTokens).isEqualTo(1);

        // R-05 Regression: Single-session logout with an already-rotated RT1 revokes its child RT2 in PostgreSQL
        ResponseEntity<AuthTokenResponse> rotatedSession2Resp = restTemplate.postForEntity(
            "/api/v1/auth/refresh",
            Map.of("refreshToken", session2.refreshToken()),
            AuthTokenResponse.class
        );
        assertThat(rotatedSession2Resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthTokenResponse rotatedSession2 = Objects.requireNonNull(rotatedSession2Resp.getBody());

        HttpHeaders staleRtLogoutHeaders = bearerHeaders(rotatedSession2.accessToken());
        staleRtLogoutHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> staleRtLogoutResp = restTemplate.exchange(
            "/api/v1/auth/logout",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("refreshToken", session2.refreshToken()), staleRtLogoutHeaders),
            Void.class
        );
        assertThat(staleRtLogoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer activeTokens = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            HR_MANAGER_USER_ID
        );
        assertThat(activeTokens).isZero();

        ResponseEntity<ProblemDetail> childAfterStaleLogout = restTemplate.postForEntity(
            "/api/v1/auth/refresh",
            Map.of("refreshToken", rotatedSession2.refreshToken()),
            ProblemDetail.class
        );
        assertThat(childAfterStaleLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Objects.requireNonNull(childAfterStaleLogout.getBody()).getProperties())
            .containsEntry("errorCode", "TOKEN_REVOKED");

        Integer logoutAudits = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action_type = 'AUTH_LOGOUT' AND status_code = 204",
            Integer.class,
            HR_MANAGER_USER_ID
        );
        assertThat(logoutAudits).isEqualTo(2);
    }

    @Test
    void rejectsExpiredWrongSignatureUnsignedMalformedAndOpaqueTokensEndToEndAndAllowsStaleBearerOnRefreshAndLogin() {
        AuthTokenResponse validLogin = loginAs(HR_MANAGER_EMAIL, SEED_DEV_PASSWORD);

        // Build real expired token signed with the app's active secret
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");
        JwtService pastIssuer = new JwtService(
            jwtProperties,
            devEnv,
            Clock.fixed(Instant.now().minus(Duration.ofMinutes(30)), ZoneOffset.UTC)
        );
        UserEntity hrEntity = buildUserEntityForJwt(HR_MANAGER_USER_ID, AbstractIntegrationTest.HR_DEPT_ID);
        String expiredJwt = pastIssuer.generateAccessToken(hrEntity).token();

        // Build wrong-signature token signed with a different strong secret
        JwtService wrongSecretIssuer = new JwtService(
            new JwtProperties(
                "different-attacker-hmac-sha256-secret-key-min-32-bytes-long",
                jwtProperties.issuer(),
                Duration.ofMinutes(15),
                Duration.ofDays(7)
            ),
            devEnv
        );
        String wrongSigJwt = wrongSecretIssuer.generateAccessToken(hrEntity).token();

        // Build unsigned alg=none token
        String[] parts = validLogin.accessToken().split("\\.");
        String algNoneHeader = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String algNoneJwt = algNoneHeader + "." + parts[1] + ".";

        // Verify all invalid token variants are rejected E2E through real SecurityFilterChain with 401 + RFC 6750 header
        assertInvalidBearerEndToEnd("Bearer " + expiredJwt, "TOKEN_EXPIRED");
        assertInvalidBearerEndToEnd("Bearer " + wrongSigJwt, "INVALID_TOKEN");
        assertInvalidBearerEndToEnd("Bearer " + algNoneJwt, "INVALID_TOKEN");
        assertInvalidBearerEndToEnd("Bearer", "INVALID_TOKEN");
        assertInvalidBearerEndToEnd("Bearer not-a-jwt", "INVALID_TOKEN");
        assertInvalidBearerEndToEnd("Bearer " + validLogin.refreshToken(), "INVALID_TOKEN");

        // R-03 Regression: SPA sending expired Bearer header to /auth/refresh and /auth/login MUST succeed 200 OK
        HttpHeaders expiredBearerJson = bearerHeaders(expiredJwt);
        expiredBearerJson.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<AuthTokenResponse> refreshWithExpiredBearer = restTemplate.postForEntity(
            "/api/v1/auth/refresh",
            new HttpEntity<>(Map.of("refreshToken", validLogin.refreshToken()), expiredBearerJson),
            AuthTokenResponse.class
        );
        assertThat(refreshWithExpiredBearer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(refreshWithExpiredBearer.getBody()).accessToken()).isNotBlank();

        ResponseEntity<AuthTokenResponse> loginWithExpiredBearer = restTemplate.postForEntity(
            "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", HR_MANAGER_EMAIL, "password", SEED_DEV_PASSWORD), expiredBearerJson),
            AuthTokenResponse.class
        );
        assertThat(loginWithExpiredBearer.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void asyncSseEndpointStreamsWithValidJwtAndRejectsMissingOrLoggedOutJwt() throws Exception {
        AuthTokenResponse login = loginAs(HR_MANAGER_EMAIL, SEED_DEV_PASSWORD);

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        URI sseUri = URI.create("http://localhost:" + port + "/api/v1/test-sse/stream");

        // Unauthenticated SSE request -> 401
        HttpResponse<String> unauthResp = httpClient.send(
            HttpRequest.newBuilder(sseUri).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(unauthResp.statusCode()).isEqualTo(401);

        // Authenticated SSE request -> 200 OK across DispatcherType.ASYNC emissions
        HttpResponse<String> authResp = httpClient.send(
            HttpRequest.newBuilder(sseUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(authResp.statusCode()).isEqualTo(200);
        assertThat(authResp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
            .contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(authResp.body())
            .contains("event:token")
            .contains("data:chunk-1")
            .contains("data:chunk-2")
            .contains("event:done")
            .contains(HR_MANAGER_USER_ID.toString());

        // R-08 Regression: Server-side proof that DispatcherType.ASYNC passed AuthorizationFilter with restored SecurityContext
        assertThat(asyncDispatchAuditProbe.asyncDispatchCount()).isGreaterThanOrEqualTo(1);
        Authentication capturedAsyncAuth = asyncDispatchAuditProbe.lastAsyncAuthentication();
        assertThat(capturedAsyncAuth).isNotNull();
        assertThat(capturedAsyncAuth.isAuthenticated()).isTrue();
        assertThat(capturedAsyncAuth.getPrincipal()).isInstanceOf(CustomUserDetails.class);
        assertThat(((CustomUserDetails) capturedAsyncAuth.getPrincipal()).userId()).isEqualTo(HR_MANAGER_USER_ID);

        // Logout then re-attempt SSE -> 401 TOKEN_REVOKED
        ResponseEntity<Void> logoutResp = restTemplate.exchange(
            "/api/v1/auth/logout",
            HttpMethod.POST,
            new HttpEntity<>(bearerHeaders(login.accessToken())),
            Void.class
        );
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        HttpResponse<String> revokedSseResp = httpClient.send(
            HttpRequest.newBuilder(sseUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(revokedSseResp.statusCode()).isEqualTo(401);
        assertThat(revokedSseResp.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(""))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_INVALID_TOKEN);
        JsonNode revokedJson = objectMapper.readTree(revokedSseResp.body());
        assertThat(revokedJson.path("errorCode").asText()).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void concurrentLoginAndRefreshDuringAdminPasswordResetLeavesZeroValidOldSessions() throws Exception {
        AuthTokenResponse adminSession = loginAs(ADMIN_EMAIL, SEED_DEV_PASSWORD);
        HttpHeaders adminHeaders = bearerHeaders(adminSession.accessToken());
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);

        String targetEmail = "race.reset." + UUID.randomUUID() + "@documind.ai";
        String oldPassword = "OldPassword@2026!";
        String newPassword = "NewPassword@2026!";
        String newerPassword = "NewerPassword@2026!";

        ResponseEntity<JsonNode> createdResp = restTemplate.postForEntity(
            "/api/v1/admin/users",
            new HttpEntity<>(
                Map.of(
                    "email", targetEmail,
                    "fullName", "Race Reset User",
                    "password", oldPassword,
                    "role", "STANDARD_EMPLOYEE",
                    "departmentId", AbstractIntegrationTest.HR_DEPT_ID.toString()
                ),
                adminHeaders
            ),
            JsonNode.class
        );
        assertThat(createdResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID targetUserId = UUID.fromString(Objects.requireNonNull(createdResp.getBody()).path("id").asText());

        // 1. Deterministic TOCTOU window reproduction: login matches oldPassword in BCrypt, pauses while admin resets password, then enters DB tx
        CountDownLatch matchedOldPassword = new CountDownLatch(1);
        CountDownLatch allowLoginProceed = new CountDownLatch(1);
        passwordMatchPauseProbe.arm(oldPassword, matchedOldPassword, allowLoginProceed);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ResponseEntity<String>> inFlightOldLogin = executor.submit(() -> restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("email", targetEmail, "password", oldPassword),
                String.class
            ));

            assertThat(matchedOldPassword.await(5, TimeUnit.SECONDS)).isTrue();

            ResponseEntity<Void> resetResp = restTemplate.exchange(
                "/api/v1/admin/users/" + targetUserId + "/password",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("newPassword", newPassword), adminHeaders),
                Void.class
            );
            assertThat(resetResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            allowLoginProceed.countDown();

            ResponseEntity<String> oldLoginResult = inFlightOldLogin.get(10, TimeUnit.SECONDS);
            assertThat(oldLoginResult.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(objectMapper.readTree(oldLoginResult.getBody()).path("errorCode").asText())
                .isEqualTo("INVALID_CREDENTIALS");
        } finally {
            passwordMatchPauseProbe.reset();
        }

        Integer activeTokensAfterDeterministicRace = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            targetUserId
        );
        assertThat(activeTokensAfterDeterministicRace).isZero();

        // 2. High-concurrency Virtual Thread burst: simultaneous logins (newPassword about to become old) + refresh + resetPassword -> newerPassword
        AuthTokenResponse preRaceSession = loginAs(targetEmail, newPassword);
        int loginWorkers = 6;
        int refreshWorkers = 4;
        CyclicBarrier burstBarrier = new CyclicBarrier(loginWorkers + refreshWorkers + 1);
        List<Future<ResponseEntity<String>>> oldAuthFutures = new java.util.ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < loginWorkers; i++) {
                oldAuthFutures.add(executor.submit(() -> {
                    burstBarrier.await(5, TimeUnit.SECONDS);
                    return restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        Map.of("email", targetEmail, "password", newPassword),
                        String.class
                    );
                }));
            }
            for (int i = 0; i < refreshWorkers; i++) {
                oldAuthFutures.add(executor.submit(() -> {
                    burstBarrier.await(5, TimeUnit.SECONDS);
                    return restTemplate.postForEntity(
                        "/api/v1/auth/refresh",
                        Map.of("refreshToken", preRaceSession.refreshToken()),
                        String.class
                    );
                }));
            }
            Future<ResponseEntity<Void>> resetFuture = executor.submit(() -> {
                burstBarrier.await(5, TimeUnit.SECONDS);
                return restTemplate.exchange(
                    "/api/v1/admin/users/" + targetUserId + "/password",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("newPassword", newerPassword), adminHeaders),
                    Void.class
                );
            });

            ResponseEntity<Void> burstResetResp = resetFuture.get(15, TimeUnit.SECONDS);
            assertThat(burstResetResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            List<AuthTokenResponse> transientlyIssuedBeforeReset = new java.util.ArrayList<>();
            for (Future<ResponseEntity<String>> f : oldAuthFutures) {
                ResponseEntity<String> r = f.get(15, TimeUnit.SECONDS);
                assertThat(r.getStatusCode().value()).isIn(200, 401);
                if (r.getStatusCode().value() == 200) {
                    transientlyIssuedBeforeReset.add(objectMapper.readValue(r.getBody(), AuthTokenResponse.class));
                }
            }

            // After resetPassword completes, ZERO active refresh tokens may remain and EVERY transiently issued token must fail 401!
            Integer activeAfterBurst = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                targetUserId
            );
            assertThat(activeAfterBurst).isZero();

            for (AuthTokenResponse staleTokens : transientlyIssuedBeforeReset) {
                assertInvalidBearerEndToEnd("Bearer " + staleTokens.accessToken(), "TOKEN_REVOKED");
                ResponseEntity<ProblemDetail> staleRefresh = restTemplate.postForEntity(
                    "/api/v1/auth/refresh",
                    Map.of("refreshToken", staleTokens.refreshToken()),
                    ProblemDetail.class
                );
                assertThat(staleRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }

        // Only the final newerPassword can authenticate now
        AuthTokenResponse freshValidSession = loginAs(targetEmail, newerPassword);
        assertThat(freshValidSession.accessToken()).isNotBlank();
    }

    private void assertInvalidBearerEndToEnd(String authorizationHeader, String expectedErrorCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        ResponseEntity<ProblemDetail> resp = restTemplate.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            ProblemDetail.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(ProblemDetailAuthenticationEntryPoint.WWW_AUTHENTICATE_INVALID_TOKEN);
        assertThat(Objects.requireNonNull(resp.getBody()).getProperties())
            .containsEntry("errorCode", expectedErrorCode);
    }

    private static UserEntity buildUserEntityForJwt(UUID userId, UUID deptId) {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(deptId);
        dept.setCode("HR");
        dept.setName("Human Resources");

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setDepartment(dept);
        user.setEmail(HR_MANAGER_EMAIL);
        user.setFullName("HR Department Manager");
        user.setPasswordHash("unused");
        user.setRole(Role.DEPARTMENT_MANAGER);
        user.setIsActive(true);
        return user;
    }

    private AuthTokenResponse loginAs(String email, String password) {
        ResponseEntity<AuthTokenResponse> response = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", email, "password", password),
            AuthTokenResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Objects.requireNonNull(response.getBody());
    }

    private static HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SseAsyncTestControllerConfig {

        @Bean
        AsyncDispatchAuditProbe asyncDispatchAuditProbe() {
            return new AsyncDispatchAuditProbe();
        }

        @Bean
        PasswordMatchPauseProbe passwordMatchPauseProbe() {
            return new PasswordMatchPauseProbe();
        }

        @Bean
        static BeanPostProcessor passwordEncoderPauseWrapper(ObjectProvider<PasswordMatchPauseProbe> probeProvider) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof PasswordEncoder delegate && !(bean instanceof PausablePasswordEncoder)) {
                        return new PausablePasswordEncoder(delegate, probeProvider);
                    }
                    return bean;
                }
            };
        }

        @Bean
        FilterRegistrationBean<OncePerRequestFilter> asyncDispatchAuditProbeRegistration(
            AsyncDispatchAuditProbe probe
        ) {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(probe);
            registration.setDispatcherTypes(EnumSet.of(DispatcherType.ASYNC));
            registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 10);
            registration.addUrlPatterns("/api/v1/test-sse/*");
            return registration;
        }

        static final class AsyncDispatchAuditProbe extends OncePerRequestFilter {

            private final AtomicInteger asyncDispatchCount = new AtomicInteger();
            private final AtomicReference<Authentication> lastAsyncAuthentication = new AtomicReference<>();

            void reset() {
                asyncDispatchCount.set(0);
                lastAsyncAuthentication.set(null);
            }

            int asyncDispatchCount() {
                return asyncDispatchCount.get();
            }

            Authentication lastAsyncAuthentication() {
                return lastAsyncAuthentication.get();
            }

            @Override
            protected boolean shouldNotFilterAsyncDispatch() {
                return false;
            }

            @Override
            protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
            ) throws ServletException, IOException {
                if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
                    asyncDispatchCount.incrementAndGet();
                    lastAsyncAuthentication.set(SecurityContextHolder.getContext().getAuthentication());
                }
                filterChain.doFilter(request, response);
            }
        }

        @RestController
        static class TestSseController {

            @GetMapping(value = "/api/v1/test-sse/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
            public Flux<ServerSentEvent<String>> stream(@AuthenticationPrincipal CustomUserDetails principal) {
                UUID callerId = principal != null ? principal.userId() : null;
                return Flux.just("chunk-1", "chunk-2")
                    .delayElements(Duration.ofMillis(20))
                    .map(token -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(token)
                        .build())
                    .concatWithValues(ServerSentEvent.<String>builder()
                        .event("done")
                        .data(String.valueOf(callerId))
                        .build());
            }

            @GetMapping("/api/v1/test-sse/admin-only")
            @PreAuthorize("hasRole('SYSTEM_ADMIN')")
            public String adminOnly() {
                return "admin-ok";
            }
        }

        static final class PasswordMatchPauseProbe {

            private record Gate(String targetRawPassword, CountDownLatch entered, CountDownLatch proceed) {
            }

            private final AtomicReference<Gate> activeGate = new AtomicReference<>();

            void arm(String targetRawPassword, CountDownLatch entered, CountDownLatch proceed) {
                activeGate.set(new Gate(targetRawPassword, entered, proceed));
            }

            void reset() {
                Gate prev = activeGate.getAndSet(null);
                if (prev != null) {
                    prev.proceed().countDown();
                }
            }

            void onPostMatch(CharSequence rawPassword, boolean matched) {
                if (!matched || rawPassword == null) {
                    return;
                }
                Gate gate = activeGate.get();
                if (gate != null && gate.targetRawPassword().contentEquals(rawPassword) && activeGate.compareAndSet(gate, null)) {
                    gate.entered().countDown();
                    try {
                        gate.proceed().await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private static final class PausablePasswordEncoder implements PasswordEncoder {

            private final PasswordEncoder delegate;
            private final ObjectProvider<PasswordMatchPauseProbe> probeProvider;

            private PausablePasswordEncoder(PasswordEncoder delegate, ObjectProvider<PasswordMatchPauseProbe> probeProvider) {
                this.delegate = delegate;
                this.probeProvider = probeProvider;
            }

            @Override
            public String encode(CharSequence rawPassword) {
                return delegate.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                boolean matched = delegate.matches(rawPassword, encodedPassword);
                PasswordMatchPauseProbe probe = probeProvider.getIfAvailable();
                if (probe != null) {
                    probe.onPostMatch(rawPassword, matched);
                }
                return matched;
            }
        }
    }
}
