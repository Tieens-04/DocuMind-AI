package com.documind.controller;

import com.documind.controller.advice.GlobalExceptionHandler;
import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.controller.dto.auth.LoginRequest;
import com.documind.controller.dto.auth.LogoutRequest;
import com.documind.controller.dto.auth.RefreshTokenRequest;
import com.documind.controller.dto.auth.UserProfileResponse;
import com.documind.domain.enums.Role;
import com.documind.exception.AccountDisabledException;
import com.documind.exception.AuthStateStoreUnavailableException;
import com.documind.exception.InvalidCredentialsException;
import com.documind.exception.InvalidTokenException;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.service.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AuthService authService;
    private ObjectMapper objectMapper;
    private LocalValidatorFactoryBean validator;
    private CustomUserDetails currentPrincipal;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        objectMapper = Jackson2ObjectMapperBuilder.json()
            .mixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        currentPrincipal = new CustomUserDetails(
            USER_ID,
            HR_DEPT_ID,
            "manager.hr@documind.ai",
            "HR Department Manager",
            Role.DEPARTMENT_MANAGER,
            "jti-ctrl-1",
            Instant.now().plusSeconds(900)
        );

        HandlerMethodArgumentResolver principalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                    && CustomUserDetails.class.isAssignableFrom(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory
            ) {
                return currentPrincipal;
            }
        };

        mockMvc = MockMvcBuilders
            .standaloneSetup(new AuthController(authService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .setCustomArgumentResolvers(principalResolver)
            .setValidator(validator)
            .build();
    }

    @AfterEach
    void tearDown() {
        if (validator != null) {
            validator.close();
        }
    }

    @Test
    void loginReturns200AndForwardsUserAgentAndClientIp() throws Exception {
        UserProfileResponse profile = new UserProfileResponse(
            USER_ID,
            "manager.hr@documind.ai",
            "HR Department Manager",
            Role.DEPARTMENT_MANAGER,
            HR_DEPT_ID,
            "HR",
            "Hành chính Nhân sự",
            true,
            OffsetDateTime.now(ZoneOffset.UTC)
        );
        AuthTokenResponse tokenResponse = new AuthTokenResponse(
            "jwt-access-token",
            "opaque-refresh-token",
            "Bearer",
            900L,
            604800L,
            profile
        );
        when(authService.login(eq(new LoginRequest("manager.hr@documind.ai", "DocuMind@2026!")), eq("JUnit-UA"), eq("203.0.113.5")))
            .thenReturn(tokenResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                .header(HttpHeaders.USER_AGENT, "JUnit-UA")
                .header("X-Forwarded-For", "198.51.100.99, 10.0.0.1")
                .with(req -> {
                    req.setRemoteAddr("203.0.113.5");
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "manager.hr@documind.ai",
                    "password", "DocuMind@2026!"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("jwt-access-token"))
            .andExpect(jsonPath("$.refreshToken").value("opaque-refresh-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(jsonPath("$.user.email").value("manager.hr@documind.ai"));
    }

    @Test
    void loginMapsValidationMalformedJsonAndAuthFailuresToRfc7807ResponsesWithoutLeakingSecrets() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{malformed-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON_REQUEST"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON_REQUEST"));

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{bad-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON_REQUEST"));

        verifyNoInteractions(authService);

        doThrow(new InvalidCredentialsException("Invalid email or password"))
            .when(authService).login(any(), any(), any());
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"manager.hr@documind.ai\",\"password\":\"bad\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"DocuMind AI\""))
            .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));

        doThrow(new AccountDisabledException("User account is disabled"))
            .when(authService).login(any(), any(), any());
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"manager.hr@documind.ai\",\"password\":\"DocuMind@2026!\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DISABLED"));

        doThrow(new AuthStateStoreUnavailableException("redis://internal-secret-host:6380 refused"))
            .when(authService).login(any(), any(), any());
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"manager.hr@documind.ai\",\"password\":\"DocuMind@2026!\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorCode").value("AUTH_STATE_STORE_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail", not(containsString("internal-secret-host"))));
    }

    @Test
    void refreshReturnsRotatedTokensOr401OnReuseDetection() throws Exception {
        UserProfileResponse profile = new UserProfileResponse(
            USER_ID, "manager.hr@documind.ai", "HR Manager", Role.DEPARTMENT_MANAGER,
            HR_DEPT_ID, "HR", "Human Resources", true, OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(authService.refresh(eq(new RefreshTokenRequest("old-rt")), any(), any()))
            .thenReturn(new AuthTokenResponse("new-at", "new-rt", "Bearer", 900L, 604800L, profile));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"old-rt\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("new-at"))
            .andExpect(jsonPath("$.refreshToken").value("new-rt"));

        when(authService.refresh(eq(new RefreshTokenRequest("reused-rt")), any(), any()))
            .thenThrow(new InvalidTokenException(InvalidTokenException.CODE_REFRESH_TOKEN_REUSED, "Refresh token reuse detected"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"reused-rt\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"DocuMind AI\", error=\"invalid_token\""))
            .andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void logoutAcceptsOptionalBodyMapsForeignTokenTo403AndMeReturnsProfileOr401WhenPrincipalMissing() throws Exception {
        doNothing().when(authService).logout(eq(currentPrincipal), any(), any());

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"rt-1\"}"))
            .andExpect(status().isNoContent());

        verify(authService).logout(eq(currentPrincipal), eq(new LogoutRequest("rt-1")), any());

        doThrow(new org.springframework.security.access.AccessDeniedException("Refresh token does not belong to caller"))
            .when(authService).logout(eq(currentPrincipal), eq(new LogoutRequest("foreign-rt")), any());
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"foreign-rt\"}"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isNoContent());

        verify(authService).logout(eq(currentPrincipal), isNull(), any());

        UserProfileResponse profile = new UserProfileResponse(
            USER_ID, "manager.hr@documind.ai", "HR Manager", Role.DEPARTMENT_MANAGER,
            HR_DEPT_ID, "HR", "Human Resources", true, OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(authService.getCurrentUser(currentPrincipal)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(USER_ID.toString()))
            .andExpect(jsonPath("$.departmentCode").value("HR"));

        currentPrincipal = null;
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }
}
