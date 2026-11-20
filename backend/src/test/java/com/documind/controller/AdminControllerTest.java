package com.documind.controller;

import com.documind.controller.advice.GlobalExceptionHandler;
import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminDepartmentResponse;
import com.documind.controller.dto.admin.AdminResetPasswordRequest;
import com.documind.controller.dto.admin.AdminUserResponse;
import com.documind.controller.dto.admin.CreateDepartmentRequest;
import com.documind.controller.dto.admin.CreateUserRequest;
import com.documind.controller.dto.admin.UpdateDepartmentRequest;
import com.documind.controller.dto.admin.UpdateUserRequest;
import com.documind.domain.enums.Role;
import com.documind.exception.AdminConflictException;
import com.documind.exception.AuthStateStoreUnavailableException;
import com.documind.exception.InvalidAdminRequestException;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.service.admin.AdminDepartmentService;
import com.documind.service.admin.AdminUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FIN_DEPT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private AdminDepartmentService departmentService;
    private AdminUserService userService;
    private ObjectMapper objectMapper;
    private LocalValidatorFactoryBean validator;
    private CustomUserDetails currentPrincipal;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        departmentService = mock(AdminDepartmentService.class);
        userService = mock(AdminUserService.class);
        objectMapper = Jackson2ObjectMapperBuilder.json()
            .mixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        currentPrincipal = new CustomUserDetails(
            ADMIN_ID,
            IT_DEPT_ID,
            "admin@documind.ai",
            "System Administrator",
            Role.SYSTEM_ADMIN,
            "jti-admin-1",
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
            .standaloneSetup(
                new AdminDepartmentController(departmentService),
                new AdminUserController(userService)
            )
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
    void departmentCrudEndpointsReturnStandardStatusCodesAndRfc7807Conflicts() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdminDepartmentResponse finDept = new AdminDepartmentResponse(
            FIN_DEPT_ID, "FIN", "Tài chính", "Phòng Tài chính", 1L, now, now
        );

        when(departmentService.createDepartment(any(CreateDepartmentRequest.class), eq(ADMIN_ID)))
            .thenReturn(finDept);
        mockMvc.perform(post("/api/v1/admin/departments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "code", "FIN",
                    "name", "Tài chính",
                    "description", "Phòng Tài chính"
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(FIN_DEPT_ID.toString()))
            .andExpect(jsonPath("$.code").value("FIN"));

        when(departmentService.listDepartments("fin", 0, 20, ADMIN_ID))
            .thenReturn(new PageResponse<>(List.of(finDept), 0, 20, 1L, 1));
        mockMvc.perform(get("/api/v1/admin/departments?q=fin&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].code").value("FIN"));

        when(departmentService.getDepartment(FIN_DEPT_ID, ADMIN_ID)).thenReturn(finDept);
        mockMvc.perform(get("/api/v1/admin/departments/" + FIN_DEPT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Tài chính"));

        when(departmentService.updateDepartment(eq(FIN_DEPT_ID), any(UpdateDepartmentRequest.class), eq(ADMIN_ID)))
            .thenReturn(finDept);
        mockMvc.perform(patch("/api/v1/admin/departments/" + FIN_DEPT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tài chính Kế toán\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("FIN"));

        doNothing().when(departmentService).deleteDepartment(FIN_DEPT_ID, ADMIN_ID);
        mockMvc.perform(delete("/api/v1/admin/departments/" + FIN_DEPT_ID))
            .andExpect(status().isNoContent());

        doThrow(new AdminConflictException(AdminConflictException.CODE_DEPARTMENT_IN_USE, "Department is still referenced"))
            .when(departmentService).deleteDepartment(IT_DEPT_ID, ADMIN_ID);
        mockMvc.perform(delete("/api/v1/admin/departments/" + IT_DEPT_ID))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("DEPARTMENT_IN_USE"));
    }

    @Test
    void departmentValidationRejectsBlankCodeOrNameBeforeService() throws Exception {
        mockMvc.perform(post("/api/v1/admin/departments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void userEndpointsSupportCreateListGetPatchPasswordResetAndMapErrors() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdminUserResponse createdUser = new AdminUserResponse(
            USER_ID,
            "new.user@documind.ai",
            "Nguyễn Văn A",
            Role.DEPARTMENT_MANAGER,
            FIN_DEPT_ID,
            "FIN",
            "Tài chính",
            true,
            now,
            now
        );

        when(userService.createUser(any(CreateUserRequest.class), eq(ADMIN_ID))).thenReturn(createdUser);
        mockMvc.perform(post("/api/v1/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "new.user@documind.ai",
                    "fullName", "Nguyễn Văn A",
                    "password", "Secret@2026!",
                    "role", "DEPARTMENT_MANAGER",
                    "departmentId", FIN_DEPT_ID.toString()
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("new.user@documind.ai"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        when(userService.listUsers(FIN_DEPT_ID, Role.DEPARTMENT_MANAGER, true, "nguyễn", 0, 20, ADMIN_ID))
            .thenReturn(new PageResponse<>(List.of(createdUser), 0, 20, 1L, 1));
        mockMvc.perform(get("/api/v1/admin/users?departmentId=" + FIN_DEPT_ID
                + "&role=DEPARTMENT_MANAGER&isActive=true&q=nguyễn&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].departmentCode").value("FIN"));

        when(userService.getUser(USER_ID, ADMIN_ID)).thenReturn(createdUser);
        mockMvc.perform(get("/api/v1/admin/users/" + USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(USER_ID.toString()));

        when(userService.updateUser(eq(USER_ID), any(UpdateUserRequest.class), eq(ADMIN_ID))).thenReturn(createdUser);
        mockMvc.perform(patch("/api/v1/admin/users/" + USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\":false}"))
            .andExpect(status().isOk());

        doNothing().when(userService).resetPassword(eq(USER_ID), any(AdminResetPasswordRequest.class), eq(ADMIN_ID));
        mockMvc.perform(put("/api/v1/admin/users/" + USER_ID + "/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"ResetPass@2026!\"}"))
            .andExpect(status().isNoContent());
        verify(userService).resetPassword(eq(USER_ID), eq(new AdminResetPasswordRequest("ResetPass@2026!")), eq(ADMIN_ID));

        doThrow(new InvalidAdminRequestException("At least one field must be provided"))
            .when(userService).updateUser(eq(ADMIN_ID), any(UpdateUserRequest.class), eq(ADMIN_ID));
        mockMvc.perform(patch("/api/v1/admin/users/" + ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_ADMIN_REQUEST"));

        doThrow(new AuthStateStoreUnavailableException("Redis unavailable"))
            .when(userService).resetPassword(eq(USER_ID), any(AdminResetPasswordRequest.class), eq(ADMIN_ID));
        mockMvc.perform(put("/api/v1/admin/users/" + USER_ID + "/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"ResetPass@2026!\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorCode").value("AUTH_STATE_STORE_UNAVAILABLE"));
    }

    @Test
    void userValidationRejectsShortPasswordAndInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "not-an-email",
                    "fullName", "User",
                    "password", "short",
                    "role", "STANDARD_EMPLOYEE",
                    "departmentId", FIN_DEPT_ID.toString()
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        // >72-char password must fail Bean Validation (400) rather than crashing BCrypt with 500
        mockMvc.perform(post("/api/v1/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "valid@documind.ai",
                    "fullName", "User",
                    "password", "A".repeat(73),
                    "role", "STANDARD_EMPLOYEE",
                    "departmentId", FIN_DEPT_ID.toString()
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(put("/api/v1/admin/users/" + USER_ID + "/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("newPassword", "B".repeat(73)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(userService);
    }

    @Test
    void dtoTrimsEmailAndCodeBeforeBeanValidationAndRedactsPasswordsInToString() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdminUserResponse createdUser = new AdminUserResponse(
            USER_ID, "new.user@documind.ai", "Nguyễn Văn A",
            Role.STANDARD_EMPLOYEE, FIN_DEPT_ID, "FIN", "Tài chính", true, now, now
        );
        when(userService.createUser(any(CreateUserRequest.class), eq(ADMIN_ID))).thenReturn(createdUser);

        mockMvc.perform(post("/api/v1/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "  New.User@DocuMind.AI  ",
                    "fullName", "Nguyễn Văn A",
                    "password", "Secret@2026!",
                    "role", "STANDARD_EMPLOYEE",
                    "departmentId", FIN_DEPT_ID.toString()
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("new.user@documind.ai"));

        CreateUserRequest createReq = new CreateUserRequest(
            "  user@documind.ai ", "User", "TopSecret@2026!", Role.STANDARD_EMPLOYEE, FIN_DEPT_ID, true
        );
        AdminResetPasswordRequest resetReq = new AdminResetPasswordRequest("ResetSecret@2026!");
        org.assertj.core.api.Assertions.assertThat(createReq.toString())
            .doesNotContain("TopSecret@2026!")
            .contains("[REDACTED]");
        org.assertj.core.api.Assertions.assertThat(resetReq.toString())
            .doesNotContain("ResetSecret@2026!")
            .contains("[REDACTED]");
    }

    @Test
    void missingPrincipalReturns401AndMissingResourceReturns404() throws Exception {
        currentPrincipal = null;
        mockMvc.perform(get("/api/v1/admin/departments"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/admin/users"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(departmentService, userService);

        currentPrincipal = new CustomUserDetails(
            ADMIN_ID, IT_DEPT_ID, "admin@documind.ai", "System Administrator",
            Role.SYSTEM_ADMIN, "jti-admin-2", Instant.now().plusSeconds(900)
        );
        when(departmentService.getDepartment(FIN_DEPT_ID, ADMIN_ID))
            .thenThrow(new com.documind.exception.ResourceNotFoundException("Department not found"));
        mockMvc.perform(get("/api/v1/admin/departments/" + FIN_DEPT_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void methodSecurityPreAuthorizeEnforcesSystemAdminRoleOnAdminControllersAndServices() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                 new org.springframework.context.annotation.AnnotationConfigApplicationContext(
                     MethodSecurityTestConfig.class
                 )) {
            AdminDepartmentController deptController = ctx.getBean(AdminDepartmentController.class);
            AdminUserController userController = ctx.getBean(AdminUserController.class);
            AdminDepartmentService deptSvc = ctx.getBean(AdminDepartmentService.class);
            AdminUserService userSvc = ctx.getBean(AdminUserService.class);

            // Non-admin principal (DEPARTMENT_MANAGER) must be rejected by @PreAuthorize AOP proxy
            CustomUserDetails manager = new CustomUserDetails(
                USER_ID, FIN_DEPT_ID, "mgr@documind.ai", "Manager",
                Role.DEPARTMENT_MANAGER, "jti-mgr", Instant.now().plusSeconds(900)
            );
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    manager, null, manager.getAuthorities()
                )
            );
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> deptController.listDepartments(null, 0, 20, manager))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> userController.listUsers(null, null, null, null, 0, 20, manager))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> deptSvc.listDepartments(null, 0, 20, USER_ID))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> userSvc.listUsers(null, null, null, null, 0, 20, USER_ID))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

                // SYSTEM_ADMIN principal passes @PreAuthorize AOP proxy on both controllers
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        currentPrincipal, null, currentPrincipal.getAuthorities()
                    )
                );
                org.assertj.core.api.Assertions.assertThat(
                    deptController.listDepartments(null, 0, 20, currentPrincipal).items()
                ).isEmpty();
                org.assertj.core.api.Assertions.assertThat(
                    userController.listUsers(null, null, null, null, 0, 20, currentPrincipal).items()
                ).isEmpty();
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @org.springframework.context.annotation.Bean
        AdminDepartmentService adminDepartmentService() {
            AdminDepartmentService mockSvc = mock(AdminDepartmentService.class);
            when(mockSvc.listDepartments(any(), eq(0), eq(20), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));
            return mockSvc;
        }

        @org.springframework.context.annotation.Bean
        AdminUserService adminUserService() {
            AdminUserService mockSvc = mock(AdminUserService.class);
            when(mockSvc.listUsers(any(), any(), any(), any(), eq(0), eq(20), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));
            return mockSvc;
        }

        @org.springframework.context.annotation.Bean
        AdminDepartmentController adminDepartmentController(AdminDepartmentService svc) {
            return new AdminDepartmentController(svc);
        }

        @org.springframework.context.annotation.Bean
        AdminUserController adminUserController(AdminUserService svc) {
            return new AdminUserController(svc);
        }
    }
}
