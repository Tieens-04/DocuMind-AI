package com.documind.service.ingestion;

import com.documind.domain.enums.AccessLevel;
import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SmartChunkingServiceTest {

    // Intentionally pinned to the pre-DA-11 800/150 mathematical window so legacy boundary/overlap
    // unit assertions remain deterministic; active 512/100 production defaults are verified below in
    // calibratedDefault512By100WindowPreservesVietnameseOverlapAndBounds().
    private final IngestionProperties properties = new IngestionProperties(
        IngestionProperties.ExtractionProperties.defaults(),
        new IngestionProperties.ChunkingProperties(800, 150, IngestionProperties.DEFAULT_MAX_CHUNKS_PER_DOCUMENT)
    );
    private final OverlappingTokenTextSplitter splitter = new OverlappingTokenTextSplitter(
        properties.chunking().chunkSize(),
        properties.chunking().chunkOverlap(),
        properties.chunking().maxChunksPerDocument()
    );
    private final SmartChunkingService chunkingService = new SmartChunkingService(properties, splitter);

    @Test
    void slidingWindow800By150PreservesVietnameseWordsZeroReplacementCharsAndCleanOverlap() {
        StringBuilder policyBuilder = new StringBuilder();
        for (int i = 1; i <= 23; i++) {
            policyBuilder.append("Điều khoản ").append(i)
                .append(": Nhân viên phòng Kỹ thuật phải bảo mật tuyệt đối dữ liệu nội bộ, mã nguồn và tài liệu thiết kế hệ thống trí tuệ nhân tạo doanh nghiệp theo quy định hiện hành.\n\n");
        }
        String policyText = policyBuilder.toString().trim();
        int totalTokens = splitter.countTokens(policyText);
        assertThat(totalTokens).isBetween(1_700, 2_300);

        DocumentIngestionContext ctx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/it/doc/v1/chinh_sach_bao_mat.txt",
            "Chính sách bảo mật.txt",
            "txt",
            AccessLevel.DEPARTMENT,
            1
        );

        List<Document> chunks = chunkingService.chunk(
            ctx,
            List.of(new ExtractedSegment(policyText, null, null))
        );

        assertThat(chunks).hasSize(3);
        for (Document chunk : chunks) {
            String text = chunk.getText();
            assertThat(text).isNotBlank();
            assertThat(text).doesNotContain("\uFFFD");
            assertThat(splitter.countTokens(text)).isLessThanOrEqualTo(800);
        }

        for (int i = 0; i < chunks.size() - 1; i++) {
            String previous = chunks.get(i).getText();
            String next = chunks.get(i + 1).getText();
            String overlapText = findSuffixPrefixOverlap(previous, next);
            assertThat(overlapText).isNotBlank();
            assertThat(splitter.countTokens(overlapText)).isBetween(50, 160);
            assertThat(previous).endsWith(overlapText);
            assertThat(next).startsWith(overlapText);
        }
    }

    @Test
    void unbrokenGiantAsciiAndVietnameseStringsTerminatePromptlyWithoutReplacementChars() {
        String giantAscii = "a1b2c3d4e5".repeat(450);
        assertThat(splitter.countTokens(giantAscii)).isGreaterThan(1_600);

        List<String> asciiChunks = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () -> splitter.splitTextWithBudget(giantAscii, 800, 150)
        );
        assertThat(asciiChunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(asciiChunks).allSatisfy(chunk -> {
            assertThat(chunk).doesNotContain("\uFFFD");
            assertThat(splitter.countTokens(chunk)).isLessThanOrEqualTo(800);
        });

        String giantVietnamese = "ệữắộ".repeat(800);
        List<String> vietChunks = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () -> splitter.splitTextWithBudget(giantVietnamese, 800, 150)
        );
        assertThat(vietChunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(vietChunks).allSatisfy(chunk -> {
            assertThat(chunk).doesNotContain("\uFFFD");
            assertThat(splitter.countTokens(chunk)).isLessThanOrEqualTo(800);
        });
    }

    @Test
    void contextualHeaderPrefixIsInjectedWithin800TokenCeilingAndNotDuplicatedWhenAlreadyPresent() {
        String heading = "Chương 3: Phúc lợi > Điều 7: Nghỉ thai sản";
        StringBuilder sectionBody = new StringBuilder();
        for (int i = 1; i <= 45; i++) {
            sectionBody.append("Quy định chi tiết mục ").append(i)
                .append(": Thời gian hưởng chế độ thai sản tối đa sáu tháng được tính theo quy định của Bộ luật Lao động và chính sách nhân sự nội bộ.\n\n");
        }

        DocumentIngestionContext ctx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/hr/doc/v1/phuc_loi.md",
            "Phúc lợi nhân sự.md",
            "md",
            AccessLevel.PUBLIC,
            1
        );

        List<Document> chunks = chunkingService.chunk(
            ctx,
            List.of(new ExtractedSegment(sectionBody.toString().trim(), null, heading))
        );

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getText()).startsWith(heading + "\n\n");
            assertThat(splitter.countTokens(chunk.getText())).isLessThanOrEqualTo(800);
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.SECTION_HEADING)).isEqualTo(heading);
            assertThat(chunk.getMetadata()).doesNotContainKey(ChunkMetadataKeys.PAGE_NUMBER);
        });

        String alreadyPrefixedBody = heading + "\n\nNội dung ngắn gọn ngay dưới tiêu đề đã được giữ lại.";
        List<Document> noDuplicateChunks = chunkingService.chunk(
            ctx,
            List.of(new ExtractedSegment(alreadyPrefixedBody, null, heading))
        );
        assertThat(noDuplicateChunks).hasSize(1);
        assertThat(noDuplicateChunks.getFirst().getText()).isEqualTo(alreadyPrefixedBody);

        String oversizedHeading = "Chương đặc biệt rất dài ".repeat(30).trim();
        List<Document> cappedPrefixChunks = chunkingService.chunk(
            ctx,
            List.of(new ExtractedSegment(sectionBody.toString().trim(), null, oversizedHeading))
        );
        assertThat(cappedPrefixChunks).isNotEmpty();
        assertThat(cappedPrefixChunks).allSatisfy(chunk -> {
            assertThat(splitter.countTokens(chunk.getText())).isLessThanOrEqualTo(800);
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.SECTION_HEADING)).isEqualTo(oversizedHeading);
        });
    }

    @Test
    void strictPageIsolationGlobalChunkIndexMetadataAndDeterministicIdsAreEnforced() {
        String page1Text = ("Trang một trình bày tổng quan hệ thống tra cứu tài liệu nội bộ DocuMind AI. ").repeat(6).trim();
        assertThat(splitter.countTokens(page1Text)).isBetween(100, 300);

        StringBuilder page3Builder = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            page3Builder.append("Phụ lục trang ba điều ").append(i)
                .append(": Tất cả truy vấn tìm kiếm vector đều phải gắn bộ lọc phân quyền theo phòng ban từ SecurityContext.\n");
        }
        String page3Text = page3Builder.toString().trim();
        assertThat(splitter.countTokens(page3Text)).isBetween(850, 1_300);

        UUID docId = UUID.fromString("11111111-aaaa-bbbb-cccc-222222222222");
        UUID deptId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String originalName = "Sổ tay Nhân sự #2026.pdf";
        DocumentIngestionContext pdfCtx = new DocumentIngestionContext(
            docId,
            deptId,
            "departments/" + deptId + "/upload/v2/so_tay.pdf",
            originalName,
            "pdf",
            AccessLevel.CONFIDENTIAL,
            2
        );

        List<Document> chunks = chunkingService.chunk(
            pdfCtx,
            List.of(
                new ExtractedSegment(page1Text, 1, null),
                new ExtractedSegment(page3Text, 3, null)
            )
        );

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getMetadata().get(ChunkMetadataKeys.PAGE_NUMBER)).isEqualTo(1);
        assertThat(chunks.get(1).getMetadata().get(ChunkMetadataKeys.PAGE_NUMBER)).isEqualTo(3);
        assertThat(chunks.get(2).getMetadata().get(ChunkMetadataKeys.PAGE_NUMBER)).isEqualTo(3);

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            assertThat(chunk.getId()).isEqualTo(ChunkIdGenerator.generate(docId, 2, i).toString());
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.DOCUMENT_ID)).isEqualTo(docId.toString());
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.DEPARTMENT_ID)).isEqualTo(deptId.toString());
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.FILE_NAME)).isEqualTo(originalName);
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.ACCESS_LEVEL)).isEqualTo("CONFIDENTIAL");
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.DOC_VERSION)).isEqualTo(2);
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.CHUNK_INDEX)).isEqualTo(i);
            assertThat(chunk.getMetadata()).doesNotContainKey(ChunkMetadataKeys.SECTION_HEADING);
        }
    }

    @Test
    void enforcesMaxChunksPerDocumentAndEmptySegmentsGuards() {
        DocumentIngestionContext ctx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/it/doc/v1/notes.txt",
            "notes.txt",
            "txt",
            AccessLevel.PUBLIC,
            1
        );

        assertThatThrownBy(() -> chunkingService.chunk(ctx, List.of()))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EMPTY_DOCUMENT);

        IngestionProperties tightChunkCap = new IngestionProperties(
            IngestionProperties.ExtractionProperties.defaults(),
            new IngestionProperties.ChunkingProperties(64, 16, 2)
        );
        SmartChunkingService cappedService = new SmartChunkingService(tightChunkCap);
        String longText = "Quy trình sao lưu dữ liệu hàng ngày bắt buộc áp dụng cho mọi máy chủ nội bộ. ".repeat(15);

        assertThatThrownBy(() -> cappedService.chunk(ctx, List.of(new ExtractedSegment(longText, null, null))))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);
    }

    @Test
    void enforcesMinimumChunkSize32CeilingEvenWithSectionHeadingPrefix() {
        IngestionProperties minChunkProps = new IngestionProperties(
            IngestionProperties.ExtractionProperties.defaults(),
            new IngestionProperties.ChunkingProperties(32, 8, 500)
        );
        SmartChunkingService minChunkService = new SmartChunkingService(minChunkProps);
        OverlappingTokenTextSplitter checkSplitter = new OverlappingTokenTextSplitter(32, 8, 500);

        DocumentIngestionContext ctx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/it/doc/v1/small_chunks.md",
            "Small chunks.md",
            "md",
            AccessLevel.DEPARTMENT,
            1
        );
        String heading = "Chương 1: Quy định bảo mật thông tin và kiểm soát truy cập hệ thống";
        String body = ("Nhân viên kỹ thuật phải tuân thủ nguyên tắc phân quyền tối thiểu khi truy cập dữ liệu nội bộ và báo cáo sự cố ngay lập tức.\n\n").repeat(6).trim();

        List<Document> chunks = minChunkService.chunk(ctx, List.of(new ExtractedSegment(body, null, heading)));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(checkSplitter.countTokens(chunk.getText())).isLessThanOrEqualTo(32);
            assertThat(chunk.getText()).doesNotContain("\uFFFD");
            assertThat(chunk.getMetadata().get(ChunkMetadataKeys.SECTION_HEADING)).isEqualTo(heading);
        });
    }

    @Test
    void prefixBoundaryCheckAndOnePassBudgetAvoidsFalseSkipAndStepDownLoop() {
        DocumentIngestionContext ctx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/hr/doc/v1/dieu_khoan.txt",
            "Điều khoản.txt",
            "txt",
            AccessLevel.PUBLIC,
            1
        );

        List<Document> prefixSimilarityChunks = chunkingService.chunk(
            ctx,
            List.of(new ExtractedSegment("Điều 10 quy định trách nhiệm bảo mật hệ thống.", null, "Điều 1"))
        );
        assertThat(prefixSimilarityChunks).hasSize(1);
        assertThat(prefixSimilarityChunks.getFirst().getText())
            .isEqualTo("Điều 1\n\nĐiều 10 quy định trách nhiệm bảo mật hệ thống.");

        List<Document> lineBoundaryChunks = chunkingService.chunk(
            ctx,
            List.of(new ExtractedSegment("Điều 1\nNội dung điều một.", null, "Điều 1"))
        );
        assertThat(lineBoundaryChunks).hasSize(1);
        assertThat(lineBoundaryChunks.getFirst().getText()).isEqualTo("Điều 1\nNội dung điều một.");

        AtomicInteger splitCallCount = new AtomicInteger(0);
        OverlappingTokenTextSplitter countingSplitter = new OverlappingTokenTextSplitter(800, 150, 10_000) {
            @Override
            public List<String> splitTextWithBudget(String text, int effectiveChunkSize, int effectiveOverlap) {
                splitCallCount.incrementAndGet();
                return super.splitTextWithBudget(text, effectiveChunkSize, effectiveOverlap);
            }
        };
        SmartChunkingService countingService = new SmartChunkingService(properties, countingSplitter);

        StringBuilder longSection = new StringBuilder();
        for (int i = 1; i <= 75; i++) {
            longSection.append("Điều khoản chi tiết số ").append(i)
                .append(": Mọi truy cập vào kho tri thức doanh nghiệp đều phải đi qua lớp lọc phân quyền theo phòng ban và được ghi log kiểm toán đầy đủ.\n\n");
        }
        String threeThousandTokenBody = longSection.toString().trim();
        assertThat(splitter.countTokens(threeThousandTokenBody)).isGreaterThan(2_500);

        List<Document> longChunks = countingService.chunk(
            ctx,
            List.of(new ExtractedSegment(threeThousandTokenBody, null, "Chương 2 > Điều 4: Kiểm soát truy cập"))
        );
        assertThat(longChunks).hasSizeGreaterThanOrEqualTo(4);
        assertThat(splitCallCount.get()).isLessThanOrEqualTo(2);
        assertThat(longChunks).allSatisfy(chunk ->
            assertThat(splitter.countTokens(chunk.getText())).isLessThanOrEqualTo(800)
        );
    }

    @Test
    void calibratedDefault512By100WindowPreservesVietnameseOverlapAndBounds() {
        IngestionProperties defaults = IngestionProperties.defaults();
        assertThat(defaults.chunking().chunkSize()).isEqualTo(512);
        assertThat(defaults.chunking().chunkOverlap()).isEqualTo(100);

        OverlappingTokenTextSplitter defaultSplitter = new OverlappingTokenTextSplitter();
        SmartChunkingService defaultService = new SmartChunkingService(defaults, defaultSplitter);

        StringBuilder policyBuilder = new StringBuilder();
        for (int i = 1; i <= 18; i++) {
            policyBuilder.append("Điều khoản ").append(i)
                .append(": Nhân viên chính thức tuân thủ quy định bảo mật dữ liệu nội bộ và chính sách nghỉ phép năm của doanh nghiệp.\n\n");
        }
        String policyText = policyBuilder.toString().trim();
        assertThat(defaultSplitter.countTokens(policyText)).isBetween(900, 1_400);

        DocumentIngestionContext ctx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/hr/doc/v1/chinh_sach_512.txt",
            "Chính sách 512.txt",
            "txt",
            AccessLevel.DEPARTMENT,
            1
        );

        List<Document> chunks = defaultService.chunk(
            ctx,
            List.of(new ExtractedSegment(policyText, null, null))
        );

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getText()).isNotBlank().doesNotContain("\uFFFD");
            assertThat(defaultSplitter.countTokens(chunk.getText())).isLessThanOrEqualTo(512);
        });

        for (int i = 0; i < chunks.size() - 1; i++) {
            String overlapText = findSuffixPrefixOverlap(chunks.get(i).getText(), chunks.get(i + 1).getText());
            assertThat(overlapText).isNotBlank();
            assertThat(defaultSplitter.countTokens(overlapText)).isBetween(30, 120);
        }
    }

    private static String findSuffixPrefixOverlap(String previous, String next) {
        int maxLen = Math.min(previous.length(), next.length());
        for (int len = maxLen; len >= 1; len--) {
            if (previous.regionMatches(previous.length() - len, next, 0, len)) {
                return next.substring(0, len);
            }
        }
        return "";
    }
}
