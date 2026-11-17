package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownAndPlainTextExtractorTest {

    private final MarkdownSectionExtractor markdownExtractor = new MarkdownSectionExtractor();
    private final PlainTextSectionExtractor plainTextExtractor = new PlainTextSectionExtractor();

    @Test
    void markdownExtractsHierarchicalAtxAndSetextBreadcrumbsAndResetsDeeperLevels() {
        String markdown = """
            Lời mở đầu tài liệu nội bộ trước tiêu đề đầu tiên.

            # Chương 1: Quy định chung

            ## 1.1 Thời giờ làm việc ##

            ### Ca hành chính

            Thời gian làm việc từ 08:30 đến 17:30 mỗi ngày.

            ## 1.2 Nghỉ phép năm

            Nhân viên chính thức được hưởng 12 ngày phép năm.

            Tiêu đề Setext cấp 1
            ====================

            Tiêu đề Setext cấp 2
            --------------------

            Nội dung dưới Setext cấp 2 vẫn giữ cấp 1 làm cha.
            """;

        List<ExtractedSegment> segments = markdownExtractor.extract(
            new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)),
            "quy_che.md"
        );

        assertThat(segments).hasSize(4);
        assertThat(segments).allSatisfy(segment -> assertThat(segment.pageNumber()).isNull());

        assertThat(segments.get(0).sectionHeading()).isNull();
        assertThat(segments.get(0).content()).isEqualTo("Lời mở đầu tài liệu nội bộ trước tiêu đề đầu tiên.");

        assertThat(segments.get(1).sectionHeading())
            .isEqualTo("Chương 1: Quy định chung > 1.1 Thời giờ làm việc > Ca hành chính");
        assertThat(segments.get(1).content()).isEqualTo("Thời gian làm việc từ 08:30 đến 17:30 mỗi ngày.");

        assertThat(segments.get(2).sectionHeading())
            .isEqualTo("Chương 1: Quy định chung > 1.2 Nghỉ phép năm");
        assertThat(segments.get(2).content()).isEqualTo("Nhân viên chính thức được hưởng 12 ngày phép năm.");

        assertThat(segments.get(3).sectionHeading())
            .isEqualTo("Tiêu đề Setext cấp 1 > Tiêu đề Setext cấp 2");
        assertThat(segments.get(3).content()).isEqualTo("Nội dung dưới Setext cấp 2 vẫn giữ cấp 1 làm cha.");
    }

    @Test
    void markdownIgnoresHashesInsideFencedCodeBlocksPipeTablesAndHorizontalRules() {
        String markdown = """
            # Hướng dẫn kỹ thuật

            ```python
            # Đây là comment không phải heading
            x = 1
            ```

            ~~~bash
            # Another comment inside tilde fence
            echo "ok"
            ~~~

            | Bậc | Lương |
            | --- | --- |
            | 1 | 20M |

            ---

            Đoạn văn sau đường kẻ ngang vẫn thuộc cùng mục.
            """;

        List<ExtractedSegment> segments = markdownExtractor.extractFromText(markdown, "tech.md");

        assertThat(segments).hasSize(1);
        ExtractedSegment segment = segments.getFirst();
        assertThat(segment.sectionHeading()).isEqualTo("Hướng dẫn kỹ thuật");
        assertThat(segment.content()).contains("# Đây là comment không phải heading");
        assertThat(segment.content()).contains("# Another comment inside tilde fence");
        assertThat(segment.content()).contains("| Bậc | Lương |");
        assertThat(segment.content()).contains("| --- | --- |");
        assertThat(segment.content()).contains("Đoạn văn sau đường kẻ ngang vẫn thuộc cùng mục.");
    }

    @Test
    void plainTextExtractsVietnameseStructuralHeadingsNormalizesNfcAndStripsBom() {
        String rawNfd = "\uFEFF" + Normalizer.normalize(
            "CHƯƠNG I: QUY ĐỊNH BẢO MẬT\r\n\r\n"
                + "Điều 1. Phạm vi áp dụng\r\n"
                + "Áp dụng cho toàn bộ nhân sự\u00AD trong công ty.\r\n\r\n"
                + "1.1 Quy trình phê duyệt\r\n"
                + "100 ngày kể từ ngày ký không phải tiêu đề.\r\n"
                + "1. Nhân viên gửi yêu cầu trên cổng nội bộ.",
            Normalizer.Form.NFD
        );

        List<ExtractedSegment> segments = plainTextExtractor.extract(
            new ByteArrayInputStream(rawNfd.getBytes(StandardCharsets.UTF_8)),
            "bao_mat.txt"
        );

        assertThat(segments).hasSize(2);
        assertThat(segments).allSatisfy(segment -> assertThat(segment.pageNumber()).isNull());

        assertThat(segments.get(0).sectionHeading())
            .isEqualTo("CHƯƠNG I: QUY ĐỊNH BẢO MẬT > Điều 1. Phạm vi áp dụng");
        assertThat(segments.get(0).content())
            .isEqualTo("Áp dụng cho toàn bộ nhân sự trong công ty.")
            .satisfies(text -> assertThat(Normalizer.isNormalized(text, Normalizer.Form.NFC)).isTrue());

        assertThat(segments.get(1).sectionHeading())
            .isEqualTo("CHƯƠNG I: QUY ĐỊNH BẢO MẬT > Điều 1. Phạm vi áp dụng > 1.1 Quy trình phê duyệt");
        assertThat(segments.get(1).content())
            .contains("100 ngày kể từ ngày ký không phải tiêu đề.")
            .contains("1. Nhân viên gửi yêu cầu trên cổng nội bộ.");
    }

    @Test
    void plainTextUnstructuredProseEmitsSingleSegmentWithNullHeading() {
        String prose = """
            Đây là đoạn văn bản thông thường không có đề mục cấu trúc.

            Đoạn thứ hai trình bày nội dung quy trình tiếp nhận nhân viên mới.
            """;

        List<ExtractedSegment> segments = plainTextExtractor.extractFromText(prose, "notes.txt");

        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().pageNumber()).isNull();
        assertThat(segments.getFirst().sectionHeading()).isNull();
        assertThat(segments.getFirst().content()).contains("Đoạn thứ hai trình bày nội dung");
    }

    @Test
    void emptyAndLimitAndBinaryNullByteGuardsThrowExpectedExtractionExceptions() {
        assertThatThrownBy(() -> markdownExtractor.extract(
            new ByteArrayInputStream("   \r\n\t  ".getBytes(StandardCharsets.UTF_8)),
            "empty.md"
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EMPTY_DOCUMENT);

        assertThatThrownBy(() -> plainTextExtractor.extract(
            new ByteArrayInputStream(new byte[0]),
            "zero.txt"
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EMPTY_DOCUMENT);

        assertThatThrownBy(() -> plainTextExtractor.extract(
            new ByteArrayInputStream("abc\0def".getBytes(StandardCharsets.UTF_8)),
            "binary.txt"
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.CORRUPT_DOCUMENT);

        IngestionProperties tightCharLimit = new IngestionProperties(
            new IngestionProperties.ExtractionProperties(1024L, 50, 0.01d, 1024L, 10L),
            IngestionProperties.ChunkingProperties.defaults()
        );
        PlainTextSectionExtractor boundedTxtExtractor = new PlainTextSectionExtractor(tightCharLimit);
        MarkdownSectionExtractor boundedMdExtractor = new MarkdownSectionExtractor(tightCharLimit);

        String oversized = "Tiếng Việt có dấu vượt quá giới hạn ký tự cho phép. ".repeat(3);
        assertThatThrownBy(() -> boundedTxtExtractor.extract(
            new ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8)),
            "oversized.txt"
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);

        assertThatThrownBy(() -> boundedMdExtractor.extract(
            new ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8)),
            "oversized.md"
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);

        IngestionProperties tightByteLimit = new IngestionProperties(
            new IngestionProperties.ExtractionProperties(20L, 5_000, 0.01d, 1024L, 10L),
            IngestionProperties.ChunkingProperties.defaults()
        );
        PlainTextSectionExtractor byteBoundedExtractor = new PlainTextSectionExtractor(tightByteLimit);
        assertThatThrownBy(() -> byteBoundedExtractor.extract(
            new ByteArrayInputStream("Nội dung vượt quá 20 bytes UTF-8".getBytes(StandardCharsets.UTF_8)),
            "bytes.txt"
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);
    }

    @Test
    void plainTextPreservesNumberedAndRomanListsWithoutDroppingStepsWhileRecognizingGenuineNumberedHeadings() {
        String singleSpacedList = """
            Quy trình xin nghỉ phép:

            1. Nộp đơn cho quản lý trực tiếp
            2. Quản lý phê duyệt trong 3 ngày
            3. Phòng nhân sự ghi nhận

            Hết.
            """;

        List<ExtractedSegment> singleSegments = plainTextExtractor.extractFromText(singleSpacedList, "nghi_phep.txt");
        assertThat(singleSegments).hasSize(1);
        assertThat(singleSegments.getFirst().sectionHeading()).isNull();
        assertThat(singleSegments.getFirst().content())
            .contains("Quy trình xin nghỉ phép:")
            .contains("1. Nộp đơn cho quản lý trực tiếp")
            .contains("2. Quản lý phê duyệt trong 3 ngày")
            .contains("3. Phòng nhân sự ghi nhận")
            .contains("Hết.");

        String doubleSpacedMixedPunctuationList = """
            Các bước thực hiện:

            1. Bước một.

            2. Bước hai.

            3. Bước ba

            Hết.
            """;

        List<ExtractedSegment> doubleSegments = plainTextExtractor.extractFromText(
            doubleSpacedMixedPunctuationList,
            "double_list.txt"
        );
        assertThat(doubleSegments).hasSize(1);
        assertThat(doubleSegments.getFirst().sectionHeading()).isNull();
        assertThat(doubleSegments.getFirst().content())
            .contains("1. Bước một.")
            .contains("2. Bước hai.")
            .contains("3. Bước ba");

        String doubleSpacedNoPeriodList = """
            1. Nộp đơn cho quản lý trực tiếp

            2. Quản lý phê duyệt trong 3 ngày

            3. Phòng nhân sự ghi nhận
            """;

        List<ExtractedSegment> unpunctuatedList = plainTextExtractor.extractFromText(
            doubleSpacedNoPeriodList,
            "double_list_no_dot.txt"
        );
        assertThat(unpunctuatedList).hasSize(1);
        assertThat(unpunctuatedList.getFirst().sectionHeading()).isNull();
        assertThat(unpunctuatedList.getFirst().content())
            .contains("1. Nộp đơn cho quản lý trực tiếp")
            .contains("2. Quản lý phê duyệt trong 3 ngày")
            .contains("3. Phòng nhân sự ghi nhận");

        String genuineNumberedHeadings = """
            1. Phạm vi áp dụng
            Quy chế áp dụng toàn công ty.

            2. Hiệu lực thi hành
            Có hiệu lực ngay.
            """;

        List<ExtractedSegment> headingSegments = plainTextExtractor.extractFromText(
            genuineNumberedHeadings,
            "headings.txt"
        );
        assertThat(headingSegments).hasSize(2);
        assertThat(headingSegments.get(0).sectionHeading()).isEqualTo("1. Phạm vi áp dụng");
        assertThat(headingSegments.get(0).content()).isEqualTo("Quy chế áp dụng toàn công ty.");
        assertThat(headingSegments.get(1).sectionHeading()).isEqualTo("2. Hiệu lực thi hành");
        assertThat(headingSegments.get(1).content()).isEqualTo("Có hiệu lực ngay.");

        String romanList = """
            I. Bước một
            II. Bước hai
            III. Bước ba
            """;
        List<ExtractedSegment> romanListSegments = plainTextExtractor.extractFromText(romanList, "roman_list.txt");
        assertThat(romanListSegments).hasSize(1);
        assertThat(romanListSegments.getFirst().sectionHeading()).isNull();
        assertThat(romanListSegments.getFirst().content())
            .contains("I. Bước một")
            .contains("II. Bước hai")
            .contains("III. Bước ba");

        String genuineRomanHeadings = """
            I. Quy định chung
            Nội dung phần một.

            II. Tổ chức thực hiện
            Nội dung phần hai.
            """;
        List<ExtractedSegment> romanHeadingSegments = plainTextExtractor.extractFromText(
            genuineRomanHeadings,
            "roman_headings.txt"
        );
        assertThat(romanHeadingSegments).hasSize(2);
        assertThat(romanHeadingSegments.get(0).sectionHeading()).isEqualTo("I. Quy định chung");
        assertThat(romanHeadingSegments.get(0).content()).isEqualTo("Nội dung phần một.");
        assertThat(romanHeadingSegments.get(1).sectionHeading()).isEqualTo("II. Tổ chức thực hiện");
        assertThat(romanHeadingSegments.get(1).content()).isEqualTo("Nội dung phần hai.");
    }

    @Test
    void consecutiveBodylessHeadingsArePreservedAcrossMarkdownAndPlainText() {
        String mdConsecutive = """
            # Chương 1
            ## Mục 1.1
            ## Mục 1.2
            Nội dung 1.2
            """;
        List<ExtractedSegment> mdSegments = markdownExtractor.extractFromText(mdConsecutive, "empty_sec.md");
        assertThat(mdSegments).hasSize(2);
        assertThat(mdSegments.get(0).sectionHeading()).isEqualTo("Chương 1");
        assertThat(mdSegments.get(0).content()).isEqualTo("Mục 1.1");
        assertThat(mdSegments.get(1).sectionHeading()).isEqualTo("Chương 1 > Mục 1.2");
        assertThat(mdSegments.get(1).content()).isEqualTo("Nội dung 1.2");

        String txtConsecutive = """
            CHƯƠNG I: TỔNG QUAN
            Điều 1. Phạm vi
            Điều 2. Đối tượng
            Nội dung đối tượng.
            """;
        List<ExtractedSegment> txtSegments = plainTextExtractor.extractFromText(txtConsecutive, "empty_sec.txt");
        assertThat(txtSegments).hasSize(2);
        assertThat(txtSegments.get(0).sectionHeading()).isEqualTo("CHƯƠNG I: TỔNG QUAN");
        assertThat(txtSegments.get(0).content()).isEqualTo("Điều 1. Phạm vi");
        assertThat(txtSegments.get(1).sectionHeading()).isEqualTo("CHƯƠNG I: TỔNG QUAN > Điều 2. Đối tượng");
        assertThat(txtSegments.get(1).content()).isEqualTo("Nội dung đối tượng.");
    }

    @Test
    void utf16LeAndBeWithBomDecodeCleanlyWhileUtf32LeAndRawNullBytesStillFail() {
        String txtSample = "CHƯƠNG I: BẢO MẬT\n\nNội dung tiếng Việt đầy đủ dấu ăâđêôơư.";
        byte[] utf16LePayload = concat(
            new byte[]{(byte) 0xFF, (byte) 0xFE},
            txtSample.getBytes(StandardCharsets.UTF_16LE)
        );
        List<ExtractedSegment> leSegments = plainTextExtractor.extract(
            new ByteArrayInputStream(utf16LePayload),
            "utf16le.txt"
        );
        assertThat(leSegments).hasSize(1);
        assertThat(leSegments.getFirst().sectionHeading()).isEqualTo("CHƯƠNG I: BẢO MẬT");
        assertThat(leSegments.getFirst().content()).contains("Nội dung tiếng Việt đầy đủ dấu ăâđêôơư.");

        String mdSample = "# Tiêu đề UTF-16BE\n\nNội dung Markdown chuẩn tiếng Việt.";
        byte[] utf16BePayload = concat(
            new byte[]{(byte) 0xFE, (byte) 0xFF},
            mdSample.getBytes(StandardCharsets.UTF_16BE)
        );
        List<ExtractedSegment> beSegments = markdownExtractor.extract(
            new ByteArrayInputStream(utf16BePayload),
            "utf16be.md"
        );
        assertThat(beSegments).hasSize(1);
        assertThat(beSegments.getFirst().sectionHeading()).isEqualTo("Tiêu đề UTF-16BE");
        assertThat(beSegments.getFirst().content()).isEqualTo("Nội dung Markdown chuẩn tiếng Việt.");

        byte[] utf32LeBomPayload = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x00, 'A', 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> plainTextExtractor.extract(
            new ByteArrayInputStream(utf32LeBomPayload),
            "utf32.txt"
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.CORRUPT_DOCUMENT);
    }

    private static byte[] concat(byte[] prefix, byte[] body) {
        byte[] out = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(body, 0, out, prefix.length, body.length);
        return out;
    }
}
