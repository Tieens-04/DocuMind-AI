package com.documind.service.document;

import com.documind.exception.InvalidDocumentRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevFallbackCallerContextResolverTest {

    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CUSTOM_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void requestDepartmentIdOverridesHeaderAndDevFallback() {
        var resolver = new DevFallbackCallerContextResolver(new MockEnvironment());
        var request = new MockHttpServletRequest();
        request.addHeader("X-Department-Id", HR_DEPT_ID.toString());
        request.addHeader("X-User-Id", CUSTOM_USER_ID.toString());

        CallerContext context = resolver.resolve(SALES_DEPT_ID, request);

        assertThat(context.departmentId()).isEqualTo(SALES_DEPT_ID);
        assertThat(context.userId()).isEqualTo(CUSTOM_USER_ID);
        assertThat(context.explicitDepartment()).isTrue();
    }

    @Test
    void headerDepartmentIdOverridesDevFallbackWithExplicitFlagTrue() {
        var resolver = new DevFallbackCallerContextResolver(new MockEnvironment());
        var request = new MockHttpServletRequest();
        request.addHeader("X-Department-Id", "  " + HR_DEPT_ID + "  ");

        CallerContext context = resolver.resolve(null, request);

        assertThat(context.departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(context.userId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID);
        assertThat(context.explicitDepartment()).isTrue();
    }

    @Test
    void missingDepartmentAndUserInNonProdFallsBackToSeededItDepartmentAndAdmin() {
        var resolver = new DevFallbackCallerContextResolver(new MockEnvironment());
        var request = new MockHttpServletRequest();

        CallerContext context = resolver.resolve(null, request);

        assertThat(context.departmentId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_DEPARTMENT_ID);
        assertThat(context.userId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID);
        assertThat(context.explicitDepartment()).isFalse();
        assertThat(context.ipAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void blankDepartmentAndUserHeadersFallBackInNonProd() {
        var resolver = new DevFallbackCallerContextResolver(new MockEnvironment());
        var request = new MockHttpServletRequest();
        request.addHeader("X-Department-Id", "   ");
        request.addHeader("X-User-Id", "");

        CallerContext context = resolver.resolve(null, request);

        assertThat(context.departmentId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_DEPARTMENT_ID);
        assertThat(context.userId()).isEqualTo(DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID);
        assertThat(context.explicitDepartment()).isFalse();
    }

    @Test
    void prodProfileThrowsWhenBothRequestDepartmentIdAndHeaderMissing() {
        MockEnvironment prodEnv = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        var resolver = new DevFallbackCallerContextResolver(prodEnv);
        var request = new MockHttpServletRequest();

        assertThatThrownBy(() -> resolver.resolve(null, request))
            .isInstanceOf(InvalidDocumentRequestException.class);
    }

    @Test
    void prodProfileUsesExplicitDepartmentAndNullUserIdWhenHeaderAbsent() {
        MockEnvironment prodEnv = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        var resolver = new DevFallbackCallerContextResolver(prodEnv);
        var request = new MockHttpServletRequest();
        request.addHeader("X-Department-Id", HR_DEPT_ID.toString());

        CallerContext context = resolver.resolve(null, request);

        assertThat(context.departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(context.userId()).isNull();
        assertThat(context.explicitDepartment()).isTrue();
    }

    @Test
    void malformedUuidInDepartmentOrUserHeaderThrowsInvalidDocumentRequestException() {
        var resolver = new DevFallbackCallerContextResolver(new MockEnvironment());

        var badDeptRequest = new MockHttpServletRequest();
        badDeptRequest.addHeader("X-Department-Id", "not-a-uuid");
        assertThatThrownBy(() -> resolver.resolve(null, badDeptRequest))
            .isInstanceOf(InvalidDocumentRequestException.class);

        var badUserRequest = new MockHttpServletRequest();
        badUserRequest.addHeader("X-Department-Id", HR_DEPT_ID.toString());
        badUserRequest.addHeader("X-User-Id", "bad-user-uuid");
        assertThatThrownBy(() -> resolver.resolve(null, badUserRequest))
            .isInstanceOf(InvalidDocumentRequestException.class);
    }

    @Test
    void resolvesClientIpFromFirstXForwardedForHopOrRemoteAddr() {
        var resolver = new DevFallbackCallerContextResolver(new MockEnvironment());

        var forwardedRequest = new MockHttpServletRequest();
        forwardedRequest.addHeader("X-Forwarded-For", "203.0.113.42, 10.0.0.1");
        forwardedRequest.setRemoteAddr("10.0.0.2");
        assertThat(resolver.resolve(HR_DEPT_ID, forwardedRequest).ipAddress()).isEqualTo("203.0.113.42");

        var directRequest = new MockHttpServletRequest();
        directRequest.setRemoteAddr("10.20.30.40");
        assertThat(resolver.resolve(HR_DEPT_ID, directRequest).ipAddress()).isEqualTo("10.20.30.40");

        assertThat(resolver.resolve(HR_DEPT_ID, null).ipAddress()).isNull();
    }
}
