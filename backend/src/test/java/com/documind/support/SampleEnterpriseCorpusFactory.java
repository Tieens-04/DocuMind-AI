package com.documind.support;

import com.documind.domain.enums.AccessLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the 5-document bilingual Vietnamese/English enterprise corpus across PDF, DOCX, Markdown, and TXT
 * and loads the ground-truth evaluation queries used for Milestone 1 integration testing and {@code bge-m3} tuning.
 */
public final class SampleEnterpriseCorpusFactory {

    public static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String LIBERATION_SANS_TTF = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    private static final String INFOSEC_MD_RESOURCE = "/corpus/week1/en-infosec-access-standard.md";
    private static final String HYBRID_WORK_TXT_RESOURCE = "/corpus/week1/bilingual-hybrid-work-charter.txt";
    private static final String WEEK1_EVAL_RESOURCE = "/eval/week1_milestone_queries.jsonl";
    private static final int PDF_LINE_WRAP_CHARS = 82;
    private static final float PDF_LEADING = 14.5f;

    private SampleEnterpriseCorpusFactory() {
    }

    public static List<SampleDocumentSpec> buildWeek1EnterpriseCorpus() {
        return List.of(
            new SampleDocumentSpec(
                "vn-hr-handbook",
                "vn-hr-handbook-2026.pdf",
                "application/pdf",
                buildVnHrHandbookPdf(),
                HR_DEPT_ID,
                AccessLevel.DEPARTMENT
            ),
            new SampleDocumentSpec(
                "vn-sales-expense",
                "vn-sales-expense-policy.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                buildVnSalesExpenseDocx(),
                SALES_DEPT_ID,
                AccessLevel.DEPARTMENT
            ),
            new SampleDocumentSpec(
                "en-infosec-standard",
                "en-infosec-access-standard.md",
                "text/markdown",
                readClasspathResourceBytes(INFOSEC_MD_RESOURCE),
                IT_DEPT_ID,
                AccessLevel.PUBLIC
            ),
            new SampleDocumentSpec(
                "en-cloud-runbook",
                "en-cloud-release-runbook.pdf",
                "application/pdf",
                buildEnCloudReleaseRunbookPdf(),
                IT_DEPT_ID,
                AccessLevel.CONFIDENTIAL
            ),
            new SampleDocumentSpec(
                "bilingual-hybrid-work",
                "bilingual-hybrid-work-charter.txt",
                "text/plain",
                readClasspathResourceBytes(HYBRID_WORK_TXT_RESOURCE),
                HR_DEPT_ID,
                AccessLevel.PUBLIC
            )
        );
    }

    public static List<EvalQueryCase> loadWeek1MilestoneQueries(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        List<EvalQueryCase> cases = new ArrayList<>();
        try (InputStream is = SampleEnterpriseCorpusFactory.class.getResourceAsStream(WEEK1_EVAL_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Missing evaluation resource: " + WEEK1_EVAL_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        cases.add(objectMapper.readValue(trimmed, EvalQueryCase.class));
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load evaluation dataset: " + WEEK1_EVAL_RESOURCE, ex);
        }
        return List.copyOf(cases);
    }

    public static UUID departmentIdForCode(String deptCode) {
        if (deptCode == null) {
            throw new IllegalArgumentException("deptCode must not be null");
        }
        return switch (deptCode.trim().toUpperCase()) {
            case "IT" -> IT_DEPT_ID;
            case "HR" -> HR_DEPT_ID;
            case "SALES" -> SALES_DEPT_ID;
            default -> throw new IllegalArgumentException("Unsupported department code in eval dataset: " + deptCode);
        };
    }

    public static byte[] createMultiPagePdf(List<List<String>> pagesParagraphs) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDType0Font font;
            try (InputStream fontStream = Objects.requireNonNull(
                PDDocument.class.getResourceAsStream(LIBERATION_SANS_TTF),
                "Missing bundled LiberationSans-Regular.ttf"
            )) {
                font = PDType0Font.load(doc, fontStream);
            }
            for (List<String> pageParagraphs : pagesParagraphs) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 11f);
                    cs.setLeading(PDF_LEADING);
                    cs.newLineAtOffset(50f, 740f);
                    for (String paragraph : pageParagraphs) {
                        for (String line : wrapText(paragraph, PDF_LINE_WRAP_CHARS)) {
                            cs.showText(line);
                            cs.newLine();
                        }
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to synthesize sample PDF document", ex);
        }
    }

    public static byte[] createStructuredDocx(List<DocxBlock> blocks) {
        String contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-officedocument.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
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
              <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
              <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/></w:style>
            </w:styles>
            """;

        StringBuilder bodyXml = new StringBuilder();
        for (DocxBlock block : blocks) {
            bodyXml.append("    <w:p>");
            if (block.styleId() != null && !block.styleId().isBlank()) {
                bodyXml.append("<w:pPr><w:pStyle w:val=\"")
                    .append(escapeXml(block.styleId()))
                    .append("\"/></w:pPr>");
            }
            bodyXml.append("<w:r><w:t xml:space=\"preserve\">")
                .append(escapeXml(block.text()))
                .append("</w:t></w:r></w:p>\n");
        }

        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
            %s  </w:body>
            </w:document>
            """.formatted(bodyXml.toString());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "[Content_Types].xml", contentTypes);
            writeZipEntry(zos, "_rels/.rels", rootRels);
            writeZipEntry(zos, "word/_rels/document.xml.rels", docRels);
            writeZipEntry(zos, "word/styles.xml", stylesXml);
            writeZipEntry(zos, "word/document.xml", documentXml);
            zos.finish();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to synthesize sample DOCX document", ex);
        }
    }

    private static byte[] buildVnHrHandbookPdf() {
        List<List<String>> pages = List.of(
            List.of(
                "SO TAY NHAN SU DOCUMIND AI 2026 - PHONG HANH CHINH NHAN SU",
                "Chương 1: Quy định Tuyển dụng, Hội nhập và Thời gian Thử việc",
                "Nhân sự mới gia nhập công ty được bố trí người hướng dẫn chuyên môn trong hai tuần đầu tiên để làm quen với quy trình vận hành, bảo mật dữ liệu nội bộ và hệ thống quản trị công việc.",
                "Theo Bộ luật Lao động và quy chế nội bộ, thời gian thử việc tối đa cho vị trí kỹ sư phần mềm, chuyên viên phân tích dữ liệu và chuyên viên có trình độ đại học là 60 ngày kể từ ngày nhận việc chính thức. Đối với các vị trí cộng tác viên hỗ trợ hành chính không yêu cầu bằng cấp chuyên môn, thời gian thử việc không vượt quá 30 ngày.",
                "Trong suốt thời gian thử việc, nhân sự được hưởng tối thiểu 85% mức lương chính thức ghi nhận trên thư mời nhận việc và được tham gia đầy đủ các khóa đào tạo an toàn thông tin bắt buộc. Trước khi kết thúc thử việc 5 ngày làm việc, quản lý trực tiếp sẽ thực hiện đánh giá hiệu suất 360 độ để trình Giám đốc Nhân sự ký hợp đồng lao động xác định thời hạn."
            ),
            List.of(
                "Chương 2: Chính sách Nghỉ phép năm và Cộng thêm theo Thâm niên",
                "Mục tiêu của chính sách nghỉ phép là giúp cán bộ nhân viên tái tạo sức lao động và cân bằng cuộc sống. Nhân viên chính thức làm việc đủ 12 tháng trong năm tài chính được hưởng 14 ngày phép nguyên lương mỗi năm, tính từ tháng tiếp theo sau khi ký hợp đồng lao động chính thức.",
                "Bên cạnh quỹ phép tiêu chuẩn, công ty áp dụng chính sách tri ân thâm niên gắn bó lâu dài: nhân viên được cộng thêm 1 ngày phép hưởng nguyên lương cho mỗi 5 năm thâm niên làm việc liên tục tại doanh nghiệp.",
                "Trường hợp nhân viên chưa sử dụng hết số ngày phép của năm hiện tại được phép chuyển tối đa 5 ngày phép sang quý đầu tiên của năm kế tiếp (hạn sử dụng đến hết ngày 31/03). Mọi đơn xin nghỉ phép từ 3 ngày liên tiếp trở lên cần được gửi qua cổng nhân sự trước ít nhất 5 ngày làm việc để trưởng bộ phận sắp xếp nhân sự thay thế."
            ),
            List.of(
                "Chương 3: Gói Bảo hiểm Sức khỏe Bổ sung và Khám Sức khỏe Định kỳ",
                "Ngoài chế độ bảo hiểm xã hội và bảo hiểm y tế theo quy định nhà nước, toàn bộ nhân viên chính thức ký hợp đồng từ 12 tháng trở lên được cấp thẻ bảo hiểm sức khỏe cao cấp PVI Care do công ty chi trả 100% phí thường niên.",
                "Gói bảo hiểm sức khỏe PVI Care có hạn mức bảo lãnh viện phí điều trị nội trú lên tới 120 triệu đồng mỗi năm cho mỗi nhân viên và hỗ trợ thanh toán chi phí khám ngoại trú, nha khoa cơ bản theo danh mục bệnh viện liên kết toàn quốc.",
                "Bên cạnh đó, phòng Hành chính Nhân sự tổ chức chương trình khám sức khỏe tổng quát định kỳ hằng năm vào tháng 9 tại hệ thống bệnh viện đối tác ở Hà Nội, Đà Nẵng và TP. Hồ Chí Minh. Lịch đăng ký khung giờ khám chi tiết được gửi qua email nội bộ trước ngày 15/08 hằng năm."
            )
        );
        return createMultiPagePdf(pages);
    }

    private static byte[] buildVnSalesExpenseDocx() {
        return createStructuredDocx(List.of(
            new DocxBlock("Heading1", "Chương 1: Định mức công tác phí và lưu trú nội địa"),
            new DocxBlock("Heading2", "Điều 1.1: Tiêu chuẩn khách sạn và phương tiện di chuyển"),
            new DocxBlock(
                null,
                "Quy chế công tác phí áp dụng cho toàn bộ chuyên viên kinh doanh, tư vấn giải pháp và quản lý khu vực khi thực hiện chuyến công tác gặp gỡ khách hàng doanh nghiệp ngoài địa bàn làm việc chính thức. Mọi chuyến công tác có chi phí dự kiến trên 5.000.000 VNĐ cần được Giám đốc Kinh doanh phê duyệt trước khi đặt vé."
            ),
            new DocxBlock(
                null,
                "Hạn mức tiền phòng khách sạn tối đa mỗi đêm khi công tác tại Hà Nội, Đà Nẵng và TP. Hồ Chí Minh là 1.500.000 VNĐ (đã bao gồm thuế giá trị gia tăng và phí dịch vụ). Tại các tỉnh thành khác trên toàn quốc, hạn mức lưu trú áp dụng tối đa 1.100.000 VNĐ mỗi đêm và ưu tiên sử dụng hệ thống khách sạn đối tác có xuất hóa đơn điện tử hợp lệ mang tên công ty."
            ),
            new DocxBlock("Heading2", "Điều 1.2: Phụ cấp lưu trú theo ngày"),
            new DocxBlock(
                null,
                "Nhân viên đi công tác qua đêm ngoài tỉnh được chi trả phụ cấp lưu trú cố định 350.000 VNĐ mỗi ngày để trang trải chi phí ăn uống và đi lại nội bộ tại địa phương. Trường hợp đi công tác về trong ngày có khoảng cách di chuyển một chiều từ 80 km trở lên được hưởng 50% mức phụ cấp lưu trú tiêu chuẩn."
            ),
            new DocxBlock("Heading1", "Chương 2: Thời hạn quyết toán hoàn ứng và chi trả hoa hồng"),
            new DocxBlock("Heading2", "Điều 2.1: Quy trình hoàn ứng chứng từ kế toán"),
            new DocxBlock(
                null,
                "Sau khi kết thúc chuyến công tác, nhân viên phải tập hợp đầy đủ hóa đơn giá trị gia tăng điện tử, thẻ lên máy bay và biên lai hợp lệ để nộp hồ sơ quyết toán hoàn ứng về bộ phận Kế toán trong vòng 7 ngày làm việc kể từ ngày trở về văn phòng. Hồ sơ nộp trễ quá 15 ngày mà không có lý do giải trình được phê duyệt sẽ bị tạm khấu trừ khoản tạm ứng vào kỳ lương gần nhất."
            ),
            new DocxBlock("Heading2", "Điều 2.2: Chu kỳ đối soát và chi trả hoa hồng doanh số"),
            new DocxBlock(
                null,
                "Hoa hồng doanh số theo quý của phòng Kinh doanh được tính dựa trên doanh thu thực thu (cash-collected revenue) đã được bộ phận Tài chính xác nhận đến hết ngày làm việc cuối cùng của quý. Kỳ chi trả hoa hồng doanh số quý được thực hiện định kỳ vào ngày 15 của tháng đầu quý kế tiếp cùng bảng đối soát chi tiết gửi tới từng nhân viên."
            )
        ));
    }

    private static byte[] buildEnCloudReleaseRunbookPdf() {
        List<List<String>> pages = List.of(
            List.of(
                "CONFIDENTIAL - IT INFRASTRUCTURE & SRE RELEASE RUNBOOK",
                "Section 1: Progressive Canary Rollout Procedure",
                "All production deployments for DocuMind AI backend services, pgvector ingestion workers, and API gateways must execute through the automated GitOps canary pipeline. Direct manual image overrides in production Kubernetes namespaces are strictly forbidden outside declared disaster recovery windows.",
                "Every canary release begins by shifting 10% of live production traffic to the candidate revision for an initial 15-minute observation soak. During this phase, SRE on-call engineers inspect golden signals including HTTP 5xx ratio, JVM virtual-thread pinning telemetry, PostgreSQL connection pool saturation, and MinIO upload latency.",
                "Once the initial canary stage remains healthy with zero SLO breaches, the pipeline promotes traffic weight to 50% for a 30-minute stability window before completing full 100% rollout across all availability zones."
            ),
            List.of(
                "Section 2: Automated Rollback Triggers and Guardrail Thresholds",
                "Automated Argo Rollouts analysis continuously evaluates Prometheus metrics at 30-second intervals throughout every canary stage. Human confirmation is never required to abort a degrading deployment when quantitative safety thresholds are violated.",
                "The pipeline immediately triggers an automated production rollback to the previous stable ReplicaSet if the HTTP 5xx server error rate exceeds 1.0% or if the API p95 response latency exceeds 800 milliseconds continuously over a 5-minute evaluation window.",
                "Additional hard abort triggers include PostgreSQL HikariCP acquisition timeouts exceeding 3 events per minute, Redis cluster connection failures, or JVM heap utilization crossing 85% sustained after full garbage collection."
            ),
            List.of(
                "Section 3: Severity Classification and Post-Incident Review SLA",
                "When an automated rollback occurs or customer-facing availability degrades, the incident commander opens a tracking channel and assigns an incident scribe within 10 minutes. Customer support leadership receives status updates every 30 minutes until full service restoration is confirmed.",
                "Following resolution of any Severity-1 (P1) or Severity-2 (P2) production incident, the engineering owner must draft and publish a comprehensive blameless postmortem report within 48 hours of service recovery.",
                "Every postmortem document must contain a minute-by-minute timeline, five-whys root cause analysis, verification test coverage gap assessment, and prioritized Jira corrective actions with named owners and target completion dates."
            )
        );
        return createMultiPagePdf(pages);
    }

    private static byte[] readClasspathResourceBytes(String resourcePath) {
        try (InputStream is = SampleEnterpriseCorpusFactory.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Missing classpath resource: " + resourcePath);
            }
            return is.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read classpath resource: " + resourcePath, ex);
        }
    }

    private static List<String> wrapText(String text, int maxCharsPerLine) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.isEmpty()) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= maxCharsPerLine) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String xml) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(xml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String escapeXml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    public record SampleDocumentSpec(
        String key,
        String filename,
        String mimeType,
        byte[] content,
        UUID departmentId,
        AccessLevel accessLevel
    ) {
    }

    public record DocxBlock(String styleId, String text) {
    }

    public record EvalQueryCase(
        String id,
        String category,
        String role,
        String dept,
        String query,
        String expectedFile,
        Integer expectedPage,
        String expectedKeyPhrase,
        String forbiddenPhrase
    ) {
    }
}
