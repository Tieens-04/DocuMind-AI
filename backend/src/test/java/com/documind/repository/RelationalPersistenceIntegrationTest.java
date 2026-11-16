package com.documind.repository;

import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.entity.ChatSessionEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.entity.RefreshTokenEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.enums.Role;
import com.documind.domain.model.CitationPayload;
import com.documind.support.PostgresTestcontainersConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("dev")
@Import(PostgresTestcontainersConfig.class)
@Transactional
class RelationalPersistenceIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void contextBootsWithHibernateDdlAutoValidate() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void userRepositoryFindsByEmailIgnoreCaseWithEagerlyFetchedDepartment() {
        UserEntity manager = userRepository.findByEmailIgnoreCase("MANAGER.HR@documind.ai")
            .orElseThrow();

        PersistenceUnitUtil unitUtil = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(unitUtil.isLoaded(manager, "department")).isTrue();
        assertThat(manager.getRole()).isEqualTo(Role.DEPARTMENT_MANAGER);
        assertThat(manager.getDepartment().getCode()).isEqualTo("HR");
        assertThat(manager.isActive()).isTrue();

        assertThat(userRepository.existsByEmailIgnoreCase("ADMIN@documind.ai")).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("missing@documind.ai")).isFalse();
    }

    @Test
    void departmentRepositoryIncrementsSingleAndAllCacheVersionsAndSumsVersions() {
        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("hr").orElseThrow();
        assertThat(hr.getCacheVersion()).isEqualTo(1L);

        int updatedSingle = departmentRepository.incrementCacheVersion(hr.getId());
        assertThat(updatedSingle).isEqualTo(1);

        DepartmentEntity refreshedHr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        DepartmentEntity refreshedIt = departmentRepository.findByCodeIgnoreCase("IT").orElseThrow();
        DepartmentEntity refreshedSales = departmentRepository.findByCodeIgnoreCase("SALES").orElseThrow();
        assertThat(refreshedHr.getCacheVersion()).isEqualTo(2L);
        assertThat(refreshedIt.getCacheVersion()).isEqualTo(1L);
        assertThat(refreshedSales.getCacheVersion()).isEqualTo(1L);

        int updatedAll = departmentRepository.incrementAllCacheVersions();
        assertThat(updatedAll).isEqualTo(3);

        assertThat(departmentRepository.findByCodeIgnoreCase("IT").orElseThrow().getCacheVersion()).isEqualTo(2L);
        assertThat(departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getCacheVersion()).isEqualTo(3L);
        assertThat(departmentRepository.findByCodeIgnoreCase("SALES").orElseThrow().getCacheVersion()).isEqualTo(2L);
        assertThat(departmentRepository.sumCacheVersions()).isEqualTo(7L);
    }

    @Test
    void documentRepositoryEnforcesUniqueFileHashPerDepartmentOnly() {
        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        DepartmentEntity sales = departmentRepository.findByCodeIgnoreCase("SALES").orElseThrow();
        UserEntity hrManager = userRepository.findByEmailIgnoreCase("manager.hr@documind.ai").orElseThrow();
        String sharedHash = "a".repeat(64);

        DocumentEntity hrDoc = new DocumentEntity();
        hrDoc.setDepartment(hr);
        hrDoc.setUploadedBy(hrManager);
        hrDoc.setOriginalName("hr-policy.pdf");
        hrDoc.setStorageKey("hr/hr-policy.pdf");
        hrDoc.setFileExtension("pdf");
        hrDoc.setMimeType("application/pdf");
        hrDoc.setFileSizeBytes(2048L);
        hrDoc.setFileHash(sharedHash);
        hrDoc.setAccessLevel(AccessLevel.DEPARTMENT);
        hrDoc.setStatus(DocumentStatus.PENDING);

        DocumentEntity savedHrDoc = documentRepository.saveAndFlush(hrDoc);
        assertThat(savedHrDoc.getId()).isNotNull();
        assertThat(savedHrDoc.getCurrentVersion()).isEqualTo(1);
        assertThat(savedHrDoc.getChunkCount()).isZero();
        assertThat(documentRepository.existsByDepartmentIdAndFileHash(hr.getId(), sharedHash)).isTrue();
        assertThat(documentRepository.findByDepartmentIdOrderByCreatedAtDesc(hr.getId()))
            .extracting(DocumentEntity::getId)
            .containsExactly(savedHrDoc.getId());

        DocumentEntity salesDoc = new DocumentEntity();
        salesDoc.setDepartment(sales);
        salesDoc.setUploadedBy(hrManager);
        salesDoc.setOriginalName("sales-copy.pdf");
        salesDoc.setStorageKey("sales/sales-copy.pdf");
        salesDoc.setFileExtension("pdf");
        salesDoc.setMimeType("application/pdf");
        salesDoc.setFileSizeBytes(2048L);
        salesDoc.setFileHash(sharedHash);
        salesDoc.setAccessLevel(AccessLevel.PUBLIC);
        salesDoc.setStatus(DocumentStatus.COMPLETED);
        salesDoc.setChunkCount(4);

        DocumentEntity savedSalesDoc = documentRepository.saveAndFlush(salesDoc);
        assertThat(savedSalesDoc.getId()).isNotNull();
        assertThat(documentRepository.existsByDepartmentIdAndFileHash(sales.getId(), sharedHash)).isTrue();

        DocumentEntity duplicateHrDoc = new DocumentEntity();
        duplicateHrDoc.setDepartment(hr);
        duplicateHrDoc.setUploadedBy(hrManager);
        duplicateHrDoc.setOriginalName("hr-policy-duplicate.pdf");
        duplicateHrDoc.setStorageKey("hr/hr-policy-duplicate.pdf");
        duplicateHrDoc.setFileExtension("pdf");
        duplicateHrDoc.setMimeType("application/pdf");
        duplicateHrDoc.setFileSizeBytes(2048L);
        duplicateHrDoc.setFileHash(sharedHash);
        duplicateHrDoc.setAccessLevel(AccessLevel.CONFIDENTIAL);
        duplicateHrDoc.setStatus(DocumentStatus.PENDING);

        assertThatThrownBy(() -> documentRepository.saveAndFlush(duplicateHrDoc))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chatMessagePersistsAndReloadsVietnameseJsonbCitationsAndSessionOrdering() {
        UserEntity employee = userRepository.findByEmailIgnoreCase("employee.sales@documind.ai").orElseThrow();
        OffsetDateTime baseTime = OffsetDateTime.of(2026, 11, 15, 9, 0, 0, 0, ZoneOffset.UTC);

        ChatSessionEntity olderSession = new ChatSessionEntity();
        olderSession.setUser(employee);
        olderSession.setTitle("Chính sách bán hàng Q3");
        olderSession.setCreatedAt(baseTime);
        olderSession.setUpdatedAt(baseTime);
        olderSession = chatSessionRepository.saveAndFlush(olderSession);

        ChatSessionEntity newerSession = new ChatSessionEntity();
        newerSession.setUser(employee);
        newerSession.setTitle("Quy định nghỉ phép năm 2026");
        newerSession.setCreatedAt(baseTime.plusSeconds(1));
        newerSession.setUpdatedAt(baseTime.plusSeconds(10));
        newerSession = chatSessionRepository.saveAndFlush(newerSession);

        assertThat(chatSessionRepository.findByIdAndUserId(newerSession.getId(), employee.getId())).isPresent();
        assertThat(chatSessionRepository.findByIdAndUserId(newerSession.getId(), UUID.randomUUID())).isEmpty();
        assertThat(chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(employee.getId()))
            .extracting(ChatSessionEntity::getId)
            .containsExactly(newerSession.getId(), olderSession.getId());

        UUID citedDocId = UUID.randomUUID();
        String vietnameseExcerpt = "Nhân viên chính thức được hưởng 12 ngày nghỉ phép năm có nguyên lương.";
        List<CitationPayload> expectedCitations = List.of(
            new CitationPayload(citedDocId, "so-tay-nhan-su.pdf", 12, 0.9245d, vietnameseExcerpt)
        );

        for (int i = 0; i < 6; i++) {
            ChatMessageEntity msg = new ChatMessageEntity();
            msg.setSession(newerSession);
            msg.setRole(i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
            msg.setContent("Tin nhắn thứ " + i);
            msg.setCreatedAt(baseTime.plusSeconds(i));
            chatMessageRepository.save(msg);
        }

        ChatMessageEntity citedAnswer = new ChatMessageEntity();
        citedAnswer.setSession(newerSession);
        citedAnswer.setRole(MessageRole.ASSISTANT);
        citedAnswer.setContent("Theo sổ tay nhân sự, bạn có 12 ngày phép năm.");
        citedAnswer.setRewrittenQuery("quy định số ngày nghỉ phép năm nhân viên chính thức");
        citedAnswer.setCitations(expectedCitations);
        citedAnswer.setAbstained(false);
        citedAnswer.setCacheTier(CacheTier.L2);
        citedAnswer.setPromptTokens(320);
        citedAnswer.setCompletionTokens(48);
        citedAnswer.setLatencyMs(145L);
        citedAnswer.setModelProvider("groq");
        citedAnswer.setCreatedAt(baseTime.plusSeconds(10));

        ChatMessageEntity savedCited = chatMessageRepository.saveAndFlush(citedAnswer);
        entityManager.clear();

        ChatMessageEntity reloaded = chatMessageRepository.findById(savedCited.getId()).orElseThrow();
        assertThat(reloaded.getCitations()).containsExactlyElementsOf(expectedCitations);
        assertThat(reloaded.getCitations().getFirst().excerpt()).isEqualTo(vietnameseExcerpt);
        assertThat(reloaded.getCacheTier()).isEqualTo(CacheTier.L2);
        assertThat(reloaded.getLatencyMs()).isEqualTo(145L);

        List<ChatMessageEntity> ascMessages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(newerSession.getId());
        assertThat(ascMessages).hasSize(7);
        assertThat(ascMessages.getFirst().getContent()).isEqualTo("Tin nhắn thứ 0");
        assertThat(ascMessages.getLast().getId()).isEqualTo(savedCited.getId());

        List<ChatMessageEntity> top6Desc = chatMessageRepository.findTop6BySessionIdOrderByCreatedAtDesc(newerSession.getId());
        assertThat(top6Desc).hasSize(6);
        assertThat(top6Desc.getFirst().getId()).isEqualTo(savedCited.getId());
        ChatMessageEntity oldestInWindow = top6Desc.getLast();
        assertThat(oldestInWindow.getContent()).isEqualTo("Tin nhắn thứ 1");
        assertThat(oldestInWindow.getCitations()).isEmpty();
        assertThat(oldestInWindow.isAbstained()).isFalse();
        assertThat(oldestInWindow.getCacheTier()).isEqualTo(CacheTier.NONE);
        assertThat(oldestInWindow.getPromptTokens()).isZero();
        assertThat(oldestInWindow.getCompletionTokens()).isZero();
        assertThat(oldestInWindow.getLatencyMs()).isNull();
    }

    @Test
    void refreshTokenAndAuditLogRepositoriesPersistAndQueryCleanly() {
        UserEntity admin = userRepository.findByEmailIgnoreCase("admin@documind.ai").orElseThrow();
        DepartmentEntity itDept = admin.getDepartment();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        RefreshTokenEntity activeToken = new RefreshTokenEntity();
        activeToken.setUser(admin);
        activeToken.setTokenHash("b".repeat(64));
        activeToken.setExpiresAt(now.plusDays(7));
        activeToken.setUserAgent("DocuMind-Test/1.0");
        activeToken.setIpAddress("127.0.0.1");
        refreshTokenRepository.saveAndFlush(activeToken);

        assertThat(refreshTokenRepository.findByTokenHash("b".repeat(64))).isPresent();

        int revokedCount = refreshTokenRepository.revokeAllActiveByUserId(admin.getId(), now);
        assertThat(revokedCount).isEqualTo(1);
        assertThat(refreshTokenRepository.findByTokenHash("b".repeat(64)).orElseThrow().getRevokedAt())
            .isNotNull();

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setUser(admin);
        auditLog.setDepartment(itDept);
        auditLog.setActionType(AuditActionType.CHAT_QUERY);
        auditLog.setQueryText("Quy trình cấp quyền VPN?");
        auditLog.setProvider("groq");
        auditLog.setIpAddress("10.0.0.12");
        auditLogRepository.saveAndFlush(auditLog);

        Page<AuditLogEntity> byDept = auditLogRepository.findByDepartmentIdOrderByCreatedAtDesc(
            itDept.getId(), PageRequest.of(0, 10));
        assertThat(byDept.getContent()).hasSize(1);
        assertThat(byDept.getContent().getFirst().getStatusCode()).isEqualTo(200);
        assertThat(byDept.getContent().getFirst().getCacheTier()).isEqualTo(CacheTier.NONE);

        Page<AuditLogEntity> byAction = auditLogRepository.findByActionTypeOrderByCreatedAtDesc(
            AuditActionType.CHAT_QUERY, PageRequest.of(0, 10));
        assertThat(byAction.getContent()).hasSize(1);
        assertThat(byAction.getContent().getFirst().getQueryText()).isEqualTo("Quy trình cấp quyền VPN?");
    }
}
