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
}
