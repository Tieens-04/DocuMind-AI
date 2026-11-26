package com.documind.controller;

import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminDepartmentResponse;
import com.documind.controller.dto.admin.AdminUserResponse;
import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.service.auth.RedisTokenBlacklistService;
import com.documind.service.auth.TokenBlacklistService;
import com.documind.service.ingestion.ChunkIdGenerator;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.ingestion.DocumentIngestionStateService;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class
})
class AdminAndDocumentRbacIntegrationTest {

    private static final String SEED_DEV_PASSWORD = "DocuMind@2026!";
    private static final String ADMIN_EMAIL = "admin@documind.ai";
    private static final String HR_MANAGER_EMAIL = "manager.hr@documind.ai";
    private static final String SALES_EMPLOYEE_EMAIL = "employee.sales@documind.ai";

    private static final UUID IT_DEPT_ID = AbstractIntegrationTest.IT_DEPT_ID;
    private static final UUID HR_DEPT_ID = AbstractIntegrationTest.HR_DEPT_ID;
    private static final UUID SALES_DEPT_ID = AbstractIntegrationTest.SALES_DEPT_ID;
    private static final UUID ADMIN_USER_ID = AbstractIntegrationTest.ADMIN_USER_ID;
    private static final UUID HR_MANAGER_USER_ID = AbstractIntegrationTest.HR_MANAGER_USER_ID;
    private static final UUID SALES_EMPLOYEE_USER_ID = AbstractIntegrationTest.SALES_EMPLOYEE_USER_ID;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private DocumentIngestionStateService documentIngestionStateService;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @BeforeEach
    void setUp() {
        cleanUpTestState();
    }

    @AfterEach
    void tearDown() {
        cleanUpTestState();
    }

    @Test
    void adminDepartmentAndUserCrudWorksEndToEndAndBlocksNonAdminRolesWith403() throws Exception {
        AuthTokenResponse adminSession = login(ADMIN_EMAIL, SEED_DEV_PASSWORD);
        AuthTokenResponse hrManagerSession = login(HR_MANAGER_EMAIL, SEED_DEV_PASSWORD);
        AuthTokenResponse salesEmployeeSession = login(SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD);

        // 1. Unauthenticated -> 401; MANAGER & EMPLOYEE -> 403 ACCESS_DENIED on /api/v1/admin/**
        HttpResponse<String> anonDepts = sendJson("GET", "/api/v1/admin/departments", null, null);
        assertThat(anonDepts.statusCode()).isEqualTo(401);

        for (String nonAdminToken : List.of(hrManagerSession.accessToken(), salesEmployeeSession.accessToken())) {
            HttpResponse<String> deptsForbidden = sendJson("GET", "/api/v1/admin/departments", null, nonAdminToken);
            assertThat(deptsForbidden.statusCode()).isEqualTo(403);
            assertThat(parseJson(deptsForbidden.body()).path("errorCode").asText()).isEqualTo("ACCESS_DENIED");

            HttpResponse<String> usersForbidden = sendJson("GET", "/api/v1/admin/users", null, nonAdminToken);
            assertThat(usersForbidden.statusCode()).isEqualTo(403);
            assertThat(parseJson(usersForbidden.body()).path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        }

        // 2. Admin creates FIN department, queries, updates, blocks duplicate code
        HttpResponse<String> createFinResp = sendJson(
            "POST",
            "/api/v1/admin/departments",
            Map.of("code", "  fin  ", "name", "Phòng Tài chính", "description", "Quản trị ngân sách"),
            adminSession.accessToken()
        );
        assertThat(createFinResp.statusCode()).isEqualTo(201);
        AdminDepartmentResponse finDept = objectMapper.readValue(createFinResp.body(), AdminDepartmentResponse.class);
        assertThat(finDept.code()).isEqualTo("FIN");
        assertThat(finDept.name()).isEqualTo("Phòng Tài chính");

        HttpResponse<String> dupFinResp = sendJson(
            "POST",
            "/api/v1/admin/departments",
            Map.of("code", "FiN", "name", "Duplicate Finance"),
            adminSession.accessToken()
        );
        assertThat(dupFinResp.statusCode()).isEqualTo(409);
        assertThat(parseJson(dupFinResp.body()).path("errorCode").asText()).isEqualTo("DUPLICATE_DEPARTMENT_CODE");

        HttpResponse<String> searchFinResp = sendJson(
            "GET",
            "/api/v1/admin/departments?q=Tài+chính&page=0&size=10",
            null,
            adminSession.accessToken()
        );
        assertThat(searchFinResp.statusCode()).isEqualTo(200);
        PageResponse<AdminDepartmentResponse> deptPage = objectMapper.readValue(
            searchFinResp.body(),
            new TypeReference<>() {}
        );
        assertThat(deptPage.items()).extracting(AdminDepartmentResponse::code).containsExactly("FIN");

        HttpResponse<String> patchFinResp = sendJson(
            "PATCH",
            "/api/v1/admin/departments/" + finDept.id(),
            Map.of("name", "Tài chính & Kế toán"),
            adminSession.accessToken()
        );
        assertThat(patchFinResp.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readValue(patchFinResp.body(), AdminDepartmentResponse.class).name())
            .isEqualTo("Tài chính & Kế toán");

        // GET /{id} 200 & 404 on departments
        HttpResponse<String> getFinResp = sendJson(
            "GET",
            "/api/v1/admin/departments/" + finDept.id(),
            null,
            adminSession.accessToken()
        );
        assertThat(getFinResp.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readValue(getFinResp.body(), AdminDepartmentResponse.class).description())
            .isEqualTo("Quản trị ngân sách");
        assertThat(sendJson("GET", "/api/v1/admin/departments/" + UUID.randomUUID(), null, adminSession.accessToken()).statusCode())
            .isEqualTo(404);

        // 3. Admin creates new user in FIN with surrounding whitespace in email -> trimmed 201 & real JWT login
        HttpResponse<String> createUserResp = sendJson(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email", "  Employee.Fin@DocuMind.AI  ",
                "fullName", "Nhân viên Tài chính",
                "password", "FinPass@2026!",
                "role", "STANDARD_EMPLOYEE",
                "departmentId", finDept.id().toString()
            ),
            adminSession.accessToken()
        );
        assertThat(createUserResp.statusCode()).isEqualTo(201);
        AdminUserResponse finUser = objectMapper.readValue(createUserResp.body(), AdminUserResponse.class);
        assertThat(finUser.email()).isEqualTo("employee.fin@documind.ai");
        assertThat(finUser.departmentCode()).isEqualTo("FIN");
        assertThat(createUserResp.body()).doesNotContain("passwordHash").doesNotContain("FinPass@2026!");

        // GET /{id} 200 & 404 on users, plus >72-byte Vietnamese password (<=72 chars) returns 400 INVALID_ADMIN_REQUEST (never 500)
        assertThat(sendJson("GET", "/api/v1/admin/users/" + finUser.id(), null, adminSession.accessToken()).statusCode())
            .isEqualTo(200);
        assertThat(sendJson("GET", "/api/v1/admin/users/" + UUID.randomUUID(), null, adminSession.accessToken()).statusCode())
            .isEqualTo(404);
        String tooLongUtf8Password = "MậtKhẩuTiếngViệtCóDấuVượtQuáGiớiHạnBảyMươiHaiByteNăm2026!";
        assertThat(tooLongUtf8Password.length()).isLessThanOrEqualTo(72);
        assertThat(tooLongUtf8Password.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
        HttpResponse<String> over72PasswordResp = sendJson(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email", "long.pass@documind.ai",
                "fullName", "Long Pass",
                "password", tooLongUtf8Password,
                "role", "STANDARD_EMPLOYEE",
                "departmentId", finDept.id().toString()
            ),
            adminSession.accessToken()
        );
        assertThat(over72PasswordResp.statusCode()).isEqualTo(400);
        assertThat(parseJson(over72PasswordResp.body()).path("errorCode").asText()).isEqualTo("INVALID_ADMIN_REQUEST");

        // Creating an inactive user immediately blocks login with 403 ACCOUNT_DISABLED
        HttpResponse<String> createInactiveResp = sendJson(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email", "inactive.fin@documind.ai",
                "fullName", "Inactive User",
                "password", "Inactive@2026!",
                "role", "STANDARD_EMPLOYEE",
                "departmentId", finDept.id().toString(),
                "isActive", false
            ),
            adminSession.accessToken()
        );
        assertThat(createInactiveResp.statusCode()).isEqualTo(201);
        HttpResponse<String> inactiveLoginResp = sendJson(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", "inactive.fin@documind.ai", "password", "Inactive@2026!"),
            null
        );
        assertThat(inactiveLoginResp.statusCode()).isEqualTo(403);
        assertThat(parseJson(inactiveLoginResp.body()).path("errorCode").asText()).isEqualTo("ACCOUNT_DISABLED");

        AuthTokenResponse finUserLogin = login("employee.fin@documind.ai", "FinPass@2026!");
        assertThat(finUserLogin.user().departmentCode()).isEqualTo("FIN");
        assertThat(finUserLogin.user().role()).isEqualTo(Role.STANDARD_EMPLOYEE);

        // 4. Filter users endpoint (no LazyInitializationException with open-in-view=false)
        HttpResponse<String> listUsersResp = sendJson(
            "GET",
            "/api/v1/admin/users?departmentId=" + finDept.id() + "&role=STANDARD_EMPLOYEE&isActive=true&q=employee.fin",
            null,
            adminSession.accessToken()
        );
        assertThat(listUsersResp.statusCode()).isEqualTo(200);
        PageResponse<AdminUserResponse> userPage = objectMapper.readValue(listUsersResp.body(), new TypeReference<>() {});
        assertThat(userPage.items()).extracting(AdminUserResponse::id).containsExactly(finUser.id());

        // 5. Deleting department with assigned user -> 409 DEPARTMENT_IN_USE; deleting unused department -> 204
        HttpResponse<String> deleteFinInUse = sendJson(
            "DELETE",
            "/api/v1/admin/departments/" + finDept.id(),
            null,
            adminSession.accessToken()
        );
        assertThat(deleteFinInUse.statusCode()).isEqualTo(409);
        assertThat(parseJson(deleteFinInUse.body()).path("errorCode").asText()).isEqualTo("DEPARTMENT_IN_USE");

        HttpResponse<String> createTempResp = sendJson(
            "POST",
            "/api/v1/admin/departments",
            Map.of("code", "TEMP_OPS", "name", "Temporary Ops"),
            adminSession.accessToken()
        );
        UUID tempDeptId = objectMapper.readValue(createTempResp.body(), AdminDepartmentResponse.class).id();
        HttpResponse<String> deleteTempResp = sendJson(
            "DELETE",
            "/api/v1/admin/departments/" + tempDeptId,
            null,
            adminSession.accessToken()
        );
        assertThat(deleteTempResp.statusCode()).isEqualTo(204);
    }

    @ParameterizedTest(name = "{0} ({1} in {2})")
    @MethodSource("mandatoryRbacTruthMatrix")
    void enforcesMandatoryRbacTruthMatrixAcrossReadUpdateUploadAndDelete(
        String label,
        Role callerRole,
        String callerDeptCode,
        boolean canReadHrPublic,
        boolean canReadHrDept,
        boolean canReadHrConfidential,
        boolean canReadItDept,
        boolean canManageHr,
        boolean canManageIt
    ) throws Exception {
        AuthTokenResponse adminSession = login(ADMIN_EMAIL, SEED_DEV_PASSWORD);

        // Seed 4 canonical documents across HR & IT
        DocumentResponse hrPublic = uploadDocument(
            adminSession.accessToken(), "hr-public.txt", "HR public handbook", AccessLevel.PUBLIC, HR_DEPT_ID
        );
        DocumentResponse hrDept = uploadDocument(
            adminSession.accessToken(), "hr-dept.txt", "HR internal leave policy", AccessLevel.DEPARTMENT, HR_DEPT_ID
        );
        DocumentResponse hrConfidential = uploadDocument(
            adminSession.accessToken(), "hr-confidential.txt", "HR executive salary band", AccessLevel.CONFIDENTIAL, HR_DEPT_ID
        );
        DocumentResponse itDept = uploadDocument(
            adminSession.accessToken(), "it-dept.txt", "IT network architecture", AccessLevel.DEPARTMENT, IT_DEPT_ID
        );

        UUID callerDeptId = switch (callerDeptCode) {
            case "IT" -> IT_DEPT_ID;
            case "HR" -> HR_DEPT_ID;
            case "SALES" -> SALES_DEPT_ID;
            default -> throw new IllegalArgumentException("Unknown dept: " + callerDeptCode);
        };

        String callerToken = provisionAndLoginActor(adminSession.accessToken(), callerRole, callerDeptId, callerDeptCode);

        // 1. Listing visibility check
        HttpResponse<String> listResp = sendJson("GET", "/api/v1/documents?page=0&size=50", null, callerToken);
        assertThat(listResp.statusCode()).isEqualTo(200);
        PageResponse<DocumentResponse> visiblePage = objectMapper.readValue(listResp.body(), new TypeReference<>() {});
        Set<UUID> visibleIds = visiblePage.items().stream().map(DocumentResponse::id).collect(java.util.stream.Collectors.toSet());

        assertThat(visibleIds.contains(hrPublic.id())).isEqualTo(canReadHrPublic);
        assertThat(visibleIds.contains(hrDept.id())).isEqualTo(canReadHrDept);
        assertThat(visibleIds.contains(hrConfidential.id())).isEqualTo(canReadHrConfidential);
        assertThat(visibleIds.contains(itDept.id())).isEqualTo(canReadItDept);

        // 2. Single-document GET /{id} and GET /{id}/status check
        assertReadAccess(callerToken, hrPublic.id(), canReadHrPublic);
        assertReadAccess(callerToken, hrDept.id(), canReadHrDept);
        assertReadAccess(callerToken, hrConfidential.id(), canReadHrConfidential);
        assertReadAccess(callerToken, itDept.id(), canReadItDept);

        // 3. PATCH metadata check on HR internal, HR public, and IT internal
        HttpResponse<String> patchHrDept = sendJson(
            "PATCH",
            "/api/v1/documents/" + hrDept.id(),
            Map.of("originalName", "hr-dept-edited.txt"),
            callerToken
        );
        assertThat(patchHrDept.statusCode()).isEqualTo(canManageHr ? 200 : 403);

        HttpResponse<String> patchHrPublic = sendJson(
            "PATCH",
            "/api/v1/documents/" + hrPublic.id(),
            Map.of("originalName", "hr-public-edited.txt"),
            callerToken
        );
        assertThat(patchHrPublic.statusCode()).isEqualTo(canManageHr ? 200 : 403);

        HttpResponse<String> patchItDept = sendJson(
            "PATCH",
            "/api/v1/documents/" + itDept.id(),
            Map.of("originalName", "it-dept-edited.txt"),
            callerToken
        );
        assertThat(patchItDept.statusCode()).isEqualTo(canManageIt ? 200 : 403);

        // 4. Upload check into HR and IT
        HttpResponse<String> uploadHr = uploadRaw(
            callerToken, "new-hr.txt", "Unique HR content " + UUID.randomUUID(), AccessLevel.CONFIDENTIAL, HR_DEPT_ID
        );
        assertThat(uploadHr.statusCode()).isEqualTo(canManageHr ? 202 : 403);

        HttpResponse<String> uploadIt = uploadRaw(
            callerToken, "new-it.txt", "Unique IT content " + UUID.randomUUID(), AccessLevel.DEPARTMENT, IT_DEPT_ID
        );
        assertThat(uploadIt.statusCode()).isEqualTo(canManageIt ? 202 : 403);

        // 5. Delete check on HR and IT documents
        HttpResponse<String> deleteHr = sendJson("DELETE", "/api/v1/documents/" + hrConfidential.id(), null, callerToken);
        assertThat(deleteHr.statusCode()).isEqualTo(canManageHr ? 204 : 403);

        HttpResponse<String> deleteIt = sendJson("DELETE", "/api/v1/documents/" + itDept.id(), null, callerToken);
        assertThat(deleteIt.statusCode()).isEqualTo(canManageIt ? 204 : 403);
    }

    static Stream<Arguments> mandatoryRbacTruthMatrix() {
        return Stream.of(
            Arguments.of("SYSTEM_ADMIN in IT", Role.SYSTEM_ADMIN, "IT", true, true, true, true, true, true),
            Arguments.of("DEPARTMENT_MANAGER in HR", Role.DEPARTMENT_MANAGER, "HR", true, true, true, false, true, false),
            Arguments.of("DEPARTMENT_MANAGER in IT (Jira AC-1)", Role.DEPARTMENT_MANAGER, "IT", true, false, false, true, false, true),
            Arguments.of("STANDARD_EMPLOYEE in HR", Role.STANDARD_EMPLOYEE, "HR", true, true, false, false, false, false),
            Arguments.of("STANDARD_EMPLOYEE in SALES", Role.STANDARD_EMPLOYEE, "SALES", true, false, false, false, false, false)
        );
    }

    @Test
    void patchDocumentMetadataTriggersV6ChunkPropagationAndSingleCacheVersionIncrement() throws Exception {
        AuthTokenResponse hrManager = login(HR_MANAGER_EMAIL, SEED_DEV_PASSWORD);
        AuthTokenResponse adminSession = login(ADMIN_EMAIL, SEED_DEV_PASSWORD);

        DocumentResponse hrDoc = uploadDocument(
            hrManager.accessToken(),
            "chinh-sach-cu.txt",
            "Noi dung chinh sach nhan su Q4",
            AccessLevel.DEPARTMENT,
            HR_DEPT_ID
        );

        // Insert a real chunk row in pgvector matching parent document metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.DOCUMENT_ID, hrDoc.id().toString());
        metadata.put(ChunkMetadataKeys.DEPARTMENT_ID, HR_DEPT_ID.toString());
        metadata.put(ChunkMetadataKeys.FILE_NAME, hrDoc.originalName());
        metadata.put(ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.DEPARTMENT.name());
        metadata.put(ChunkMetadataKeys.DOC_VERSION, 1);
        metadata.put(ChunkMetadataKeys.CHUNK_INDEX, 0);
        vectorStore.add(List.of(new Document(ChunkIdGenerator.generate(hrDoc.id(), 1, 0).toString(),
            "Noi dung chinh sach nhan su Q4", metadata)));

        long hrCacheBefore = getDepartmentCacheVersion(HR_DEPT_ID);
        long itCacheBefore = getDepartmentCacheVersion(IT_DEPT_ID);

        // 1. HR Manager reclassifies DEPARTMENT -> CONFIDENTIAL and renames file
        HttpResponse<String> patchResp = sendJson(
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("originalName", "Chính sách lương mật.txt", "accessLevel", "CONFIDENTIAL"),
            hrManager.accessToken()
        );
        assertThat(patchResp.statusCode()).isEqualTo(200);
        DocumentResponse patched = objectMapper.readValue(patchResp.body(), DocumentResponse.class);
        assertThat(patched.originalName()).isEqualTo("Chính sách lương mật.txt");
        assertThat(patched.accessLevel()).isEqualTo(AccessLevel.CONFIDENTIAL);

        Map<String, Object> chunkMetaAfterManagerPatch = jdbcTemplate.queryForMap(
            """
            SELECT metadata->>'access_level' AS access_level,
                   metadata->>'file_name' AS file_name,
                   metadata->>'department_id' AS department_id
            FROM document_chunks
            WHERE document_id = ?
            """,
            hrDoc.id()
        );
        assertThat(chunkMetaAfterManagerPatch.get("access_level")).isEqualTo("CONFIDENTIAL");
        assertThat(chunkMetaAfterManagerPatch.get("file_name")).isEqualTo("Chính sách lương mật.txt");
        assertThat(chunkMetaAfterManagerPatch.get("department_id")).isEqualTo(HR_DEPT_ID.toString());
        assertThat(getDepartmentCacheVersion(HR_DEPT_ID)).isEqualTo(hrCacheBefore + 1L);
        assertThat(getDepartmentCacheVersion(IT_DEPT_ID)).isEqualTo(itCacheBefore);

        // 2a. SYSTEM_ADMIN transfers CONFIDENTIAL document from HR -> IT (non-PUBLIC V6 branch: HR +1, IT +1, SALES +0)
        long hrCacheBeforeTransfer = getDepartmentCacheVersion(HR_DEPT_ID);
        long itCacheBeforeTransfer = getDepartmentCacheVersion(IT_DEPT_ID);
        long salesCacheBeforeTransfer = getDepartmentCacheVersion(SALES_DEPT_ID);

        HttpResponse<String> nonPublicTransferResp = sendJson(
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("departmentId", IT_DEPT_ID.toString()),
            adminSession.accessToken()
        );
        assertThat(nonPublicTransferResp.statusCode()).isEqualTo(200);
        Map<String, Object> chunkMetaAfterTransfer = jdbcTemplate.queryForMap(
            """
            SELECT metadata->>'access_level' AS access_level,
                   metadata->>'department_id' AS department_id
            FROM document_chunks
            WHERE document_id = ?
            """,
            hrDoc.id()
        );
        assertThat(chunkMetaAfterTransfer.get("access_level")).isEqualTo("CONFIDENTIAL");
        assertThat(chunkMetaAfterTransfer.get("department_id")).isEqualTo(IT_DEPT_ID.toString());
        assertThat(getDepartmentCacheVersion(HR_DEPT_ID)).isEqualTo(hrCacheBeforeTransfer + 1L);
        assertThat(getDepartmentCacheVersion(IT_DEPT_ID)).isEqualTo(itCacheBeforeTransfer + 1L);
        assertThat(getDepartmentCacheVersion(SALES_DEPT_ID)).isEqualTo(salesCacheBeforeTransfer);

        // 2b. SYSTEM_ADMIN publishes document as PUBLIC -> V6 bumps ALL department cache versions
        long hrCacheBeforeAdmin = getDepartmentCacheVersion(HR_DEPT_ID);
        long itCacheBeforeAdmin = getDepartmentCacheVersion(IT_DEPT_ID);
        long salesCacheBeforeAdmin = getDepartmentCacheVersion(SALES_DEPT_ID);

        HttpResponse<String> adminPublishResp = sendJson(
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("accessLevel", "PUBLIC"),
            adminSession.accessToken()
        );
        assertThat(adminPublishResp.statusCode()).isEqualTo(200);

        Map<String, Object> chunkMetaAfterAdminTransfer = jdbcTemplate.queryForMap(
            """
            SELECT metadata->>'access_level' AS access_level,
                   metadata->>'department_id' AS department_id
            FROM document_chunks
            WHERE document_id = ?
            """,
            hrDoc.id()
        );
        assertThat(chunkMetaAfterAdminTransfer.get("access_level")).isEqualTo("PUBLIC");
        assertThat(chunkMetaAfterAdminTransfer.get("department_id")).isEqualTo(IT_DEPT_ID.toString());
        assertThat(getDepartmentCacheVersion(HR_DEPT_ID)).isEqualTo(hrCacheBeforeAdmin + 1L);
        assertThat(getDepartmentCacheVersion(IT_DEPT_ID)).isEqualTo(itCacheBeforeAdmin + 1L);
        assertThat(getDepartmentCacheVersion(SALES_DEPT_ID)).isEqualTo(salesCacheBeforeAdmin + 1L);

        // 3. Unauthenticated (401) and missing document (404) checks on GET/PATCH /api/v1/documents/{id}
        assertThat(sendJson("GET", "/api/v1/documents/" + hrDoc.id(), null, null).statusCode()).isEqualTo(401);
        assertThat(sendJson("PATCH", "/api/v1/documents/" + hrDoc.id(), Map.of("accessLevel", "PUBLIC"), null).statusCode())
            .isEqualTo(401);
        assertThat(sendJson("GET", "/api/v1/documents/" + UUID.randomUUID(), null, adminSession.accessToken()).statusCode())
            .isEqualTo(404);
        assertThat(sendJson("PATCH", "/api/v1/documents/" + UUID.randomUUID(), Map.of("accessLevel", "PUBLIC"), adminSession.accessToken()).statusCode())
            .isEqualTo(404);

        // 4. F-02: PATCH while document is actively PROCESSING bumps version, resets PENDING, and fences stale worker
        jdbcTemplate.update("UPDATE documents SET status = 'PROCESSING', chunk_count = 1 WHERE id = ?", hrDoc.id());
        HttpResponse<String> patchWhileProcessingResp = sendJson(
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("accessLevel", "CONFIDENTIAL", "originalName", "Khóa khẩn cấp.txt"),
            adminSession.accessToken()
        );
        assertThat(patchWhileProcessingResp.statusCode()).isEqualTo(200);
        DocumentResponse requeued = objectMapper.readValue(patchWhileProcessingResp.body(), DocumentResponse.class);
        assertThat(requeued.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(requeued.currentVersion()).isEqualTo(2);
        assertThat(requeued.chunkCount()).isEqualTo(0);

        // Stale v1 worker hitting V7 23514 failure cannot overwrite v2 status to FAILED
        boolean markedStaleFailed = documentIngestionStateService.markFailed(hrDoc.id(), 1, "simulated V7 23514 mismatch");
        assertThat(markedStaleFailed).isFalse();
        String persistedStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM documents WHERE id = ?", String.class, hrDoc.id()
        );
        assertThat(persistedStatus).isEqualTo("PENDING");
    }

    @Test
    void adminSecurityChangesImmediatelyRevokeActiveJwtAndProtectAgainstDualAdminLockoutRace() throws Exception {
        AuthTokenResponse adminSession = login(ADMIN_EMAIL, SEED_DEV_PASSWORD);

        // 1. Provision target employee & verify active session works
        HttpResponse<String> createTargetResp = sendJson(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email", "target.user@documind.ai",
                "fullName", "Target User",
                "password", "InitialPass@2026!",
                "role", "STANDARD_EMPLOYEE",
                "departmentId", HR_DEPT_ID.toString()
            ),
            adminSession.accessToken()
        );
        assertThat(createTargetResp.statusCode()).isEqualTo(201);
        UUID targetUserId = objectMapper.readValue(createTargetResp.body(), AdminUserResponse.class).id();

        AuthTokenResponse initialTargetSession = login("target.user@documind.ai", "InitialPass@2026!");
        assertThat(sendJson("GET", "/api/v1/documents", null, initialTargetSession.accessToken()).statusCode())
            .isEqualTo(200);

        // 2. Admin transfers user to IT & promotes to DEPARTMENT_MANAGER -> 0s cutoff on old Access & Refresh tokens
        HttpResponse<String> promoteResp = sendJson(
            "PATCH",
            "/api/v1/admin/users/" + targetUserId,
            Map.of("role", "DEPARTMENT_MANAGER", "departmentId", IT_DEPT_ID.toString()),
            adminSession.accessToken()
        );
        assertThat(promoteResp.statusCode()).isEqualTo(200);

        // Old access token immediately rejected with 401 TOKEN_REVOKED (zero sleep)
        HttpResponse<String> staleAccessResp = sendJson("GET", "/api/v1/documents", null, initialTargetSession.accessToken());
        assertThat(staleAccessResp.statusCode()).isEqualTo(401);
        assertThat(parseJson(staleAccessResp.body()).path("errorCode").asText()).isEqualTo("TOKEN_REVOKED");

        // Old refresh token immediately revoked in PostgreSQL
        HttpResponse<String> staleRefreshResp = sendJson(
            "POST",
            "/api/v1/auth/refresh",
            Map.of("refreshToken", initialTargetSession.refreshToken()),
            null
        );
        assertThat(staleRefreshResp.statusCode()).isEqualTo(401);

        // Redis user revocation key exists with bounded TTL (<= 900s)
        String redisKey = RedisTokenBlacklistService.USER_REVOKE_KEY_PREFIX + targetUserId;
        Long ttlSeconds = stringRedisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull().isPositive().isLessThanOrEqualTo(900L);

        // Re-login immediately succeeds even within the exact same second thanks to MonotonicClockMillis
        AuthTokenResponse promotedSession = login("target.user@documind.ai", "InitialPass@2026!");
        assertThat(promotedSession.user().role()).isEqualTo(Role.DEPARTMENT_MANAGER);
        assertThat(promotedSession.user().departmentId()).isEqualTo(IT_DEPT_ID);
        assertThat(sendJson("GET", "/api/v1/documents", null, promotedSession.accessToken()).statusCode())
            .isEqualTo(200);

        // 3. Admin resets password -> old token cut off immediately; new password logs in
        HttpResponse<String> resetResp = sendJson(
            "PUT",
            "/api/v1/admin/users/" + targetUserId + "/password",
            Map.of("newPassword", "RotatedPass@2026!"),
            adminSession.accessToken()
        );
        assertThat(resetResp.statusCode()).isEqualTo(204);
        assertThat(sendJson("GET", "/api/v1/documents", null, promotedSession.accessToken()).statusCode())
            .isEqualTo(401);

        AuthTokenResponse afterResetSession = login("target.user@documind.ai", "RotatedPass@2026!");
        assertThat(sendJson("GET", "/api/v1/documents", null, afterResetSession.accessToken()).statusCode())
            .isEqualTo(200);

        // 4. Admin deactivates user -> active token cut off (401) & re-login blocked (403 ACCOUNT_DISABLED)
        HttpResponse<String> deactivateResp = sendJson(
            "PATCH",
            "/api/v1/admin/users/" + targetUserId,
            Map.of("isActive", false),
            adminSession.accessToken()
        );
        assertThat(deactivateResp.statusCode()).isEqualTo(200);
        assertThat(sendJson("GET", "/api/v1/documents", null, afterResetSession.accessToken()).statusCode())
            .isEqualTo(401);
        HttpResponse<String> disabledLogin = sendJson(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", "target.user@documind.ai", "password", "RotatedPass@2026!"),
            null
        );
        assertThat(disabledLogin.statusCode()).isEqualTo(403);
        assertThat(parseJson(disabledLogin.body()).path("errorCode").asText()).isEqualTo("ACCOUNT_DISABLED");

        // 5. Admin self-modification guard (409 ADMIN_SELF_MODIFICATION_FORBIDDEN)
        HttpResponse<String> selfDeactivate = sendJson(
            "PATCH",
            "/api/v1/admin/users/" + ADMIN_USER_ID,
            Map.of("isActive", false),
            adminSession.accessToken()
        );
        assertThat(selfDeactivate.statusCode()).isEqualTo(409);
        assertThat(parseJson(selfDeactivate.body()).path("errorCode").asText())
            .isEqualTo("ADMIN_SELF_MODIFICATION_FORBIDDEN");

        // 6. Concurrent Dual-Admin cross-deactivation race on Virtual Threads -> always retains >= 1 active admin
        HttpResponse<String> createSecondAdminResp = sendJson(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email", "admin.second@documind.ai",
                "fullName", "Second Admin",
                "password", "SecondAdmin@2026!",
                "role", "SYSTEM_ADMIN",
                "departmentId", IT_DEPT_ID.toString()
            ),
            adminSession.accessToken()
        );
        assertThat(createSecondAdminResp.statusCode()).isEqualTo(201);
        UUID secondAdminId = objectMapper.readValue(createSecondAdminResp.body(), AdminUserResponse.class).id();
        AuthTokenResponse secondAdminSession = login("admin.second@documind.ai", "SecondAdmin@2026!");

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<HttpResponse<String>> adminADeactivatesB = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return sendJson(
                    "PATCH",
                    "/api/v1/admin/users/" + secondAdminId,
                    Map.of("isActive", false),
                    adminSession.accessToken()
                );
            };
            Callable<HttpResponse<String>> adminBDeactivatesA = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return sendJson(
                    "PATCH",
                    "/api/v1/admin/users/" + ADMIN_USER_ID,
                    Map.of("isActive", false),
                    secondAdminSession.accessToken()
                );
            };

            Future<HttpResponse<String>> futureA = executor.submit(adminADeactivatesB);
            Future<HttpResponse<String>> futureB = executor.submit(adminBDeactivatesA);
            HttpResponse<String> respA = futureA.get(15, TimeUnit.SECONDS);
            HttpResponse<String> respB = futureB.get(15, TimeUnit.SECONDS);

            List<Integer> statuses = List.of(respA.statusCode(), respB.statusCode());
            assertThat(statuses).contains(200);
            assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1L);
            assertThat(statuses.stream().filter(code -> code == 409 || code == 401 || code == 403).count()).isEqualTo(1L);
        }

        Integer activeAdminCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'SYSTEM_ADMIN' AND is_active = TRUE",
            Integer.class
        );
        assertThat(activeAdminCount).isEqualTo(1);

        // 7. F-01: Even if Redis loses revocation keys (simulated restart), deactivated admin is still blocked 403 via DB check
        String deactivatedAdminToken = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT is_active FROM users WHERE id = ?", Boolean.class, secondAdminId
        )) ? adminSession.accessToken() : secondAdminSession.accessToken();
        Set<String> allRevokeKeys = stringRedisTemplate.keys(RedisTokenBlacklistService.USER_REVOKE_KEY_PREFIX + "*");
        if (allRevokeKeys != null && !allRevokeKeys.isEmpty()) {
            stringRedisTemplate.delete(allRevokeKeys);
        }
        HttpResponse<String> staleAdminRead = sendJson("GET", "/api/v1/admin/users", null, deactivatedAdminToken);
        assertThat(staleAdminRead.statusCode()).isEqualTo(403);
        assertThat(parseJson(staleAdminRead.body()).path("errorCode").asText()).isEqualTo("ACCESS_DENIED");

        // 8. F-03 & F-08: Restore primary admin, verify zero lost updates under concurrent PATCH & cluster-monotonic Lua cutoff
        jdbcTemplate.update("UPDATE users SET is_active = TRUE WHERE id = ?", ADMIN_USER_ID);
        AuthTokenResponse freshAdmin = login(ADMIN_EMAIL, SEED_DEV_PASSWORD);

        HttpResponse<String> createRaceTarget = sendJson(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email", "lost.update@documind.ai",
                "fullName", "Before Name",
                "password", "RacePass@2026!",
                "role", "STANDARD_EMPLOYEE",
                "departmentId", HR_DEPT_ID.toString()
            ),
            freshAdmin.accessToken()
        );
        UUID raceUserId = objectMapper.readValue(createRaceTarget.body(), AdminUserResponse.class).id();

        CyclicBarrier patchBarrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> deactivateFuture = executor.submit(() -> {
                patchBarrier.await(10, TimeUnit.SECONDS);
                return sendJson("PATCH", "/api/v1/admin/users/" + raceUserId, Map.of("isActive", false), freshAdmin.accessToken());
            });
            Future<HttpResponse<String>> renameFuture = executor.submit(() -> {
                patchBarrier.await(10, TimeUnit.SECONDS);
                return sendJson("PATCH", "/api/v1/admin/users/" + raceUserId, Map.of("fullName", "After Concurrent Rename"), freshAdmin.accessToken());
            });
            assertThat(deactivateFuture.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(renameFuture.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        Map<String, Object> raceRow = jdbcTemplate.queryForMap(
            "SELECT full_name, is_active FROM users WHERE id = ?", raceUserId
        );
        assertThat(raceRow.get("full_name")).isEqualTo("After Concurrent Rename");
        assertThat(raceRow.get("is_active")).isEqualTo(false);

        // Cluster-monotonic Redis Lua script strictly advances an existing higher cutoff (never regresses on clock skew)
        String luaKey = RedisTokenBlacklistService.USER_REVOKE_KEY_PREFIX + raceUserId;
        long futureSkewMillis = System.currentTimeMillis() + 250L;
        stringRedisTemplate.opsForValue().set(luaKey, String.valueOf(futureSkewMillis), Duration.ofMinutes(15));
        tokenBlacklistService.revokeAllUserTokens(raceUserId, Duration.ofMinutes(15));
        long updatedCutoff = Long.parseLong(Objects.requireNonNull(stringRedisTemplate.opsForValue().get(luaKey)));
        assertThat(updatedCutoff).isGreaterThanOrEqualTo(futureSkewMillis + 1L);
    }

    private String provisionAndLoginActor(String adminToken, Role role, UUID deptId, String deptCode) throws Exception {
        String email = role.name().toLowerCase() + "." + deptCode.toLowerCase() + "."
            + UUID.randomUUID().toString().substring(0, 8) + "@documind.ai";
        String password = "MatrixUser@2026!";
        HttpResponse<String> resp = sendJson(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email", email,
                "fullName", role.name() + " " + deptCode,
                "password", password,
                "role", role.name(),
                "departmentId", deptId.toString()
            ),
            adminToken
        );
        assertThat(resp.statusCode()).isEqualTo(201);
        return login(email, password).accessToken();
    }

    private void assertReadAccess(String bearerToken, UUID docId, boolean expectedAllowed) throws Exception {
        int expectedStatus = expectedAllowed ? 200 : 403;
        HttpResponse<String> detailResp = sendJson("GET", "/api/v1/documents/" + docId, null, bearerToken);
        assertThat(detailResp.statusCode()).isEqualTo(expectedStatus);

        HttpResponse<String> statusResp = sendJson("GET", "/api/v1/documents/" + docId + "/status", null, bearerToken);
        assertThat(statusResp.statusCode()).isEqualTo(expectedStatus);
    }

    private AuthTokenResponse login(String email, String password) throws Exception {
        HttpResponse<String> resp = sendJson(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", password),
            null
        );
        assertThat(resp.statusCode())
            .withFailMessage("Expected 200 login for %s, got %s: %s", email, resp.statusCode(), resp.body())
            .isEqualTo(200);
        return objectMapper.readValue(resp.body(), AuthTokenResponse.class);
    }

    private DocumentResponse uploadDocument(
        String bearerToken,
        String fileName,
        String content,
        AccessLevel accessLevel,
        UUID targetDepartmentId
    ) throws Exception {
        HttpResponse<String> resp = uploadRaw(bearerToken, fileName, content, accessLevel, targetDepartmentId);
        assertThat(resp.statusCode())
            .withFailMessage("Expected 202 upload for %s, got %s: %s", fileName, resp.statusCode(), resp.body())
            .isEqualTo(202);
        return objectMapper.readValue(resp.body(), DocumentResponse.class);
    }

    private HttpResponse<String> uploadRaw(
        String bearerToken,
        String fileName,
        String content,
        AccessLevel accessLevel,
        UUID targetDepartmentId
    ) throws Exception {
        String boundary = "----DocuMindBoundary" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("--").append(boundary).append("\r\n");
        bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
        bodyBuilder.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
        bodyBuilder.append(content).append("\r\n");
        bodyBuilder.append("--").append(boundary).append("--\r\n");

        StringBuilder path = new StringBuilder("/api/v1/documents/upload?accessLevel=").append(accessLevel.name());
        if (targetDepartmentId != null) {
            path.append("&departmentId=").append(targetDepartmentId);
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(bodyBuilder.toString(), StandardCharsets.UTF_8));
        if (bearerToken != null) {
            reqBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendJson(String method, String path, Object body, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path));
        if (bearerToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        HttpRequest.BodyPublisher publisher;
        if (body != null) {
            builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            publisher = HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(body),
                StandardCharsets.UTF_8
            );
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }
        builder.method(method, publisher);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode parseJson(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private long getDepartmentCacheVersion(UUID departmentId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT cache_version FROM departments WHERE id = ?",
            Long.class,
            departmentId
        ));
    }

    private void cleanUpTestState() {
        jdbcTemplate.execute("DELETE FROM audit_logs");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM document_chunks");
        jdbcTemplate.execute("DELETE FROM documents");
        jdbcTemplate.update(
            "DELETE FROM users WHERE id NOT IN (?, ?, ?)",
            ADMIN_USER_ID,
            HR_MANAGER_USER_ID,
            SALES_EMPLOYEE_USER_ID
        );
        jdbcTemplate.update(
            "UPDATE users SET is_active = TRUE, role = 'SYSTEM_ADMIN', department_id = ? WHERE id = ?",
            IT_DEPT_ID,
            ADMIN_USER_ID
        );
        jdbcTemplate.update(
            "DELETE FROM departments WHERE id NOT IN (?, ?, ?)",
            IT_DEPT_ID,
            HR_DEPT_ID,
            SALES_DEPT_ID
        );
        jdbcTemplate.execute("UPDATE departments SET cache_version = 1");

        List<String> keys = new ArrayList<>();
        Set<String> jtiKeys = stringRedisTemplate.keys(RedisTokenBlacklistService.KEY_PREFIX + "*");
        if (jtiKeys != null) {
            keys.addAll(jtiKeys);
        }
        Set<String> userRevokeKeys = stringRedisTemplate.keys(RedisTokenBlacklistService.USER_REVOKE_KEY_PREFIX + "*");
        if (userRevokeKeys != null) {
            keys.addAll(userRevokeKeys);
        }
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }

        String bucket = storageProperties.bucket();
        List<S3Object> objects = s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents();
        for (S3Object obj : objects) {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build());
        }
    }
}
