package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.Role;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.service.document.CallerContext;
import com.documind.service.ingestion.ChunkMetadataKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RbacVectorFilterFactoryTest {

    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADMIN_USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID HR_MANAGER_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID HR_EMPLOYEE_USER_ID = UUID.fromString("22222222-2222-3333-4444-555555555553");
    private static final UUID SALES_EMPLOYEE_USER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private MockEnvironment prodLikeEnv;
    private RbacVectorFilterFactory factory;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        prodLikeEnv = new MockEnvironment();
        prodLikeEnv.setActiveProfiles("dev");
        prodLikeEnv.setProperty("documind.security.dev-caller-fallback.enabled", "false");
        factory = new RbacVectorFilterFactory(prodLikeEnv);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveScopeBuildsStandardEmployeeFilterExcludingConfidentialDocuments() {
        setJwtPrincipal(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        CallerContext caller = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.1");

        RbacVectorScope scope = factory.resolveScope(caller, null);

        assertThat(scope.caller()).isEqualTo(caller);
        assertThat(scope.filterDepartmentId()).isNull();
        assertThat(scope.cacheScopeKey()).isEqualTo("EMPLOYEE:DEPT:" + HR_DEPT_ID);

        Filter.Expression expr = scope.filterExpression();
        assertThat(expr.type()).isEqualTo(Filter.ExpressionType.OR);
        assertThat(expr.left()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.EQ,
            new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL),
            new Filter.Value("PUBLIC")
        ));
        assertThat(expr.right()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.AND,
            new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(ChunkMetadataKeys.DEPARTMENT_ID),
                new Filter.Value(HR_DEPT_ID.toString())
            ),
            new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL),
                new Filter.Value("DEPARTMENT")
            )
        ));

        assertThat(scope.allows(SALES_DEPT_ID, AccessLevel.PUBLIC)).isTrue();
        assertThat(scope.allows(HR_DEPT_ID, AccessLevel.PUBLIC)).isTrue();
        assertThat(scope.allows(HR_DEPT_ID, AccessLevel.DEPARTMENT)).isTrue();
        assertThat(scope.allows(HR_DEPT_ID, AccessLevel.CONFIDENTIAL)).isFalse();
        assertThat(scope.allows(SALES_DEPT_ID, AccessLevel.DEPARTMENT)).isFalse();
        assertThat(scope.allows(SALES_DEPT_ID, AccessLevel.CONFIDENTIAL)).isFalse();
        assertThat(scope.allows(null, AccessLevel.PUBLIC)).isFalse();
        assertThat(scope.allows(HR_DEPT_ID, null)).isFalse();
    }

    @Test
    void resolveScopeBuildsDepartmentManagerFilterIncludingOwnConfidentialAndCompanyPublic() {
        setJwtPrincipal(HR_MANAGER_USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER);
        CallerContext caller = new CallerContext(HR_MANAGER_USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "10.0.0.2");

        RbacVectorScope scope = factory.resolveScope(caller, null);

        assertThat(scope.caller()).isEqualTo(caller);
        assertThat(scope.filterDepartmentId()).isNull();
        assertThat(scope.cacheScopeKey()).isEqualTo("MANAGER:DEPT:" + HR_DEPT_ID);

        Filter.Expression expr = scope.filterExpression();
        assertThat(expr.type()).isEqualTo(Filter.ExpressionType.OR);
        assertThat(expr.left()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.EQ,
            new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL),
            new Filter.Value("PUBLIC")
        ));
        assertThat(expr.right()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.EQ,
            new Filter.Key(ChunkMetadataKeys.DEPARTMENT_ID),
            new Filter.Value(HR_DEPT_ID.toString())
        ));

        assertThat(scope.allows(SALES_DEPT_ID, AccessLevel.PUBLIC)).isTrue();
        assertThat(scope.allows(HR_DEPT_ID, AccessLevel.DEPARTMENT)).isTrue();
        assertThat(scope.allows(HR_DEPT_ID, AccessLevel.CONFIDENTIAL)).isTrue();
        assertThat(scope.allows(SALES_DEPT_ID, AccessLevel.DEPARTMENT)).isFalse();
        assertThat(scope.allows(SALES_DEPT_ID, AccessLevel.CONFIDENTIAL)).isFalse();
    }

    @Test
    void resolveScopeSupportsGlobalAndDepartmentScopedQueriesForSystemAdmin() {
        setJwtPrincipal(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN);
        CallerContext adminCaller = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN, "10.0.0.3");

        RbacVectorScope globalScope = factory.resolveScope(adminCaller, null);
        assertThat(globalScope.filterDepartmentId()).isNull();
        assertThat(globalScope.cacheScopeKey()).isEqualTo("ADMIN:GLOBAL");
        assertThat(globalScope.filterExpression()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.IN,
            new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL),
            new Filter.Value(List.of("PUBLIC", "DEPARTMENT", "CONFIDENTIAL"))
        ));
        assertThat(globalScope.allows(HR_DEPT_ID, AccessLevel.CONFIDENTIAL)).isTrue();
        assertThat(globalScope.allows(SALES_DEPT_ID, AccessLevel.PUBLIC)).isTrue();

        RbacVectorScope hrScoped = factory.resolveScope(adminCaller, HR_DEPT_ID);
        assertThat(hrScoped.filterDepartmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(hrScoped.cacheScopeKey()).isEqualTo("ADMIN:DEPT:" + HR_DEPT_ID);
        assertThat(hrScoped.filterExpression().type()).isEqualTo(Filter.ExpressionType.AND);
        assertThat(hrScoped.filterExpression().left()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.EQ,
            new Filter.Key(ChunkMetadataKeys.DEPARTMENT_ID),
            new Filter.Value(HR_DEPT_ID.toString())
        ));
        assertThat(hrScoped.filterExpression().right()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.IN,
            new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL),
            new Filter.Value(List.of("PUBLIC", "DEPARTMENT", "CONFIDENTIAL"))
        ));

        assertThat(hrScoped.allows(HR_DEPT_ID, AccessLevel.PUBLIC)).isTrue();
        assertThat(hrScoped.allows(HR_DEPT_ID, AccessLevel.DEPARTMENT)).isTrue();
        assertThat(hrScoped.allows(HR_DEPT_ID, AccessLevel.CONFIDENTIAL)).isTrue();
        assertThat(hrScoped.allows(SALES_DEPT_ID, AccessLevel.PUBLIC)).isFalse();
        assertThat(hrScoped.allows(SALES_DEPT_ID, AccessLevel.DEPARTMENT)).isFalse();
    }

    @Test
    void resolveScopeFailsClosedWith401WhenSecurityContextMissingUnauthenticatedOrAnonymous() {
        assertThatThrownBy(() -> factory.resolveScope(null, null))
            .isInstanceOf(AuthenticationRequiredException.class);

        TestingAuthenticationToken unauthenticated = new TestingAuthenticationToken(
            new CustomUserDetails(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, "emp.hr@documind.ai", "HR Emp", Role.STANDARD_EMPLOYEE, "jti", Instant.now().plusSeconds(60)),
            null
        );
        unauthenticated.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);
        assertThatThrownBy(() -> factory.resolveScope(null, null))
            .isInstanceOf(AuthenticationRequiredException.class);

        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
            "key",
            "anonymousUser",
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        SecurityContextHolder.getContext().setAuthentication(anonymous);
        assertThatThrownBy(() -> factory.resolveScope(null, null))
            .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void resolveScopeRejectsPreAuthenticatedCallerContextUnlessDevFallbackActivelyEnabledOutsideProd() {
        CallerContext devCaller = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        SecurityContextHolder.getContext().setAuthentication(new PreAuthenticatedAuthenticationToken(
            devCaller,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_STANDARD_EMPLOYEE"))
        ));

        // Disabled in prodLikeEnv -> fail closed 401
        assertThatThrownBy(() -> factory.resolveScope(devCaller, null))
            .isInstanceOf(AuthenticationRequiredException.class);

        // Enabled in dev + prod -> still fail closed 401
        MockEnvironment prodAndDevEnv = new MockEnvironment();
        prodAndDevEnv.setActiveProfiles("dev", "prod");
        prodAndDevEnv.setProperty("documind.security.dev-caller-fallback.enabled", "true");
        RbacVectorFilterFactory prodGuardedFactory = new RbacVectorFilterFactory(prodAndDevEnv);
        assertThatThrownBy(() -> prodGuardedFactory.resolveScope(devCaller, null))
            .isInstanceOf(AuthenticationRequiredException.class);

        // Enabled strictly in dev without prod -> accepted
        MockEnvironment devEnabledEnv = new MockEnvironment();
        devEnabledEnv.setActiveProfiles("dev");
        devEnabledEnv.setProperty("documind.security.dev-caller-fallback.enabled", "true");
        RbacVectorFilterFactory devFactory = new RbacVectorFilterFactory(devEnabledEnv);
        RbacVectorScope resolved = devFactory.resolveScope(devCaller, null);
        assertThat(resolved.caller()).isEqualTo(devCaller);
    }

    @Test
    void resolveScopeRejectsCrossCheckMismatchAndNonAdminDepartmentFilterWith403(CapturedOutput output) {
        setJwtPrincipal(SALES_EMPLOYEE_USER_ID, SALES_DEPT_ID, Role.STANDARD_EMPLOYEE);
        CallerContext matchingCaller = new CallerContext(SALES_EMPLOYEE_USER_ID, SALES_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.9");

        // Non-admin passing foreign departmentId -> 403
        assertThatThrownBy(() -> factory.resolveScope(matchingCaller, HR_DEPT_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // Non-admin passing even own departmentId -> 403 (Admin-only parameter)
        assertThatThrownBy(() -> factory.resolveScope(matchingCaller, SALES_DEPT_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // Spoofed CallerContext role/department/userId -> 403
        CallerContext spoofedRole = new CallerContext(SALES_EMPLOYEE_USER_ID, SALES_DEPT_ID, Role.SYSTEM_ADMIN, "10.0.0.9");
        assertThatThrownBy(() -> factory.resolveScope(spoofedRole, null))
            .isInstanceOf(DocumentAccessDeniedException.class);

        CallerContext spoofedDept = new CallerContext(SALES_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.9");
        assertThatThrownBy(() -> factory.resolveScope(spoofedDept, null))
            .isInstanceOf(DocumentAccessDeniedException.class);

        CallerContext spoofedUser = new CallerContext(HR_MANAGER_USER_ID, SALES_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.0.0.9");
        assertThatThrownBy(() -> factory.resolveScope(spoofedUser, null))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // Manager passing departmentId -> 403
        setJwtPrincipal(HR_MANAGER_USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER);
        CallerContext hrManager = new CallerContext(HR_MANAGER_USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "10.0.0.9");
        assertThatThrownBy(() -> factory.resolveScope(hrManager, SALES_DEPT_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);

        assertThat(output.getOut())
            .contains("SECURITY_AUDIT action=UNAUTHORIZED_CHAT_DEPARTMENT_FILTER")
            .contains("SECURITY_AUDIT action=VECTOR_CALLER_CONTEXT_MISMATCH");
    }

    @Test
    void assertAllAuthorizedGuardsPrePromptChunksAgainstAnyRbacViolation(CapturedOutput output) {
        setJwtPrincipal(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        RbacVectorScope hrEmployeeScope = factory.resolveScope(null, null);

        RetrievedChunk allowedPublic = chunk(SALES_DEPT_ID, AccessLevel.PUBLIC);
        RetrievedChunk allowedOwnDept = chunk(HR_DEPT_ID, AccessLevel.DEPARTMENT);
        RetrievedChunk forbiddenOwnConfidential = chunk(HR_DEPT_ID, AccessLevel.CONFIDENTIAL);
        RetrievedChunk forbiddenForeignDept = chunk(SALES_DEPT_ID, AccessLevel.DEPARTMENT);

        assertThatCode(() -> hrEmployeeScope.assertAllAuthorized(List.of(allowedPublic, allowedOwnDept)))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> hrEmployeeScope.assertAllAuthorized(List.of(allowedOwnDept, forbiddenOwnConfidential)))
            .isInstanceOf(DocumentAccessDeniedException.class);

        assertThatThrownBy(() -> hrEmployeeScope.assertAllAuthorized(List.of(forbiddenForeignDept)))
            .isInstanceOf(DocumentAccessDeniedException.class);

        assertThatThrownBy(() -> hrEmployeeScope.assertAllAuthorized(Arrays.asList(allowedPublic, null)))
            .isInstanceOf(DocumentAccessDeniedException.class);

        setJwtPrincipal(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN);
        RbacVectorScope adminSalesScope = factory.resolveScope(null, SALES_DEPT_ID);
        assertThatCode(() -> adminSalesScope.assertAllAuthorized(List.of(allowedPublic, forbiddenForeignDept)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> adminSalesScope.assertAllAuthorized(List.of(allowedOwnDept)))
            .isInstanceOf(DocumentAccessDeniedException.class);

        assertThat(output.getOut())
            .contains("SECURITY_ALERT action=PRE_PROMPT_RBAC_GUARD_BLOCKED")
            .doesNotContain("Nội dung tài liệu");
    }

    @Test
    void buildFilterExpressionAndCacheScopeKeyFailClosedOnInvalidCallerOrNonAdminDepartmentFilter() {
        CallerContext hrEmployee = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, null);
        CallerContext hrManager = new CallerContext(HR_MANAGER_USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, null);

        assertThatThrownBy(() -> RbacVectorFilterFactory.buildFilterExpression(null, null))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildFilterExpression(new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, null, null), null))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildFilterExpression(new CallerContext(HR_EMPLOYEE_USER_ID, null, Role.STANDARD_EMPLOYEE, null), null))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildFilterExpression(new CallerContext(HR_MANAGER_USER_ID, null, Role.DEPARTMENT_MANAGER, null), null))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildFilterExpression(hrManager, SALES_DEPT_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // RV-01 regression: buildCacheScopeKey must fail closed with 403 for any non-admin passing departmentFilter
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildCacheScopeKey(null, null))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildCacheScopeKey(new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, null, null), null))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildCacheScopeKey(new CallerContext(HR_EMPLOYEE_USER_ID, null, Role.STANDARD_EMPLOYEE, null), null))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildCacheScopeKey(hrEmployee, SALES_DEPT_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildCacheScopeKey(hrEmployee, HR_DEPT_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildCacheScopeKey(hrManager, SALES_DEPT_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> RbacVectorFilterFactory.buildCacheScopeKey(hrManager, HR_DEPT_ID))
            .isInstanceOf(DocumentAccessDeniedException.class);
    }

    private static void setJwtPrincipal(UUID userId, UUID departmentId, Role role) {
        CustomUserDetails principal = new CustomUserDetails(
            userId,
            departmentId,
            role.name().toLowerCase() + "@documind.ai",
            role.name(),
            role,
            "jti-" + userId,
            Instant.now().plusSeconds(900)
        );
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            principal.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static RetrievedChunk chunk(UUID departmentId, AccessLevel accessLevel) {
        UUID docId = UUID.randomUUID();
        CitationDto citation = new CitationDto(
            docId,
            "policy.pdf",
            1,
            null,
            0.91d,
            "Nội dung tài liệu",
            null
        );
        return new RetrievedChunk(
            UUID.randomUUID().toString(),
            docId,
            departmentId,
            accessLevel,
            0,
            "Nội dung tài liệu",
            citation
        );
    }
}
