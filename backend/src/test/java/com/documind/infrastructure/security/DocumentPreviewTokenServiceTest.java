package com.documind.infrastructure.security;

import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidTokenException;
import com.documind.service.document.CallerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentPreviewTokenServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-23T04:00:00Z");
    private static final String SECRET = "preview-token-unit-test-hmac-secret-min-32-bytes";
    private static final UUID DOC_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID OTHER_DOC_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private JwtProperties jwtProperties;
    private MockEnvironment environment;
    private JwtService jwtService;
    private DocumentPreviewTokenService previewTokenService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        jwtProperties = new JwtProperties(SECRET, "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7));
        environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("documind.security.dev-caller-fallback.enabled", "false");
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        MonotonicClockMillis monotonic = new MonotonicClockMillis();
        jwtService = new JwtService(jwtProperties, environment, clock, monotonic);
        previewTokenService = new DocumentPreviewTokenService(
            jwtProperties,
            environment,
            new PreviewBaseUrlResolver("", 8081),
            Duration.ofMinutes(15),
            clock,
            monotonic
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void issuesAndVerifiesDomainSeparatedPreviewTokenAndBlocksCrossTokenConfusion() {
        CustomUserDetails principal = new CustomUserDetails(
            USER_ID,
            HR_DEPT_ID,
            "employee.hr@documind.ai",
            "HR Employee",
            Role.STANDARD_EMPLOYEE,
            "access-jti-123",
            FIXED_NOW.plusSeconds(900)
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        DocumentPreviewTokenService.IssuedPreviewUrl issued = previewTokenService.issuePreviewUrl(DOC_ID, 2, caller);

        assertThat(issued.baseUrl())
            .startsWith("http://localhost:8081/api/v1/documents/" + DOC_ID + "/content?token=");

        DocumentPreviewTokenService.VerifiedPreviewClaims verified = previewTokenService.verifyToken(issued.token(), DOC_ID);
        assertThat(verified.documentId()).isEqualTo(DOC_ID);
        assertThat(verified.docVersion()).isEqualTo(2);
        assertThat(verified.userId()).isEqualTo(USER_ID);
        assertThat(verified.departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(verified.role()).isEqualTo(Role.STANDARD_EMPLOYEE);
        assertThat(verified.jwtAuthenticated()).isTrue();
        assertThat(verified.accessTokenJti()).isEqualTo("access-jti-123");

        // Swapped document ID in path -> 403 DocumentAccessDeniedException
        assertThatThrownBy(() -> previewTokenService.verifyToken(issued.token(), OTHER_DOC_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // Preview token CANNOT be accepted as a Bearer access JWT
        assertThatThrownBy(() -> jwtService.parseAndValidateAccessToken(issued.token()))
            .isInstanceOf(InvalidTokenException.class);

        // Bearer access JWT CANNOT be accepted as a document preview token
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(HR_DEPT_ID);
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setDepartment(dept);
        user.setEmail("employee.hr@documind.ai");
        user.setFullName("HR Employee");
        user.setRole(Role.STANDARD_EMPLOYEE);
        String accessJwt = jwtService.generateAccessToken(user).token();

        assertThatThrownBy(() -> previewTokenService.verifyToken(accessJwt, DOC_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);
    }

    @Test
    void rejectsExpiredTamperedAndUnauthenticatedPreviewTokensAndResolvesServletAndScopedBaseUrls() {
        CustomUserDetails principal = new CustomUserDetails(
            USER_ID, HR_DEPT_ID, "employee.hr@documind.ai", "HR Employee", Role.STANDARD_EMPLOYEE, "jti-1", FIXED_NOW.plusSeconds(900)
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
        CallerContext caller = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");

        // Servlet request base URL resolution
        MockHttpServletRequest servletReq = new MockHttpServletRequest("GET", "/api/v1/documents/" + DOC_ID + "/preview-url");
        servletReq.setScheme("https");
        servletReq.setServerName("docs.enterprise.vn");
        servletReq.setServerPort(443);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletReq));

        DocumentPreviewTokenService.IssuedPreviewUrl httpsIssued = previewTokenService.issuePreviewUrl(DOC_ID, 1, caller);
        assertThat(httpsIssued.baseUrl()).startsWith("https://docs.enterprise.vn/api/v1/documents/" + DOC_ID + "/content?token=");

        // Scoped base URL override (used on async SSE Virtual Threads)
        String scopedResult = PreviewBaseUrlResolver.callWithBaseUrl(
            "http://localhost:19081/",
            () -> previewTokenService.issuePreviewUrl(DOC_ID, 1, caller).baseUrl()
        );
        assertThat(scopedResult).startsWith("http://localhost:19081/api/v1/documents/" + DOC_ID + "/content?token=");

        // Expired preview token -> 403 DocumentAccessDeniedException
        DocumentPreviewTokenService futureVerifier = new DocumentPreviewTokenService(
            jwtProperties,
            environment,
            PreviewBaseUrlResolver.fixed("http://localhost:8081"),
            Duration.ofMinutes(15),
            Clock.fixed(FIXED_NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC)
        );
        assertThatThrownBy(() -> futureVerifier.verifyToken(httpsIssued.token(), DOC_ID))
            .isInstanceOf(DocumentAccessDeniedException.class)
            .hasMessageContaining("expired");

        // Blank/malformed -> 403
        assertThatThrownBy(() -> previewTokenService.verifyToken("   ", DOC_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> previewTokenService.verifyToken("not.a.jwt", DOC_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // Unauthenticated context when dev-caller-fallback is disabled -> 403 on mint
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> previewTokenService.issuePreviewUrl(DOC_ID, 1, caller))
            .isInstanceOf(DocumentAccessDeniedException.class);
    }
}
