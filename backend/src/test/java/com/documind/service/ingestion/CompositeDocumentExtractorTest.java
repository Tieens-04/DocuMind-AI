package com.documind.service.ingestion;

import com.documind.domain.enums.AccessLevel;
import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import com.documind.exception.DocumentStorageException;
import com.documind.infrastructure.storage.MinioStorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompositeDocumentExtractorTest {

    @TempDir
    Path tempDir;

    private MinioStorageService minioStorageService;
    private IngestionProperties properties;
    private PdfDocumentExtractor pdfExtractor;
    private DocxDocumentExtractor docxExtractor;
    private CompositeDocumentExtractor compositeExtractor;

    @BeforeEach
    void setUp() {
        minioStorageService = mock(MinioStorageService.class);
        properties = IngestionProperties.defaults();
        PlainTextSectionExtractor plainTextExtractor = new PlainTextSectionExtractor(properties);
        MarkdownSectionExtractor markdownExtractor = new MarkdownSectionExtractor(properties);
        pdfExtractor = new PdfDocumentExtractor(properties);
        docxExtractor = new DocxDocumentExtractor(properties, plainTextExtractor);
        compositeExtractor = new CompositeDocumentExtractor(
            minioStorageService,
            properties,
            pdfExtractor,
            docxExtractor,
            markdownExtractor,
            plainTextExtractor,
            tempDir
        );
    }

    @Test
    void pdfExtractionPreservesVietnameseDiacriticsExactPageNumbersAndSkipsBlankPages() throws IOException {
        byte[] pdfBytes = createVietnameseMultiPagePdf(
            List.of(
                "Quy chế nghỉ phép năm: 12 ngày có hưởng lương.",
                "",
                "Phụ lục chế độ bảo hiểm xã hội bắt buộc."
            )
        );

        List<ExtractedSegment> segments = compositeExtractor.extractFromStream(
            "so_tay.pdf",
            "pdf",
            new ByteArrayInputStream(pdfBytes)
        );

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).pageNumber()).isEqualTo(1);
        assertThat(segments.get(0).sectionHeading()).isNull();
        assertThat(segments.get(0).content()).contains("Quy chế nghỉ phép năm: 12 ngày có hưởng lương.");

        assertThat(segments.get(1).pageNumber()).isEqualTo(3);
        assertThat(segments.get(1).sectionHeading()).isNull();
        assertThat(segments.get(1).content()).contains("Phụ lục chế độ bảo hiểm xã hội bắt buộc.");

        assertTempDirIsClean();
    }

    @Test
    void pdfTier2AndTier3FallbacksPreservePageNumbersWithoutOcr() throws IOException {
        byte[] pdfBytes = createVietnameseMultiPagePdf(
            List.of(
                "Trang một: Quy định bảo mật thông tin.",
                "   ",
                "Trang ba: Quy trình xử lý sự cố."
            )
        );
        Path pdfFile = tempDir.resolve("fallback-sample.pdf");
        Files.write(pdfFile, pdfBytes);

        PdfDocumentExtractor throwingTier1Extractor = new PdfDocumentExtractor(
            properties,
            path -> {
                throw new IllegalStateException("Simulated Tier 1 crop failure");
            }
        );
        List<ExtractedSegment> tier2Segments = throwingTier1Extractor.extract(pdfFile, "fallback.pdf");
        assertThat(tier2Segments).hasSize(2);
        assertThat(tier2Segments.get(0).pageNumber()).isEqualTo(1);
        assertThat(tier2Segments.get(0).content()).contains("Trang một: Quy định bảo mật thông tin.");
        assertThat(tier2Segments.get(1).pageNumber()).isEqualTo(3);
        assertThat(tier2Segments.get(1).content()).contains("Trang ba: Quy trình xử lý sự cố.");

        PdfDocumentExtractor emptyTier1Extractor = new PdfDocumentExtractor(properties, path -> Map.of());
        List<ExtractedSegment> emptyRescued = emptyTier1Extractor.extract(pdfFile, "fallback.pdf");
        assertThat(emptyRescued).extracting(ExtractedSegment::pageNumber).containsExactly(1, 3);

        Map<Integer, String> tier3Pages = pdfExtractor.extractViaTikaPageSax(pdfFile, "fallback.pdf");
        assertThat(tier3Pages.keySet()).containsExactly(1, 3);
        assertThat(tier3Pages.get(1)).contains("Trang một");
        assertThat(tier3Pages.get(3)).contains("Trang ba");
    }

    @Test
    void pdfRejectsScannedImageOnlyAndEncryptedDocumentsAndLeavesZeroTempFiles() throws IOException {
        byte[] scannedPdf = createImageOnlyPdf();
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "scan.pdf",
            "pdf",
            new ByteArrayInputStream(scannedPdf)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.NO_EXTRACTABLE_TEXT);

        byte[] encryptedPdf = createEncryptedPdf("Mật khẩu bảo mật");
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "secret.pdf",
            "pdf",
            new ByteArrayInputStream(encryptedPdf)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT);

        assertTempDirIsClean();
    }

    @Test
    void docxExtractsHierarchicalBreadcrumbsPipeTablesUnstyledFallbackAndRejectsZipBomb() throws IOException {
        byte[] styledDocx = createStyledDocxWithTable();
        List<ExtractedSegment> styledSegments = compositeExtractor.extractFromStream(
            "chinh_sach.docx",
            "docx",
            new ByteArrayInputStream(styledDocx)
        );

        assertThat(styledSegments).hasSize(2);
        assertThat(styledSegments).allSatisfy(s -> assertThat(s.pageNumber()).isNull());
        assertThat(styledSegments.get(0).sectionHeading()).isEqualTo("Chương 1: Nhân sự");
        assertThat(styledSegments.get(0).content()).contains("Tổng quan chính sách nhân sự toàn công ty.");

        assertThat(styledSegments.get(1).sectionHeading()).isEqualTo("Chương 1: Nhân sự > Điều 2: Phụ cấp");
        assertThat(styledSegments.get(1).content())
            .contains("Phụ cấp ăn trưa 1.000.000 VNĐ/tháng.")
            .contains("| Loại | Mức hưởng |")
            .contains("| Ăn trưa | 1.000.000 |");

        byte[] unstyledDocx = createUnstyledDocx(
            "CHƯƠNG I: VẬN HÀNH",
            "Nội dung quy trình vận hành hệ thống nội bộ."
        );
        List<ExtractedSegment> unstyledSegments = compositeExtractor.extractFromStream(
            "unstyled.docx",
            "docx",
            new ByteArrayInputStream(unstyledDocx)
        );
        assertThat(unstyledSegments).hasSize(1);
        assertThat(unstyledSegments.getFirst().sectionHeading()).isEqualTo("CHƯƠNG I: VẬN HÀNH");
        assertThat(unstyledSegments.getFirst().content()).contains("Nội dung quy trình vận hành hệ thống nội bộ.");

        byte[] zipBombDocx = createHighlyCompressibleDocx(300_000, false);
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "bomb.docx",
            "docx",
            new ByteArrayInputStream(zipBombDocx)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);

        byte[] styledZipBombDocx = createHighlyCompressibleDocx(300_000, true);
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "styled-bomb.docx",
            "docx",
            new ByteArrayInputStream(styledZipBombDocx)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);

        IngestionProperties tightCharCap = new IngestionProperties(
            new IngestionProperties.ExtractionProperties(52_428_800L, 100, 0.01d, 104_857_600L, 1_000L),
            IngestionProperties.ChunkingProperties.defaults()
        );
        DocxDocumentExtractor charCappedDocx = new DocxDocumentExtractor(
            tightCharCap,
            new PlainTextSectionExtractor(tightCharCap)
        );
        Path styledTemp = Files.write(tempDir.resolve("char-cap.docx"), styledDocx);
        assertThatThrownBy(() -> charCappedDocx.extract(styledTemp, "chinh_sach.docx"))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);
        Files.deleteIfExists(styledTemp);

        assertTempDirIsClean();
    }

    @Test
    void compositeExtractsFromMinioClosesStreamsPropagatesStorageErrorsAndRejectsUnsupportedFormats() throws IOException {
        byte[] pdfBytes = createVietnameseMultiPagePdf(List.of("Tài liệu từ MinIO PDF."));
        byte[] docxBytes = createStyledDocxWithTable();
        byte[] mdBytes = "# Kiến trúc\n\nNội dung Markdown tiếng Việt.".getBytes(StandardCharsets.UTF_8);
        byte[] txtBytes = "CHƯƠNG I: NỘI QUY\n\nNội dung TXT tiếng Việt.".getBytes(StandardCharsets.UTF_8);

        assertMinioRoundTrip("doc.pdf", "pdf", pdfBytes, 1, null);
        assertMinioRoundTrip("doc.docx", "docx", docxBytes, null, "Chương 1: Nhân sự");
        assertMinioRoundTrip("doc.md", "md", mdBytes, null, "Kiến trúc");
        assertMinioRoundTrip("doc.txt", "txt", txtBytes, null, "CHƯƠNG I: NỘI QUY");

        DocumentIngestionContext unsupportedCtx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/1/upload/v1/sheet.xlsx",
            "sheet.xlsx",
            "xlsx",
            AccessLevel.DEPARTMENT,
            1
        );
        assertThatThrownBy(() -> compositeExtractor.extract(unsupportedCtx))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.UNSUPPORTED_FORMAT);

        DocumentIngestionContext missingMinioCtx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/1/upload/v1/missing.pdf",
            "missing.pdf",
            "pdf",
            AccessLevel.PUBLIC,
            1
        );
        when(minioStorageService.openStream(missingMinioCtx.storageKey()))
            .thenThrow(new DocumentStorageException("MinIO down", new RuntimeException("connection refused")));
        assertThatThrownBy(() -> compositeExtractor.extract(missingMinioCtx))
            .isInstanceOf(DocumentStorageException.class);

        assertTempDirIsClean();
    }

    @Test
    void tier3PdfMaxExtractedCharsEncryptedDocxTableCellsErrorsSpoolCapAndMidStreamS3FailuresAreHandledCorrectly()
        throws Exception {
        IngestionProperties tightCharCap = new IngestionProperties(
            new IngestionProperties.ExtractionProperties(52_428_800L, 100, 0.01d, 104_857_600L, 1_000L),
            IngestionProperties.ChunkingProperties.defaults()
        );

        // 1. Tier-3 PDF maxExtractedChars breach (Critical #1: must throw EXTRACTION_LIMIT_EXCEEDED, not CORRUPT_DOCUMENT)
        byte[] largeMultiPagePdf = createVietnameseMultiPagePdf(List.of(
            "Trang một chứa nội dung dài hơn năm mươi ký tự để kiểm tra giới hạn ký tự ở tầng ba.",
            "Trang hai chứa thêm bảy mươi ký tự vượt quá ngưỡng một trăm ký tự ngay trong quá trình đọc SAX.",
            "Trang ba bổ sung thêm văn bản nữa."
        ));
        Path tier3PdfFile = Files.write(tempDir.resolve("tier3-cap.pdf"), largeMultiPagePdf);
        PdfDocumentExtractor tier3CappedPdf = new PdfDocumentExtractor(tightCharCap);
        assertThatThrownBy(() -> tier3CappedPdf.extractViaTikaPageSax(tier3PdfFile, "tier3-cap.pdf"))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);
        Files.deleteIfExists(tier3PdfFile);

        // 2. Unstyled DOCX maxExtractedChars breach & table preservation (Important #3)
        byte[] unstyledLargeDocx = createUnstyledDocxWithTable(
            "CHƯƠNG I: BẢNG BIỂU",
            "Đoạn nội dung khá dài nhằm kiểm tra giới hạn ký tự trên văn bản Word không dùng Heading style.",
            "Tiêu chí",
            "Giá trị"
        );
        List<ExtractedSegment> unstyledWithTable = compositeExtractor.extractFromStream(
            "unstyled-table.docx",
            "docx",
            new ByteArrayInputStream(unstyledLargeDocx)
        );
        assertThat(unstyledWithTable).hasSize(1);
        assertThat(unstyledWithTable.getFirst().sectionHeading()).isEqualTo("CHƯƠNG I: BẢNG BIỂU");
        assertThat(unstyledWithTable.getFirst().content()).contains("| Tiêu chí | Giá trị |");

        DocxDocumentExtractor tightCharDocx = new DocxDocumentExtractor(
            tightCharCap,
            new PlainTextSectionExtractor(tightCharCap)
        );
        Path unstyledCapPath = Files.write(tempDir.resolve("unstyled-cap.docx"), unstyledLargeDocx);
        assertThatThrownBy(() -> tightCharDocx.extract(unstyledCapPath, "unstyled-cap.docx"))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);
        Files.deleteIfExists(unstyledCapPath);

        // 3. Multi-paragraph & heading-styled table cell in DOCX (Important #8)
        byte[] complexTableDocx = createDocxWithComplexTableCells();
        List<ExtractedSegment> complexSegments = compositeExtractor.extractFromStream(
            "complex-table.docx",
            "docx",
            new ByteArrayInputStream(complexTableDocx)
        );
        assertThat(complexSegments).hasSize(1);
        assertThat(complexSegments.getFirst().sectionHeading()).isEqualTo("Chương 2: Biểu mẫu");
        assertThat(complexSegments.getFirst().content())
            .contains("| Hàng 1 Hàng 2 | Tiêu đề trong ô |");

        // 4. Encrypted DOCX (Important #9)
        byte[] encryptedDocx = createEncryptedDocx("Mật khẩu nội bộ!");
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "secret.docx",
            "docx",
            new ByteArrayInputStream(encryptedDocx)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT);

        // 5. Fatal JVM Error must not be swallowed as CORRUPT_DOCUMENT (Critical #2)
        PlainTextSectionExtractor jvmErrorExtractor = new PlainTextSectionExtractor(properties) {
            @Override
            public List<ExtractedSegment> extractFromText(String rawText, String originalName) {
                throw new AssertionError("Simulated JVM error");
            }
        };
        DocxDocumentExtractor nonSwallowingDocx = new DocxDocumentExtractor(properties, jvmErrorExtractor);
        byte[] simpleUnstyled = createUnstyledDocx("CHƯƠNG I: TEST", "Nội dung ngắn.");
        Path errPath = Files.write(tempDir.resolve("jvm-error.docx"), simpleUnstyled);
        assertThatThrownBy(() -> nonSwallowingDocx.extract(errPath, "jvm-error.docx"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Simulated JVM error");
        Files.deleteIfExists(errPath);

        // 6. maxSpoolBytes breach on binary stream (Important #9)
        IngestionProperties tinySpoolCap = new IngestionProperties(
            new IngestionProperties.ExtractionProperties(128L, 5_000_000, 0.01d, 104_857_600L, 1_000L),
            IngestionProperties.ChunkingProperties.defaults()
        );
        CompositeDocumentExtractor tinySpoolComposite = new CompositeDocumentExtractor(
            minioStorageService,
            tinySpoolCap,
            new PdfDocumentExtractor(tinySpoolCap),
            new DocxDocumentExtractor(tinySpoolCap, new PlainTextSectionExtractor(tinySpoolCap)),
            new MarkdownSectionExtractor(tinySpoolCap),
            new PlainTextSectionExtractor(tinySpoolCap),
            tempDir
        );
        byte[] samplePdf = createVietnameseMultiPagePdf(List.of("PDF nhỏ nhưng lớn hơn 128 bytes."));
        assertThatThrownBy(() -> tinySpoolComposite.extractFromStream(
            "oversized.pdf",
            "pdf",
            new ByteArrayInputStream(samplePdf)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);

        // 7. Mid-stream S3 IOException surfaces DocumentStorageException, not CORRUPT_DOCUMENT (Important #7)
        UUID midFailDocId = UUID.randomUUID();
        UUID midFailDeptId = UUID.randomUUID();
        String midFailKey = "departments/" + midFailDeptId + "/" + midFailDocId + "/v1/drop.pdf";
        InputStream failingStream = new InputStream() {
            private int count;

            @Override
            public int read() throws IOException {
                if (count++ < 32) {
                    return '%';
                }
                throw new IOException("Simulated MinIO TCP connection reset mid-stream");
            }
        };
        ResponseInputStream<GetObjectResponse> failingS3Stream = new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(failingStream)
        );
        when(minioStorageService.openStream(midFailKey)).thenReturn(failingS3Stream);
        DocumentIngestionContext midFailCtx = new DocumentIngestionContext(
            midFailDocId,
            midFailDeptId,
            midFailKey,
            "drop.pdf",
            "pdf",
            AccessLevel.PUBLIC,
            1
        );
        assertThatThrownBy(() -> compositeExtractor.extract(midFailCtx))
            .isInstanceOf(DocumentStorageException.class);

        assertTempDirIsClean();
    }

    @Test
    void docxPreservesUnstyledNumberedListsNestedTablesAndRejectsSpoofedOrCorruptContainers() throws Exception {
        byte[] unstyledListDocx = createUnstyledDocxParagraphs(List.of(
            "Quy trình nghỉ phép:",
            "",
            "1. Nộp đơn",
            "2. Quản lý duyệt",
            "3. Nhân sự lưu hồ sơ",
            "",
            "Hết."
        ));
        List<ExtractedSegment> listSegments = compositeExtractor.extractFromStream(
            "nghi_phep_unstyled.docx",
            "docx",
            new ByteArrayInputStream(unstyledListDocx)
        );
        assertThat(listSegments).hasSize(1);
        assertThat(listSegments.getFirst().sectionHeading()).isNull();
        assertThat(listSegments.getFirst().content())
            .contains("Quy trình nghỉ phép:")
            .contains("1. Nộp đơn")
            .contains("2. Quản lý duyệt")
            .contains("3. Nhân sự lưu hồ sơ")
            .contains("Hết.");

        byte[] nestedTableDocx = createDocxWithNestedTable();
        List<ExtractedSegment> nestedSegments = compositeExtractor.extractFromStream(
            "nested_table.docx",
            "docx",
            new ByteArrayInputStream(nestedTableDocx)
        );
        assertThat(nestedSegments).hasSize(1);
        assertThat(nestedSegments.getFirst().sectionHeading()).isEqualTo("Chương 1: Bảng lồng nhau");
        assertThat(nestedSegments.getFirst().content())
            .contains("Ngoài trước")
            .contains("Trong A - Trong B")
            .contains("Cột ngoài 2")
            .contains("|");

        byte[] rtfPayload = "{\\rtf1\\ansi Nội dung RTF giả mạo}".getBytes(StandardCharsets.UTF_8);
        byte[] htmlPayload = "<html><body><p>Hello HTML</p></body></html>".getBytes(StandardCharsets.UTF_8);
        byte[] pdfPayload = createVietnameseMultiPagePdf(List.of("PDF disguised as docx."));
        byte[] ole2LegacyDoc = createUnencryptedOle2Doc();
        byte[] xlsxZip = createXlsxZipWithoutWordMainPart();

        for (byte[] nonDocx : List.of(rtfPayload, htmlPayload, pdfPayload, ole2LegacyDoc, xlsxZip)) {
            assertThatThrownBy(() -> compositeExtractor.extractFromStream(
                "spoofed.docx",
                "docx",
                new ByteArrayInputStream(nonDocx)
            ))
                .isInstanceOf(DocumentExtractionException.class)
                .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
                .isEqualTo(DocumentExtractionErrorCode.UNSUPPORTED_FORMAT);
        }

        byte[] randomCorrupt = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "corrupt.docx",
            "docx",
            new ByteArrayInputStream(randomCorrupt)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.CORRUPT_DOCUMENT);

        assertTempDirIsClean();
    }

    @Test
    void minioStreamClosesBeforeBinaryParsingIgnoresPostReadCloseGlitchesAndTranslatesTempFileErrors()
        throws Exception {
        byte[] pdfBytes = createVietnameseMultiPagePdf(List.of("Nội dung kiểm tra đóng stream trước khi parse."));
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        String key = "departments/" + deptId + "/" + docId + "/v1/stream-lifecycle.pdf";

        AtomicBoolean streamClosed = new AtomicBoolean(false);
        AtomicBoolean closedBeforeParse = new AtomicBoolean(false);
        InputStream closeTrackingPayload = new FilterInputStream(new ByteArrayInputStream(pdfBytes)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
                throw new IOException("Simulated post-read close glitch");
            }
        };
        ResponseInputStream<GetObjectResponse> s3PdfStream = new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(closeTrackingPayload)
        );
        when(minioStorageService.openStream(key)).thenReturn(s3PdfStream);

        PdfDocumentExtractor checkingPdfExtractor = new PdfDocumentExtractor(properties) {
            @Override
            public List<ExtractedSegment> extract(Path pdfTempFile, String originalName) {
                closedBeforeParse.set(streamClosed.get());
                return super.extract(pdfTempFile, originalName);
            }
        };
        CompositeDocumentExtractor checkingComposite = new CompositeDocumentExtractor(
            minioStorageService,
            properties,
            checkingPdfExtractor,
            docxExtractor,
            new MarkdownSectionExtractor(properties),
            new PlainTextSectionExtractor(properties),
            tempDir
        );

        List<ExtractedSegment> pdfSegments = checkingComposite.extract(new DocumentIngestionContext(
            docId, deptId, key, "stream-lifecycle.pdf", "pdf", AccessLevel.PUBLIC, 1
        ));
        assertThat(closedBeforeParse).isTrue();
        assertThat(streamClosed).isTrue();
        assertThat(pdfSegments).hasSize(1);
        assertThat(pdfSegments.getFirst().content()).contains("Nội dung kiểm tra đóng stream trước khi parse.");

        String mdKey = "departments/" + deptId + "/" + docId + "/v1/glitch.md";
        byte[] mdPayload = "# Tiêu đề\n\nNội dung markdown vẫn trả về bình thường.".getBytes(StandardCharsets.UTF_8);
        InputStream closeFailingMdStream = new FilterInputStream(new ByteArrayInputStream(mdPayload)) {
            @Override
            public void close() throws IOException {
                super.close();
                throw new IOException("Simulated MD close glitch");
            }
        };
        when(minioStorageService.openStream(mdKey)).thenReturn(new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(closeFailingMdStream)
        ));
        List<ExtractedSegment> mdSegments = checkingComposite.extract(new DocumentIngestionContext(
            docId, deptId, mdKey, "glitch.md", "md", AccessLevel.PUBLIC, 1
        ));
        assertThat(mdSegments).hasSize(1);
        assertThat(mdSegments.getFirst().content()).contains("Nội dung markdown vẫn trả về bình thường.");

        Path notADirectory = Files.writeString(tempDir.resolve("not-a-dir.tmp"), "blocker");
        CompositeDocumentExtractor brokenTempComposite = new CompositeDocumentExtractor(
            minioStorageService,
            properties,
            pdfExtractor,
            docxExtractor,
            new MarkdownSectionExtractor(properties),
            new PlainTextSectionExtractor(properties),
            notADirectory
        );
        assertThatThrownBy(() -> brokenTempComposite.extractFromStream(
            "fail-temp.pdf",
            "pdf",
            new ByteArrayInputStream(pdfBytes)
        ))
            .isInstanceOf(DocumentStorageException.class);
        Files.deleteIfExists(notADirectory);

        assertTempDirIsClean();
    }

    @Test
    void pdfTier2IsolatesPerPageFailuresEnforcesPerPageCharBudgetAndCapsConcurrency() throws Exception {
        byte[] threePagePdf = createVietnameseMultiPagePdf(List.of(
            "Trang một ngắn.",
            "Trang hai ngắn.",
            "Trang ba được khôi phục an toàn."
        ));
        Path threePageFile = Files.write(tempDir.resolve("tier2-per-page.pdf"), threePagePdf);

        PdfDocumentExtractor partialStripperFailureExtractor = new PdfDocumentExtractor(
            properties,
            path -> Map.of(),
            PdfDocumentExtractor.DEFAULT_MAX_CONCURRENT_EXTRACTIONS,
            () -> (doc, pageNum) -> {
                if (pageNum == 1) {
                    throw new IOException("Simulated I/O failure on page 1");
                }
                if (pageNum == 2) {
                    throw new IllegalArgumentException("Simulated malformed font stream on page 2");
                }
                return "Trang ba được khôi phục an toàn.";
            }
        );
        List<ExtractedSegment> rescuedOnlyPage3 = partialStripperFailureExtractor.extract(
            threePageFile,
            "tier2-per-page.pdf"
        );
        assertThat(rescuedOnlyPage3).hasSize(1);
        assertThat(rescuedOnlyPage3.getFirst().pageNumber()).isEqualTo(3);
        assertThat(rescuedOnlyPage3.getFirst().content()).contains("Trang ba được khôi phục an toàn.");

        IngestionProperties tinyCharBudget = new IngestionProperties(
            new IngestionProperties.ExtractionProperties(52_428_800L, 25, 0.01d, 104_857_600L, 1_000L),
            IngestionProperties.ChunkingProperties.defaults()
        );
        PdfDocumentExtractor tier1CapExtractor = new PdfDocumentExtractor(
            tinyCharBudget,
            path -> Map.of(
                1, "Nội dung trang một đã vượt quá giới hạn hai mươi lăm ký tự.",
                2, "Trang hai"
            )
        );
        assertThatThrownBy(() -> tier1CapExtractor.extract(threePageFile, "tier1-cap.pdf"))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);

        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        PdfDocumentExtractor concurrencyCapped = new PdfDocumentExtractor(
            properties,
            path -> {
                int active = inFlight.incrementAndGet();
                maxObserved.accumulateAndGet(active, Math::max);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return Map.of(1, "Trang 1", 2, "Trang 2", 3, "Trang 3");
            },
            2
        );

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(1);
            List<Future<List<ExtractedSegment>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    ready.await(5, TimeUnit.SECONDS);
                    return concurrencyCapped.extract(threePageFile, "concurrent.pdf");
                }));
            }
            ready.countDown();
            for (Future<List<ExtractedSegment>> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).hasSize(3);
            }
        }
        assertThat(maxObserved.get()).isLessThanOrEqualTo(2).isGreaterThanOrEqualTo(1);

        Files.deleteIfExists(threePageFile);
        assertTempDirIsClean();
    }

    private void assertMinioRoundTrip(
        String name,
        String ext,
        byte[] payload,
        Integer expectedFirstPage,
        String expectedFirstHeading
    ) {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        String key = "departments/" + deptId + "/" + docId + "/v1/" + name;
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        InputStream tracked = new FilterInputStream(new ByteArrayInputStream(payload)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };
        ResponseInputStream<GetObjectResponse> s3Stream = new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(tracked)
        );
        when(minioStorageService.openStream(key)).thenReturn(s3Stream);

        DocumentIngestionContext ctx = new DocumentIngestionContext(
            docId, deptId, key, name, ext, AccessLevel.DEPARTMENT, 1
        );
        List<ExtractedSegment> segments = compositeExtractor.extract(ctx);

        assertThat(streamClosed).isTrue();
        assertThat(segments).isNotEmpty();
        assertThat(segments.getFirst().pageNumber()).isEqualTo(expectedFirstPage);
        assertThat(segments.getFirst().sectionHeading()).isEqualTo(expectedFirstHeading);
        verify(minioStorageService).openStream(key);
    }

    private void assertTempDirIsClean() throws IOException {
        try (Stream<Path> entries = Files.list(tempDir)) {
            List<Path> leftovers = entries
                .filter(path -> path.getFileName().toString().startsWith("documind-extract-"))
                .toList();
            assertThat(leftovers).isEmpty();
        }
    }

    private static byte[] createVietnameseMultiPagePdf(List<String> pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDType0Font font = loadBundledUnicodeFont(doc);
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (pageText != null && !pageText.isBlank()) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(font, 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(pageText);
                        cs.endText();
                    }
                }
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createImageOnlyPdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            PDImageXObject xObject = LosslessFactory.createFromImage(doc, image);
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(xObject, 50, 650, 64, 64);
                }
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createEncryptedPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDType0Font font = loadBundledUnicodeFont(doc);
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.protect(new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission()));
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static PDType0Font loadBundledUnicodeFont(PDDocument doc) throws IOException {
        try (InputStream fontStream = Objects.requireNonNull(
            PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"),
            "Bundled LiberationSans-Regular.ttf not found"
        )) {
            return PDType0Font.load(doc, fontStream);
        }
    }

    private static byte[] createStyledDocxWithTable() throws IOException {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>Chương 1: Nhân sự</w:t></w:r>
                </w:p>
                <w:p><w:r><w:t>Tổng quan chính sách nhân sự toàn công ty.</w:t></w:r></w:p>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                  <w:r><w:t>Điều 2: Phụ cấp</w:t></w:r>
                </w:p>
                <w:p><w:r><w:t>Phụ cấp ăn trưa 1.000.000 VNĐ/tháng.</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Loại</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Mức hưởng</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Ăn trưa</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>1.000.000</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
            """;
        return createDocxPackage(documentXml, true);
    }

    private static byte[] createUnstyledDocx(String headingLine, String bodyLine) throws IOException {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                <w:p><w:r><w:t></w:t></w:r></w:p>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """.formatted(headingLine, bodyLine);
        return createDocxPackage(documentXml, false);
    }

    private static byte[] createHighlyCompressibleDocx(int repeatedChars, boolean includeStyles) throws IOException {
        String headingBlock = includeStyles
            ? "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Chương 1</w:t></w:r></w:p>"
            : "";
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body>"
            + headingBlock
            + "<w:p><w:r><w:t>"
            + "A".repeat(repeatedChars)
            + "</w:t></w:r></w:p></w:body></w:document>";
        return createDocxPackage(documentXml, includeStyles);
    }

    private static byte[] createDocxPackage(String documentXml, boolean includeStyles) throws IOException {
        String contentTypes = includeStyles
            ? """
              <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
              <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
              </Types>
              """
            : """
              <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
              <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              </Types>
              """;
        String rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;
        String docRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>
            """;
        String stylesXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Heading1">
                <w:name w:val="heading 1"/>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading2">
                <w:name w:val="heading 2"/>
              </w:style>
            </w:styles>
            """;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "[Content_Types].xml", contentTypes);
            writeZipEntry(zos, "_rels/.rels", rootRels);
            if (includeStyles) {
                writeZipEntry(zos, "word/_rels/document.xml.rels", docRels);
                writeZipEntry(zos, "word/styles.xml", stylesXml);
            }
            writeZipEntry(zos, "word/document.xml", documentXml);
        }
        return baos.toByteArray();
    }

    private static byte[] createDocxWithComplexTableCells() throws IOException {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>Chương 2: Biểu mẫu</w:t></w:r>
                </w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc>
                      <w:p><w:r><w:t>Hàng 1</w:t></w:r></w:p>
                      <w:p><w:r><w:t>Hàng 2</w:t></w:r></w:p>
                    </w:tc>
                    <w:tc>
                      <w:p>
                        <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                        <w:r><w:t>Tiêu đề trong ô</w:t></w:r>
                      </w:p>
                    </w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
            """;
        return createDocxPackage(documentXml, true);
    }

    private static byte[] createUnstyledDocxWithTable(String headingLine, String bodyLine, String col1, String col2)
        throws IOException {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
            """.formatted(headingLine, bodyLine, col1, col2);
        return createDocxPackage(documentXml, false);
    }

    private static byte[] createEncryptedDocx(String password) throws Exception {
        byte[] plainDocx = createStyledDocxWithTable();
        try (POIFSFileSystem fs = new POIFSFileSystem();
             OPCPackage opc = OPCPackage.open(new ByteArrayInputStream(plainDocx));
             ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream()) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor encryptor = info.getEncryptor();
            encryptor.confirmPassword(password);
            try (OutputStream encStream = encryptor.getDataStream(fs)) {
                opc.save(encStream);
            }
            fs.writeFilesystem(encryptedOut);
            return encryptedOut.toByteArray();
        }
    }

    private static byte[] createUnstyledDocxParagraphs(List<String> paragraphs) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String paragraph : paragraphs) {
            body.append("<w:p><w:r><w:t>")
                .append(paragraph.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                .append("</w:t></w:r></w:p>");
        }
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                %s
              </w:body>
            </w:document>
            """.formatted(body.toString());
        return createDocxPackage(documentXml, false);
    }

    private static byte[] createDocxWithNestedTable() throws IOException {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>Chương 1: Bảng lồng nhau</w:t></w:r>
                </w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc>
                      <w:p><w:r><w:t>Ngoài trước</w:t></w:r></w:p>
                      <w:tbl>
                        <w:tr>
                          <w:tc><w:p><w:r><w:t>Trong A</w:t></w:r></w:p></w:tc>
                          <w:tc><w:p><w:r><w:t>Trong B</w:t></w:r></w:p></w:tc>
                        </w:tr>
                      </w:tbl>
                      <w:p><w:r><w:t>Ngoài sau</w:t></w:r></w:p>
                    </w:tc>
                    <w:tc>
                      <w:p><w:r><w:t>Cột ngoài 2</w:t></w:r></w:p>
                    </w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
            """;
        return createDocxPackage(documentXml, true);
    }

    private static byte[] createUnencryptedOle2Doc() throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream("Legacy Word binary".getBytes(StandardCharsets.UTF_8)), "WordDocument");
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static byte[] createXlsxZipWithoutWordMainPart() throws IOException {
        String contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            </Types>
            """;
        String rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
            """;
        String workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>
            """;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "[Content_Types].xml", contentTypes);
            writeZipEntry(zos, "_rels/.rels", rootRels);
            writeZipEntry(zos, "xl/workbook.xml", workbookXml);
        }
        return baos.toByteArray();
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String xml) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(xml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
