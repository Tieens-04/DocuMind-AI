package com.documind.service.document;

import com.documind.controller.advice.GlobalExceptionHandler;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevFallbackCallerContextResolverTest {

    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID HR_MANAGER_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SALES_EMPLOYEE_USER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void missingHeadersFallBackToItManagerAndSeededAdminUser() {
        var resolver = new DevFallbackCallerContextResolver();
        var request = new MockHttpServletRequest();

        CallerContext context = resolver.resolve(request);

        assertThat(context.departmentId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_DEPARTMENT_ID);
        assertThat(context.userId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID);
        assertThat(context.role()).isEqualTo(Role.DEPARTMENT_MANAGER);
        assertThat(context.ipAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void blankHeadersFallBackCleanlyInDevResolver() {
        var resolver = new DevFallbackCallerContextResolver();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Department-Id", "   ");
        request.addHeader("X-User-Id", "");
        request.addHeader("X-User-Role", " ");

        CallerContext context = resolver.resolve(request);

        assertThat(context.departmentId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_DEPARTMENT_ID);
        assertThat(context.userId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID);
        assertThat(context.role()).isEqualTo(Role.DEPARTMENT_MANAGER);
    }

    @Test
    void explicitDepartmentHeaderWithoutUserIdNullsUserIdUnlessTargetingDefaultItDepartment() {
        var resolver = new DevFallbackCallerContextResolver();

        var hrRequest = new MockHttpServletRequest();
        hrRequest.addHeader("X-Department-Id", "  " + HR_DEPT_ID + "  ");
        CallerContext hrContext = resolver.resolve(hrRequest);
        assertThat(hrContext.departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(hrContext.userId()).isNull();
        assertThat(hrContext.role()).isEqualTo(Role.DEPARTMENT_MANAGER);

        var itRequest = new MockHttpServletRequest();
        itRequest.addHeader("X-Department-Id", DevFallbackCallerContextResolver.DEFAULT_DEV_DEPARTMENT_ID.toString());
        CallerContext itContext = resolver.resolve(itRequest);
        assertThat(itContext.departmentId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_DEPARTMENT_ID);
        assertThat(itContext.userId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID);
    }

    @Test
    void resolvesDepartmentAndRoleFromUserRepositoryWhenOnlyUserIdHeaderIsProvided() {
        UserRepository userRepository = mock(UserRepository.class);
        DepartmentEntity salesDept = new DepartmentEntity();
        salesDept.setId(SALES_DEPT_ID);
        UserEntity employee = new UserEntity();
        employee.setId(SALES_EMPLOYEE_USER_ID);
        employee.setDepartment(salesDept);
        employee.setRole(Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(SALES_EMPLOYEE_USER_ID)).thenReturn(Optional.of(employee));

        var resolver = new DevFallbackCallerContextResolver(userRepository);
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", SALES_EMPLOYEE_USER_ID.toString());

        CallerContext context = resolver.resolve(request);

        assertThat(context.userId()).isEqualTo(SALES_EMPLOYEE_USER_ID);
        assertThat(context.departmentId()).isEqualTo(SALES_DEPT_ID);
        assertThat(context.role()).isEqualTo(Role.STANDARD_EMPLOYEE);
    }

    @Test
    void parsesExplicitUserRoleHeaderCaseInsensitivelyAndRejectsInvalidHeaders() {
        var resolver = new DevFallbackCallerContextResolver();

        var adminRequest = new MockHttpServletRequest();
        adminRequest.addHeader("X-Department-Id", HR_DEPT_ID.toString());
        adminRequest.addHeader("X-User-Id", HR_MANAGER_USER_ID.toString());
        adminRequest.addHeader("X-User-Role", " system_admin ");
        CallerContext adminContext = resolver.resolve(adminRequest);
        assertThat(adminContext.role()).isEqualTo(Role.SYSTEM_ADMIN);
        assertThat(adminContext.isSystemAdmin()).isTrue();
        assertThat(adminContext.canManageDocuments()).isTrue();

        var badDeptRequest = new MockHttpServletRequest();
        badDeptRequest.addHeader("X-Department-Id", "not-a-uuid");
        assertThatThrownBy(() -> resolver.resolve(badDeptRequest))
            .isInstanceOf(InvalidDocumentRequestException.class);

        var badUserRequest = new MockHttpServletRequest();
        badUserRequest.addHeader("X-User-Id", "bad-user-uuid");
        assertThatThrownBy(() -> resolver.resolve(badUserRequest))
            .isInstanceOf(InvalidDocumentRequestException.class);

        var badRoleRequest = new MockHttpServletRequest();
        badRoleRequest.addHeader("X-User-Role", "SUPERUSER");
        assertThatThrownBy(() -> resolver.resolve(badRoleRequest))
            .isInstanceOf(InvalidDocumentRequestException.class);
    }

    @Test
    void prodCallerContextResolverAlwaysFailsClosedEvenWhenSpoofedHeadersAreProvided() {
        var prodResolver = new ProdCallerContextResolver();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Department-Id", HR_DEPT_ID.toString());
        request.addHeader("X-User-Id", HR_MANAGER_USER_ID.toString());
        request.addHeader("X-User-Role", "SYSTEM_ADMIN");

        assertThatThrownBy(() -> prodResolver.resolve(request))
            .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void resolvesClientIpFromFirstXForwardedForHopOrRemoteAddr() {
        var resolver = new DevFallbackCallerContextResolver();

        var forwardedRequest = new MockHttpServletRequest();
        forwardedRequest.addHeader("X-Forwarded-For", "203.0.113.42, 10.0.0.1");
        forwardedRequest.setRemoteAddr("10.0.0.2");
        assertThat(resolver.resolve(forwardedRequest).ipAddress()).isEqualTo("203.0.113.42");

        var directRequest = new MockHttpServletRequest();
        directRequest.setRemoteAddr("10.20.30.40");
        assertThat(resolver.resolve(directRequest).ipAddress()).isEqualTo("10.20.30.40");

        assertThat(resolver.resolve(null).ipAddress()).isNull();
    }

    @Test
    void callerContextConditionsFailClosedByDefaultAndEnableOnlyWhenDevOrLocalOptsIn() {
        var devCondition = new DevCallerFallbackEnabledCondition();
        var failClosedCondition = new FailClosedCallerContextCondition();
        AnnotatedTypeMetadata metadata = mock(AnnotatedTypeMetadata.class);

        assertConditionState(devCondition, failClosedCondition, metadata, new MockEnvironment(), false);
        assertConditionState(devCondition, failClosedCondition, metadata, env(new String[]{"staging"}, true), false);
        assertConditionState(devCondition, failClosedCondition, metadata, env(new String[]{"test"}, true), false);
        assertConditionState(devCondition, failClosedCondition, metadata, env(new String[]{"prod"}, true), false);
        assertConditionState(devCondition, failClosedCondition, metadata, env(new String[]{"dev", "prod"}, true), false);
        assertConditionState(devCondition, failClosedCondition, metadata, env(new String[]{"dev"}, false), false);
        assertConditionState(devCondition, failClosedCondition, metadata, env(new String[]{"dev"}, true), true);
        assertConditionState(devCondition, failClosedCondition, metadata, env(new String[]{"local"}, true), true);
    }

    @Test
    void globalExceptionHandlerSanitizesUnhandledExceptionsAndPreservesFrameworkErrorResponses() {
        var handler = new GlobalExceptionHandler();
        var request = new MockHttpServletRequest("PUT", "/api/v1/documents");

        ResponseEntity<ProblemDetail> unhandledResp = handler.handleUnhandledException(
            new DataIntegrityViolationException("duplicate key violates uq_internal_constraint on table secret_table"),
            request
        );
        assertThat(unhandledResp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail unhandledBody = Objects.requireNonNull(unhandledResp.getBody());
        assertThat(unhandledBody.getStatus()).isEqualTo(500);
        assertThat(unhandledBody.getProperties()).containsEntry("errorCode", "INTERNAL_ERROR");
        assertThat(unhandledBody.getDetail())
            .doesNotContain("uq_internal_constraint")
            .doesNotContain("secret_table");

        ResponseEntity<ProblemDetail> methodNotAllowedResp = handler.handleUnhandledException(
            new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "POST")),
            request
        );
        assertThat(methodNotAllowedResp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(methodNotAllowedResp.getHeaders().getAllow()).contains(HttpMethod.GET, HttpMethod.POST);
        ProblemDetail methodBody = Objects.requireNonNull(methodNotAllowedResp.getBody());
        assertThat(methodBody.getStatus()).isEqualTo(405);
        assertThat(methodBody.getProperties()).containsEntry("errorCode", "REQUEST_ERROR");
    }

    private static MockEnvironment env(String[] profiles, boolean enabled) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        env.setProperty(DevCallerFallbackEnabledCondition.PROPERTY_ENABLED, Boolean.toString(enabled));
        return env;
    }

    private static void assertConditionState(
        DevCallerFallbackEnabledCondition devCondition,
        FailClosedCallerContextCondition failClosedCondition,
        AnnotatedTypeMetadata metadata,
        MockEnvironment env,
        boolean expectDevActive
    ) {
        ConditionContext ctx = mock(ConditionContext.class);
        when(ctx.getEnvironment()).thenReturn(env);
        assertThat(devCondition.matches(ctx, metadata)).isEqualTo(expectDevActive);
        assertThat(failClosedCondition.matches(ctx, metadata)).isEqualTo(!expectDevActive);
    }
}
