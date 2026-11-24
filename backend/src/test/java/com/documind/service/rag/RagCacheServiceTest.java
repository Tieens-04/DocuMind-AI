package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.enums.Role;
import com.documind.domain.model.CitationPayload;
import com.documind.infrastructure.security.DocumentPreviewTokenService;
import com.documind.infrastructure.vector.SemanticCacheEntry;
import com.documind.infrastructure.vector.SemanticCacheVectorRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.service.document.CallerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagCacheServiceTest {

    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ADMIN_USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DOC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant FIXED_NOW = Instant.parse("2026-11-23T15:00:00Z");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private DepartmentRepository departmentRepository;
    private DocumentRepository documentRepository;
    private SemanticCacheVectorRepository semanticCacheRepository;
    private EmbeddingModel embeddingModel;
    private DocumentPreviewTokenService previewTokenService;
    private ObjectMapper objectMapper;
    private RagProperties ragProperties;
    private Clock fixedClock;
    private RagCacheService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        departmentRepository = mock(DepartmentRepository.class);
        documentRepository = mock(DocumentRepository.class);
        semanticCacheRepository = mock(SemanticCacheVectorRepository.class);
        embeddingModel = mock(EmbeddingModel.class);
        previewTokenService = mock(DocumentPreviewTokenService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ragProperties = RagProperties.defaults();
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

        service = new RagCacheService(
            redisTemplate,
            departmentRepository,
            documentRepository,
            semanticCacheRepository,
            embeddingModel,
            previewTokenService,
            objectMapper,
            ragProperties,
            fixedClock
        );
    }

    @Test
    void normalizationPreservesVietnameseDiacriticsNumbersNegationsAndIsolatesMultiTurnContext() {
        String nfc = "  Chính   sách\n\tnghỉ\u00A0phép 14 ngày?  ";
        String nfd = Normalizer.normalize("chính sách nghỉ phép 14 ngày?", Normalizer.Form.NFD);
        assertThat(RagCacheService.normalizeQueryForCache(nfc))
            .isEqualTo("chính sách nghỉ phép 14 ngày?")
            .isEqualTo(RagCacheService.normalizeQueryForCache(nfd));
        assertThat(RagCacheService.sha256Hex(RagCacheService.normalizeQueryForCache(nfc)))
            .hasSize(64)
            .isEqualTo(RagCacheService.sha256Hex(RagCacheService.normalizeQueryForCache(nfd)));

        assertThat(RagCacheService.normalizeQueryForCache("Không được nghỉ 14 ngày"))
            .isNotEqualTo(RagCacheService.normalizeQueryForCache("Được nghỉ 14 ngày"));
        assertThat(RagCacheService.normalizeQueryForCache("Nghỉ phép 14 ngày"))
            .isNotEqualTo(RagCacheService.normalizeQueryForCache("Nghỉ phép 15 ngày"));
        assertThat(RagCacheService.normalizeQueryForCache("Nghỉ phép năm"))
            .isNotEqualTo(RagCacheService.normalizeQueryForCache("Nghi phep nam"));

        List<ChatMessageEntity> leaveHistory = List.of(
            historyMsg(MessageRole.USER, "Chính sách nghỉ phép năm?", false),
            historyMsg(MessageRole.ASSISTANT, "Nhân viên chính thức hưởng 14 ngày phép.", false)
        );
        List<ChatMessageEntity> probationHistory = List.of(
            historyMsg(MessageRole.USER, "Thời gian thử việc tối đa?", false),
            historyMsg(MessageRole.ASSISTANT, "Thời gian thử việc tối đa 60 ngày.", false)
        );

        String hashLeave = service.computeContextualQueryHash("Còn trường hợp đặc biệt thì sao?", leaveHistory);
        String hashLeaveRepeat = service.computeContextualQueryHash("  còn   trường hợp đặc biệt thì sao? ", leaveHistory);
        String hashProbation = service.computeContextualQueryHash("Còn trường hợp đặc biệt thì sao?", probationHistory);

        assertThat(hashLeave).hasSize(64).isEqualTo(hashLeaveRepeat);
        assertThat(hashLeave).isNotEqualTo(hashProbation);
    }

    @Test
    void cachePropertiesRejectInvalidTtlsAndSemanticThreshold() {
        assertThatThrownBy(() -> new RagProperties.CacheProperties(
            true, Duration.ZERO, Duration.ofHours(24), 0.94d, Duration.ofHours(24)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.CacheProperties(
            true, Duration.ofMinutes(30), Duration.ofDays(31), 0.94d, Duration.ofHours(24)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.CacheProperties(
            true, Duration.ofMinutes(30), Duration.ofHours(24), 0.49d, Duration.ofHours(24)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.CacheProperties(
            true, Duration.ofMinutes(30), Duration.ofHours(24), Double.NaN, Duration.ofHours(24)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void l1HitSkipsEmbeddingAndL2RehydratesFreshPreviewUrlsAndHealsMissingRedisVersionMirror() throws Exception {
        RbacVectorScope scope = employeeScope(HR_DEPT_ID);
        String question = "Nhân viên chính thức được nghỉ phép năm bao nhiêu ngày?";
        String queryHash = RagCacheService.hashStandaloneQuery(question);
        String l1Key = RagCacheService.buildStandaloneL1Key(scope.cacheScopeKey(), 3L, queryHash);
        String versionKey = RagCacheService.buildDepartmentVersionKey(HR_DEPT_ID);

        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(3L));
        String serializedPayload = objectMapper.writeValueAsString(new RagCacheService.CachedResponsePayload(
            "Nhân viên chính thức được hưởng 14 ngày phép năm [Nguồn: hr-leave.pdf - Trang 2].",
            null,
            List.of(new CitationPayload(DOC_ID, 1, "hr-leave.pdf", 2, "Nghỉ phép", 0.91d, "14 ngày phép năm.")),
            RagConstants.PROVIDER_GROQ
        ));
        when(valueOps.multiGet(List.of(versionKey, l1Key))).thenReturn(Arrays.asList(null, serializedPayload));

        DocumentEntity liveDoc = document(DOC_ID, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, "hr-leave.pdf", "pdf", 1);
        when(documentRepository.findAllById(anyIterable())).thenReturn(List.of(liveDoc));
        when(previewTokenService.issuePreviewUrl(DOC_ID, 1, scope.caller()))
            .thenReturn(new DocumentPreviewTokenService.IssuedPreviewUrl(
                "http://localhost:8081/api/v1/documents/" + DOC_ID + "/content?token=fresh-tok",
                "fresh-tok",
                OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(900), ZoneOffset.UTC)
            ));

        RagCacheService.CacheLookupResult result = service.lookup(scope, question, List.of());

        assertThat(result.isHit()).isTrue();
        assertThat(result.cacheTier()).isEqualTo(CacheTier.L1);
        assertThat(result.capturedVersion()).isEqualTo(3L);
        assertThat(result.answer()).contains("14 ngày phép năm");
        assertThat(result.modelProvider()).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().getFirst().presignedUrl())
            .isEqualTo("http://localhost:8081/api/v1/documents/" + DOC_ID + "/content?token=fresh-tok#page=2");

        verifyNoInteractions(embeddingModel, semanticCacheRepository);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(versionKey)), anyString(), eq("3"));
    }

    @Test
    void l1HitFailsClosedWhenCitedDocumentRenamedReclassifiedOrVersionSuperseded() throws Exception {
        RbacVectorScope scope = employeeScope(HR_DEPT_ID);
        String question = "Chính sách nghỉ phép năm?";
        String queryHash = RagCacheService.hashStandaloneQuery(question);
        String l1Key = RagCacheService.buildStandaloneL1Key(scope.cacheScopeKey(), 2L, queryHash);
        String versionKey = RagCacheService.buildDepartmentVersionKey(HR_DEPT_ID);

        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(2L));
        String serializedPayload = objectMapper.writeValueAsString(new RagCacheService.CachedResponsePayload(
            "14 ngày phép năm.",
            null,
            List.of(new CitationPayload(DOC_ID, 1, "hr-leave-v1.pdf", 2, null, 0.90d, "14 ngày.")),
            RagConstants.PROVIDER_GROQ
        ));
        when(valueOps.multiGet(List.of(versionKey, l1Key))).thenReturn(List.of("2", serializedPayload));

        // Document was reclassified to CONFIDENTIAL (forbidden to STANDARD_EMPLOYEE)
        DocumentEntity confidentialDoc = document(DOC_ID, HR_DEPT_ID, AccessLevel.CONFIDENTIAL, DocumentStatus.COMPLETED, "hr-leave-v1.pdf", "pdf", 1);
        when(documentRepository.findAllById(anyIterable())).thenReturn(List.of(confidentialDoc));
        when(embeddingModel.embed(question)).thenReturn(unitVector());
        when(semanticCacheRepository.findBestMatchWithoutIncrement(anyString(), anyLong(), any(), anyDouble())).thenReturn(Optional.empty());

        RagCacheService.CacheLookupResult result = service.lookup(scope, question, List.of());

        assertThat(result.isHit()).isFalse();
        assertThat(result.cacheTier()).isEqualTo(CacheTier.NONE);
        verifyNoInteractions(previewTokenService);
    }

    @Test
    void l2HitPromotesToL1BoundedByRemainingL2ExpiryAndFallsBackGracefullyOnRedisError() {
        RbacVectorScope scope = employeeScope(HR_DEPT_ID);
        String question = "Quy định số ngày nghỉ phép hàng năm cho nhân viên?";
        String queryHash = RagCacheService.hashStandaloneQuery(question);
        String l1Key = RagCacheService.buildStandaloneL1Key(scope.cacheScopeKey(), 1L, queryHash);
        float[] vec = unitVector();

        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(1L));
        when(valueOps.multiGet(anyList())).thenThrow(new QueryTimeoutException("Redis timed out"));
        when(embeddingModel.embed(question)).thenReturn(vec);

        // L2 entry expires in 5 minutes (< 30m default L1 TTL)
        OffsetDateTime l2ExpiresAt = OffsetDateTime.ofInstant(FIXED_NOW.plus(Duration.ofMinutes(5)), ZoneOffset.UTC);
        SemanticCacheEntry l2Entry = new SemanticCacheEntry(
            UUID.randomUUID(),
            scope.cacheScopeKey(),
            1L,
            "a".repeat(64),
            "Nhân viên chính thức được nghỉ phép năm bao nhiêu ngày?",
            "Nhân viên chính thức hưởng 14 ngày phép năm.",
            List.of(new CitationPayload(DOC_ID, 1, "hr-leave.pdf", 2, null, 0.92d, "14 ngày phép năm.")),
            RagConstants.PROVIDER_GROQ,
            0.96d,
            l2ExpiresAt
        );
        when(semanticCacheRepository.findBestMatchWithoutIncrement(scope.cacheScopeKey(), 1L, vec, 0.94d))
            .thenReturn(Optional.of(l2Entry));

        DocumentEntity liveDoc = document(DOC_ID, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, "hr-leave.pdf", "pdf", 1);
        when(documentRepository.findAllById(anyIterable())).thenReturn(List.of(liveDoc));
        when(previewTokenService.issuePreviewUrl(DOC_ID, 1, scope.caller()))
            .thenReturn(new DocumentPreviewTokenService.IssuedPreviewUrl(
                "http://localhost:8081/api/v1/documents/" + DOC_ID + "/content?token=l2-tok",
                "l2-tok",
                OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(900), ZoneOffset.UTC)
            ));

        RagCacheService.CacheLookupResult result = service.lookup(scope, question, List.of());

        assertThat(result.isHit()).isTrue();
        assertThat(result.cacheTier()).isEqualTo(CacheTier.L2);
        assertThat(result.answer()).isEqualTo("Nhân viên chính thức hưởng 14 ngày phép năm.");
        assertThat(result.citations()).hasSize(1);
        verify(semanticCacheRepository).recordHit(l2Entry.id());

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(l1Key), anyString(), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void l2HitIsRejectedWithoutPromotionWhenScopeVersionAdvancesDuringEmbeddingOrEntryAlreadyExpired() {
        RbacVectorScope scope = employeeScope(HR_DEPT_ID);
        String question = "Quy định số ngày nghỉ phép hàng năm?";
        float[] vec = unitVector();

        // First read sees v1, post-L2 re-check sees v2 (concurrent doc update committed during embedding)
        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(1L), Optional.of(2L));
        when(valueOps.multiGet(anyList())).thenReturn(Arrays.asList("1", null));
        when(embeddingModel.embed(question)).thenReturn(vec);

        SemanticCacheEntry l2Entry = new SemanticCacheEntry(
            UUID.randomUUID(),
            scope.cacheScopeKey(),
            1L,
            "b".repeat(64),
            "Câu hỏi gốc",
            "Nội dung cũ v1",
            List.of(new CitationPayload(DOC_ID, 1, "hr-leave.pdf", 1, null, 0.95d, "14 ngày.")),
            RagConstants.PROVIDER_GROQ,
            0.97d,
            OffsetDateTime.ofInstant(FIXED_NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC)
        );
        when(semanticCacheRepository.findBestMatchWithoutIncrement(scope.cacheScopeKey(), 1L, vec, 0.94d))
            .thenReturn(Optional.of(l2Entry));

        RagCacheService.CacheLookupResult staleResult = service.lookup(scope, question, List.of());

        assertThat(staleResult.isHit()).isFalse();
        assertThat(staleResult.cacheTier()).isEqualTo(CacheTier.NONE);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(semanticCacheRepository, never()).recordHit(any());
        verifyNoInteractions(previewTokenService);

        // Expired L2 entry (expiresAt <= now) and sub-millisecond remaining (< 1ms -> EX 0 risk) also rejected
        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(2L), Optional.of(2L), Optional.of(2L), Optional.of(2L));
        when(valueOps.multiGet(anyList())).thenReturn(Arrays.asList("2", null));
        SemanticCacheEntry expiredEntry = new SemanticCacheEntry(
            UUID.randomUUID(),
            scope.cacheScopeKey(),
            2L,
            "c".repeat(64),
            "Câu hỏi gốc",
            "Nội dung hết hạn",
            List.of(new CitationPayload(DOC_ID, 1, "hr-leave.pdf", 1, null, 0.95d, "14 ngày.")),
            RagConstants.PROVIDER_GROQ,
            0.97d,
            OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(1), ZoneOffset.UTC)
        );
        when(semanticCacheRepository.findBestMatchWithoutIncrement(scope.cacheScopeKey(), 2L, vec, 0.94d))
            .thenReturn(Optional.of(expiredEntry));

        RagCacheService.CacheLookupResult expiredResult = service.lookup(scope, question, List.of());
        assertThat(expiredResult.isHit()).isFalse();

        SemanticCacheEntry subMillisecondEntry = new SemanticCacheEntry(
            UUID.randomUUID(),
            scope.cacheScopeKey(),
            2L,
            "d".repeat(64),
            "Câu hỏi gốc",
            "Nội dung sắp hết hạn",
            List.of(new CitationPayload(DOC_ID, 1, "hr-leave.pdf", 1, null, 0.95d, "14 ngày.")),
            RagConstants.PROVIDER_GROQ,
            0.97d,
            OffsetDateTime.ofInstant(FIXED_NOW.plusNanos(400_000L), ZoneOffset.UTC)
        );
        when(semanticCacheRepository.findBestMatchWithoutIncrement(scope.cacheScopeKey(), 2L, vec, 0.94d))
            .thenReturn(Optional.of(subMillisecondEntry));

        RagCacheService.CacheLookupResult subMsResult = service.lookup(scope, question, List.of());
        assertThat(subMsResult.isHit()).isFalse();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(semanticCacheRepository, never()).recordHit(any());
    }

    @Test
    void publishIfCurrentSkipsStaleVersionAbstainedAndEmptyCitationsAndIsolatesContextualTurnsFromL2() {
        RbacVectorScope scope = employeeScope(HR_DEPT_ID);
        String question = "Chính sách nghỉ phép năm?";
        CitationDto citation = new CitationDto(DOC_ID, 1, "hr-leave.pdf", 2, null, 0.91d, "14 ngày.", "http://preview");

        // 1. Stale version (captured 1, live DB is 2) -> skips BOTH L1 and L2!
        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(2L));
        service.publishIfCurrent(
            scope, 1L, question, null, List.of(), unitVector(),
            "Câu trả lời cũ", List.of(citation), false, RagConstants.PROVIDER_GROQ
        );
        verifyNoInteractions(semanticCacheRepository);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));

        // 2. Abstained or empty citations -> never cached!
        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(2L));
        service.publishIfCurrent(
            scope, 2L, question, null, List.of(), unitVector(),
            RagConstants.SAFE_REFUSAL_MESSAGE, List.of(), true, RagConstants.PROVIDER_GROQ
        );
        service.publishIfCurrent(
            scope, 2L, question, null, List.of(), unitVector(),
            "Không có nguồn", List.of(), false, RagConstants.PROVIDER_GROQ
        );
        verifyNoInteractions(semanticCacheRepository);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));

        // 3. Contextual follow-up (!history.isEmpty()) writes ONLY to isolated ctx L1 key, NEVER L2!
        List<ChatMessageEntity> history = List.of(
            historyMsg(MessageRole.USER, "Nghỉ phép năm?", false),
            historyMsg(MessageRole.ASSISTANT, "14 ngày phép.", false)
        );
        service.publishIfCurrent(
            scope, 2L, "Làm đủ 5 năm thì sao?", "Làm đủ 5 năm cộng mấy ngày phép?",
            history, null, "Cộng thêm 1 ngày phép.", List.of(citation), false, RagConstants.PROVIDER_GROQ
        );
        verifyNoInteractions(semanticCacheRepository);
        ArgumentCaptor<String> ctxKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(ctxKeyCaptor.capture(), anyString(), eq(ragProperties.cache().l1Ttl()));
        assertThat(ctxKeyCaptor.getValue()).startsWith(RagCacheService.L1_CONTEXT_KEY_PREFIX);
    }

    @Test
    void postCommitVersionSyncRunsOnlyOnCommitAndSkipsOnRollbackAndGlobalAdminUsesSumCacheVersions() {
        // Verify ADMIN:GLOBAL reads sumCacheVersions()
        RbacVectorScope globalAdmin = globalAdminScope();
        when(departmentRepository.sumCacheVersions()).thenReturn(7L);
        assertThat(service.readAuthoritativeVersion(globalAdmin)).isEqualTo(7L);

        // Verify post-commit sync skips on rollback and executes on commit
        RecordingTransactionManager txManager = new RecordingTransactionManager();
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        // Rollback case
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            service.schedulePostCommitSyncForDepartments(Set.of(HR_DEPT_ID));
            throw new IllegalStateException("Simulated rollback");
        })).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(redisTemplate);

        // Commit case executes a single batched multi-key Lua roundtrip
        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(4L));
        when(departmentRepository.sumCacheVersions()).thenReturn(9L);
        txTemplate.executeWithoutResult(status -> service.schedulePostCommitSyncForDepartments(Set.of(HR_DEPT_ID)));

        verify(redisTemplate, times(1)).execute(
            any(RedisScript.class),
            eq(List.of(RagCacheService.buildDepartmentVersionKey(HR_DEPT_ID), RagCacheService.GLOBAL_VERSION_KEY)),
            anyString(),
            eq("4"),
            eq("9")
        );
    }

    @Test
    void lookupL1ReconcilesStalePreloadedVersionWhenRedisMirrorAdvancesAndPublishIfCurrentSwallowsDbError() throws Exception {
        RbacVectorScope scope = employeeScope(HR_DEPT_ID);
        String question = "Chính sách bảo hiểm sức khỏe?";
        String queryHash = RagCacheService.hashStandaloneQuery(question);
        String versionKey = RagCacheService.buildDepartmentVersionKey(HR_DEPT_ID);
        String staleV1Key = RagCacheService.buildStandaloneL1Key(scope.cacheScopeKey(), 1L, queryHash);
        String freshV2Key = RagCacheService.buildStandaloneL1Key(scope.cacheScopeKey(), 2L, queryHash);

        String staleV1Json = objectMapper.writeValueAsString(new RagCacheService.CachedResponsePayload(
            "Câu trả lời cũ v1.",
            null,
            List.of(new CitationPayload(DOC_ID, 1, "hr-health.pdf", 1, null, 0.90d, "Cũ.")),
            RagConstants.PROVIDER_GROQ
        ));
        String freshV2Json = objectMapper.writeValueAsString(new RagCacheService.CachedResponsePayload(
            "Câu trả lời mới v2.",
            null,
            List.of(new CitationPayload(DOC_ID, 2, "hr-health.pdf", 1, null, 0.92d, "Mới.")),
            RagConstants.PROVIDER_GROQ
        ));

        when(valueOps.multiGet(List.of(versionKey, staleV1Key))).thenReturn(List.of("2", staleV1Json));
        when(departmentRepository.findCacheVersionById(HR_DEPT_ID)).thenReturn(Optional.of(2L));
        when(valueOps.multiGet(List.of(versionKey, freshV2Key))).thenReturn(List.of("2", freshV2Json));

        DocumentEntity v2Doc = document(DOC_ID, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, "hr-health.pdf", "pdf", 2);
        when(documentRepository.findAllById(anyIterable())).thenReturn(List.of(v2Doc));
        when(previewTokenService.issuePreviewUrl(DOC_ID, 2, scope.caller()))
            .thenReturn(new DocumentPreviewTokenService.IssuedPreviewUrl(
                "http://localhost:8081/api/v1/documents/" + DOC_ID + "/content?token=v2-tok",
                "v2-tok",
                OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(900), ZoneOffset.UTC)
            ));

        RagCacheService.L1LookupResult reconciled = service.lookupL1(scope, question, List.of(), 1L);
        assertThat(reconciled.isHit()).isTrue();
        assertThat(reconciled.capturedVersion()).isEqualTo(2L);
        assertThat(reconciled.hitResult().answer()).isEqualTo("Câu trả lời mới v2.");

        // Best-effort publishIfCurrent catches transient DB failures without aborting completed turn
        when(departmentRepository.findCacheVersionById(HR_DEPT_ID))
            .thenThrow(new DataAccessResourceFailureException("Simulated transient DB failure"));
        CitationDto citation = new CitationDto(DOC_ID, 2, "hr-health.pdf", 1, null, 0.92d, "Mới.", "http://preview");
        assertThatCode(() -> service.publishIfCurrent(
            scope, 2L, question, null, List.of(), unitVector(),
            "Câu trả lời mới v2.", List.of(citation), false, RagConstants.PROVIDER_GROQ
        )).doesNotThrowAnyException();
    }

    private static RbacVectorScope employeeScope(UUID deptId) {
        CallerContext caller = new CallerContext(HR_USER_ID, deptId, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        return new RbacVectorScope(
            caller,
            null,
            RbacVectorFilterFactory.buildFilterExpression(caller, null),
            RbacVectorFilterFactory.buildCacheScopeKey(caller, null)
        );
    }

    private static RbacVectorScope globalAdminScope() {
        CallerContext caller = new CallerContext(ADMIN_USER_ID, IT_DEPT_ID, Role.SYSTEM_ADMIN, "127.0.0.1");
        return new RbacVectorScope(
            caller,
            null,
            RbacVectorFilterFactory.buildFilterExpression(caller, null),
            RbacVectorFilterFactory.buildCacheScopeKey(caller, null)
        );
    }

    private static DocumentEntity document(
        UUID id,
        UUID deptId,
        AccessLevel accessLevel,
        DocumentStatus status,
        String originalName,
        String ext,
        int version
    ) {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(deptId);
        dept.setCode("HR");
        dept.setName("Human Resources");

        DocumentEntity doc = new DocumentEntity();
        doc.setId(id);
        doc.setDepartment(dept);
        doc.setAccessLevel(accessLevel);
        doc.setStatus(status);
        doc.setOriginalName(originalName);
        doc.setFileExtension(ext);
        doc.setCurrentVersion(version);
        return doc;
    }

    private static ChatMessageEntity historyMsg(MessageRole role, String content, boolean abstained) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId(UUID.randomUUID());
        msg.setRole(role);
        msg.setContent(content);
        msg.setAbstained(abstained);
        return msg;
    }

    private static float[] unitVector() {
        float[] vec = new float[1024];
        vec[0] = 1.0f;
        return vec;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
