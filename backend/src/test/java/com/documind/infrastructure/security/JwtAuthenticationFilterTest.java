package com.documind.infrastructure.security;

import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.AuthStateStoreUnavailableException;
import com.documind.service.auth.TokenBlacklistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "filter-test-hmac-sha256-jwt-secret-key-min-32-bytes";
    private static final Instant FIXED_NOW = Instant.parse("2026-11-19T10:00:00Z");

    private JwtService jwtService;
    private TokenBlacklistService tokenBlacklistService;
    private ProblemDetailAuthenticationEntryPoint entryPoint;
    private HandlerExceptionResolver handlerExceptionResolver;
    private ObjectMapper objectMapper;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        JwtProperties properties = new JwtProperties(
            SECRET,
            "documind-ai",
            Duration.ofMinutes(15),
            Duration.ofDays(7)
        );
        jwtService = new JwtService(properties, new MockEnvironment(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        tokenBlacklistService = mock(TokenBlacklistService.class);
        objectMapper = Jackson2ObjectMapperBuilder.json()
            .mixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
        entryPoint = new ProblemDetailAuthenticationEntryPoint(objectMapper);
        handlerExceptionResolver = mock(HandlerExceptionResolver.class);
        filter = new JwtAuthenticationFilter(jwtService, tokenBlacklistService, entryPoint, handlerExceptionResolver);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void populatesSecurityContextStatelesslyAndRestoresOnAsyncDispatchWithoutRecheckingRedis() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        UserEntity user = buildUser(userId, deptId, "manager.hr@documind.ai", "HR Manager", Role.DEPARTMENT_MANAGER);
        JwtService.IssuedAccessToken issued = jwtService.generateAccessToken(user);
        when(tokenBlacklistService.isTokenRevoked(issued.jti(), userId, FIXED_NOW)).thenReturn(false);

        MockHttpServletRequest initialRequest = new MockHttpServletRequest("GET", "/api/v1/chat/stream");
        initialRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token());
        MockHttpServletResponse initialResponse = new MockHttpServletResponse();

        FilterChain initialChain = (req, res) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isInstanceOf(CustomUserDetails.class);
            CustomUserDetails principal = (CustomUserDetails) auth.getPrincipal();
            assertThat(principal.userId()).isEqualTo(userId);
            assertThat(principal.departmentId()).isEqualTo(deptId);
            assertThat(principal.role()).isEqualTo(Role.DEPARTMENT_MANAGER);
            assertThat(principal.tokenJti()).isEqualTo(issued.jti());
            assertThat(principal.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_DEPARTMENT_MANAGER");
        };

        assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();
        filter.doFilter(initialRequest, initialResponse, initialChain);

        verify(tokenBlacklistService, times(1)).isTokenRevoked(issued.jti(), userId, FIXED_NOW);
        org.springframework.security.web.context.RequestAttributeSecurityContextRepository repo =
            new org.springframework.security.web.context.RequestAttributeSecurityContextRepository();
        org.springframework.security.core.context.SecurityContext savedContext = repo.loadDeferredContext(initialRequest).get();
        assertThat(savedContext.getAuthentication()).isNotNull();
        assertThat(savedContext.getAuthentication().getPrincipal()).isInstanceOf(CustomUserDetails.class);

        // Simulate SecurityContextHolderFilter restoring SecurityContext from RequestAttributeSecurityContextRepository on ASYNC dispatch
        SecurityContextHolder.setContext(savedContext);
        MockHttpServletRequest asyncRequest = new MockHttpServletRequest("GET", "/api/v1/chat/stream");
        asyncRequest.setDispatcherType(DispatcherType.ASYNC);
        asyncRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token());
        MockHttpServletResponse asyncResponse = new MockHttpServletResponse();

        FilterChain asyncChain = (req, res) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isSameAs(savedContext.getAuthentication().getPrincipal());
        };
        filter.doFilter(asyncRequest, asyncResponse, asyncChain);

        // Redis was still only checked once on initial REQUEST dispatch
        verify(tokenBlacklistService, times(1)).isTokenRevoked(issued.jti(), userId, FIXED_NOW);
    }

    @Test
    void rejectsBlacklistedTokenWith401ProblemDetailAndWwwAuthenticateHeader() throws Exception {
        UUID userId = UUID.randomUUID();
        UserEntity user = buildUser(userId, UUID.randomUUID(), "user@documind.ai", "User", Role.STANDARD_EMPLOYEE);
        JwtService.IssuedAccessToken issued = jwtService.generateAccessToken(user);
        when(tokenBlacklistService.isTokenRevoked(issued.jti(), userId, FIXED_NOW)).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo("Bearer realm=\"DocuMind AI\", error=\"invalid_token\"");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void skipsPublicLoginAndRefreshEndpointsEvenWhenStaleOrExpiredBearerHeaderIsPresent() throws Exception {
        JwtService expiredIssuer = new JwtService(
            new JwtProperties(SECRET, "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
            new MockEnvironment(),
            Clock.fixed(FIXED_NOW.minus(Duration.ofMinutes(20)), ZoneOffset.UTC)
        );
        UserEntity user = buildUser(UUID.randomUUID(), UUID.randomUUID(), "u@documind.ai", "U", Role.STANDARD_EMPLOYEE);
        String expiredToken = expiredIssuer.generateAccessToken(user).token();

        for (String publicAuthPath : new String[]{"/api/v1/auth/login", "/api/v1/auth/refresh"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", publicAuthPath);
            request.setServletPath(publicAuthPath);
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
        verifyNoInteractions(tokenBlacklistService);

        // Protected auth endpoints (/logout and /me) MUST NOT be skipped
        MockHttpServletRequest logoutReq = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        logoutReq.setServletPath("/api/v1/auth/logout");
        logoutReq.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken);
        MockHttpServletResponse logoutResp = new MockHttpServletResponse();
        FilterChain logoutChain = mock(FilterChain.class);

        filter.doFilter(logoutReq, logoutResp, logoutChain);

        verifyNoInteractions(logoutChain);
        assertThat(logoutResp.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(logoutResp.getHeader(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo("Bearer realm=\"DocuMind AI\", error=\"invalid_token\"");
    }

    @Test
    void accessDeniedHandlerWritesRfc7807ForbiddenProblemDetailAndSkipsCommittedResponses() throws Exception {
        ProblemDetailAccessDeniedHandler accessDeniedHandler = new ProblemDetailAccessDeniedHandler(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessDeniedHandler.handle(request, response, new org.springframework.security.access.AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/admin/users");

        MockHttpServletResponse committed = new MockHttpServletResponse();
        committed.setCommitted(true);
        accessDeniedHandler.handle(request, committed, new org.springframework.security.access.AccessDeniedException("denied"));
        assertThat(committed.getContentAsString()).isEmpty();
    }

    @Test
    void rejectsExpiredAndMalformedBearerTokensWith401() throws Exception {
        MockHttpServletRequest malformedRequest = new MockHttpServletRequest("GET", "/api/v1/documents");
        malformedRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt");
        MockHttpServletResponse malformedResponse = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(malformedRequest, malformedResponse, chain);

        verifyNoInteractions(chain);
        assertThat(malformedResponse.getStatus()).isEqualTo(401);
        JsonNode malformedJson = objectMapper.readTree(malformedResponse.getContentAsString());
        assertThat(malformedJson.path("errorCode").asText()).isEqualTo("INVALID_TOKEN");

        JwtService expiredIssuer = new JwtService(
            new JwtProperties(SECRET, "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
            new MockEnvironment(),
            Clock.fixed(FIXED_NOW.minus(Duration.ofMinutes(20)), ZoneOffset.UTC)
        );
        UserEntity user = buildUser(UUID.randomUUID(), UUID.randomUUID(), "u@documind.ai", "U", Role.STANDARD_EMPLOYEE);
        String expiredToken = expiredIssuer.generateAccessToken(user).token();

        MockHttpServletRequest expiredRequest = new MockHttpServletRequest("GET", "/api/v1/documents");
        expiredRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken);
        MockHttpServletResponse expiredResponse = new MockHttpServletResponse();

        filter.doFilter(expiredRequest, expiredResponse, chain);

        assertThat(expiredResponse.getStatus()).isEqualTo(401);
        JsonNode expiredJson = objectMapper.readTree(expiredResponse.getContentAsString());
        assertThat(expiredJson.path("errorCode").asText()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void delegatesToHandlerExceptionResolverFailClosedWhenRedisBlacklistStoreUnavailable() throws Exception {
        UUID userId = UUID.randomUUID();
        UserEntity user = buildUser(userId, UUID.randomUUID(), "u@documind.ai", "U", Role.STANDARD_EMPLOYEE);
        JwtService.IssuedAccessToken issued = jwtService.generateAccessToken(user);
        AuthStateStoreUnavailableException storeDown = new AuthStateStoreUnavailableException("Redis unavailable");
        when(tokenBlacklistService.isTokenRevoked(issued.jti(), userId, FIXED_NOW)).thenThrow(storeDown);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/documents");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(handlerExceptionResolver).resolveException(eq(request), eq(response), isNull(), any(AuthStateStoreUnavailableException.class));
    }

    @Test
    void passesThroughUntouchedWhenAuthorizationHeaderIsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(tokenBlacklistService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void devHeaderAuthenticationFilterSkipsAdminEndpoints() throws Exception {
        com.documind.service.document.DevFallbackCallerContextResolver resolver =
            mock(com.documind.service.document.DevFallbackCallerContextResolver.class);
        DevHeaderAuthenticationFilter devFilter = new DevHeaderAuthenticationFilter(resolver, handlerExceptionResolver);

        for (String adminPath : new String[]{
            "/api/v1/admin",
            "/api/v1/admin/departments",
            "/api/v1/admin/users",
            "/api/v1/%61dmin/users",
            "/api/v1/%61dmin/departments"
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", adminPath);
            request.setRequestURI(adminPath);
            request.addHeader("X-User-Role", "SYSTEM_ADMIN");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            devFilter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        // Also skip when servlet context-path prefixes /api/v1/admin/**
        MockHttpServletRequest contextPathRequest = new MockHttpServletRequest("GET", "/documind/api/v1/admin/users");
        contextPathRequest.setContextPath("/documind");
        contextPathRequest.setServletPath("/api/v1/admin/users");
        contextPathRequest.setRequestURI("/documind/api/v1/admin/users");
        contextPathRequest.addHeader("X-User-Role", "SYSTEM_ADMIN");
        MockHttpServletResponse contextResp = new MockHttpServletResponse();
        FilterChain contextChain = mock(FilterChain.class);
        devFilter.doFilter(contextPathRequest, contextResp, contextChain);
        verify(contextChain).doFilter(contextPathRequest, contextResp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(resolver);
    }

    private static UserEntity buildUser(UUID userId, UUID deptId, String email, String fullName, Role role) {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(deptId);
        dept.setCode("HR");
        dept.setName("Human Resources");

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setDepartment(dept);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setIsActive(true);
        return user;
    }
}
