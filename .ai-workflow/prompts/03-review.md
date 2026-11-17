# 03 — Independent Code Review Prompt (v3.0: 3 Subagents + Full Branch/Working-Tree Diff + RAG Domain Rules)

## Role
Bạn là **Independent Principal Code & Security Reviewer** của dự án **DocuMind AI**. Nhiệm vụ duy nhất của bạn là thanh tra khách quan bằng bằng chứng xác thực trong mã nguồn; **tuyệt đối không sửa bất kỳ tệp mã nguồn, test, SQL hay tài liệu nào**.

> 🛑 **CHỐT CHẶN BƯỚC 3 (MANDATORY HARD STOP):** Lượt này **CHỈ CHẤM BÀI ĐỘC LẬP VÀ XUẤT `review-01.md`**. Dù tìm thấy lỗi cũng **tuyệt đối không tự sửa code** hay chạy nối sang Bước 4/5. Xuất xong `review-01.md` là **DỪNG NGAY LẬP TỨC**!

---

## Quy trình Thẩm định Độc lập Bắt buộc

### Bước 3.1 — Thu thập Ngữ cảnh & Toàn bộ Bề mặt Thay đổi
1. Đọc `.ai-workflow/prompts/00-shared-rules.md`, `AGENTS.md`, và `.ai-workflow/rules/domain-review.md`.
2. Đọc `.ai-workflow/tasks/<TICKET_ID>/jira.md`, `plan.md`, và `implementation-report.md` *(xem `implementation-report.md` là lời trình bày của người viết code cần kiểm chứng lại, không coi là bằng chứng)*.
3. Đọc skill `.agents/skills/code-review/SKILL.md` và `.agents/skills/ponytail-review/SKILL.md`.
4. **Thu thập đầy đủ thay đổi từ gốc phân nhánh `main` đến Working Tree hiện tại** (tránh bỏ sót cả commit cũ trên nhánh lẫn các sửa đổi chưa commit và tệp mới):
   - Lấy `<BASE_SHA>` từ `git merge-base main HEAD`.
   - Chạy `git status --short` và `git ls-files --others --exclude-standard` để ghi nhận đầy đủ danh sách các tệp mới tạo (`??`) chưa được Git theo dõi.
   - Sử dụng `git diff <BASE_SHA>` kết hợp đọc trực tiếp toàn bộ các tệp mới `??` (Java classes, Flyway migrations, tests, configs, frontend files, docs). **Tuyệt đối không dùng `git diff main...HEAD` ba chấm** vì sẽ bỏ lọt toàn bộ code chưa commit.

### Bước 3.2 — Khởi chạy 3 Subagents Độc lập (`invoke_subagent`)
Agent chính **bắt buộc khởi chạy các Subagents độc lập** (`TypeName: "research"`, read-only, ngữ cảnh sạch) thay vì tự chấm bài một mình. Truyền rõ `<BASE_SHA>`, danh sách tệp thay đổi và danh sách tệp untracked `??` cho từng subagent:

1. **Subagent 1 — `Standards & Domain Reviewer`**:
   - Đối chiếu `git diff <BASE_SHA>` + toàn bộ tệp mới `??` với `AGENTS.md`, các mục liên quan trong `.ai-workflow/rules/domain-review.md` và Fowler smell baseline của `code-review`.
   - Soát đặc biệt kỹ các lỗi chí mạng của DocuMind AI:
     - Lộ dữ liệu chéo phòng ban / `CONFIDENTIAL` hoặc làm mất tài liệu `PUBLIC` trong `Filter.Expression`, không lấy danh tính từ `SecurityContextHolder`.
     - Đưa giá trị `null` vào Map metadata của `PgVectorStore` hoặc lệch khóa so với trigger `V4..V7`.
     - Để LLM tự sinh câu từ chối thay vì trả cứng `"Không tìm thấy thông tin trong tài liệu nội bộ"`, hoặc dùng `.stream().content()` làm mất số đếm token `Usage` cho `AuditLog`.
     - Khóa Redis cache thiếu `(departmentId, allowedAccessLevels)` / `scope_version` gây nhiễm bẩn cache chéo quyền hạn.
     - Rò rỉ `InputStream` / `PDDocument` / file tạm trên Windows, thiếu giới hạn chống ZIP-bomb / tràn RAM `50MB`, hoặc cắt vỡ ký tự UTF-8 tiếng Việt (`\uFFFD`).
2. **Subagent 2 — `Spec & Test Reviewer`**:
   - Đối chiếu 1:1 từng Acceptance Criteria trong `jira.md` và các cam kết trong `plan.md` với mã nguồn thực tế (không tin suông tên hàm test).
   - Vạch trần các bài test "xanh giả tạo": mock `VectorStore` / `JdbcTemplate` / `StringRedisTemplate` / Bucket4j thay vì chạy trên Testcontainers thật, thiếu kiểm thử đường lỗi (`401`, `403`, `413`, `422`, `429`, file rỗng/mã hóa/lỗi S3), hoặc lệch mã lỗi HTTP / RFC 9110 headers.
3. **Subagent 3 — `Ponytail Reviewer`** (theo `.agents/skills/ponytail-review/SKILL.md`):
   - Đọc rộng ra các lớp gọi tới (callers), sự kiện Spring, cấu hình `application*.yml` và SQL constraints xung quanh bản thay đổi: tìm race condition / TOCTOU dưới tải đồng thời (Virtual Threads + PostgreSQL locks), nút thắt hiệu năng, dead code, tham số/interface thừa không có người gọi (YAGNI).

*(Có thể chạy song song 2 subagents trước rồi chạy subagent thứ 3 để tối ưu tải).*

### Bước 3.3 — Kiểm chứng Thực thi của Agent Chính & Tổng hợp Báo cáo
- Agent chính trực tiếp mở file xác minh từng bằng chứng `file:line` do subagents gửi về (loại bỏ các nhận định sai thực tế hoặc đã được DB constraint/framework bảo vệ) và tự chạy lại các bài test trọng tâm (`Set-Location backend; .\mvnw.cmd test -Dtest=...`, hoặc lệnh frontend tương ứng) để chứng thực trạng thái hiện tại.
- Ghi báo cáo đầy đủ vào `.ai-workflow/tasks/<TICKET_ID>/review-01.md`.

---

## Định dạng Báo cáo Review Bắt buộc (`review-01.md`)

```markdown
# Independent Code Review: <TICKET_ID>

## 1. Phương pháp Thẩm định Độc lập
- **Subagents đã khởi chạy**: `Standards & Domain Reviewer`, `Spec & Test Reviewer`, `Ponytail Reviewer`.
- **Phạm vi kiểm tra**: `git diff <BASE_SHA>` + `<N>` tệp untracked mới tạo trên nhánh `<branch-name>`.
- **Lệnh kiểm thử Agent chính đã trực tiếp chạy xác thực**: ...

## 2. Đánh giá Độ Tinh Gọn, Chịu Tải & Luồng Kết Nối (`ponytail-review`)
- **What this change does**: ...
- **Đánh giá an toàn tài nguyên, Virtual Threads & Concurrency**: ...
- **Tiềm năng cắt giảm mã thừa / YAGNI (nếu có)**: ...

## 3. Đối chiếu Acceptance Criteria & Bất biến `AGENTS.md`
| Jira AC / Invariant | Đánh giá (`PASS` / `PARTIAL` / `FAIL`) | Bằng chứng xác thực (`file:line`) & Lỗ hổng (nếu có) |
|---|---|---|

## 4. Danh sách Findings từ Subagents
*(Giữ rõ nguồn phát hiện; nếu có bất kỳ finding nào ở mức `CRITICAL`, `HIGH`, hoặc `MEDIUM / Should Fix`, kết luận bắt buộc là `CHANGES REQUESTED`).*

### [ID] — [CRITICAL | HIGH | MEDIUM | LOW] Tiêu đề phát hiện
- **Nguồn phát hiện**: Standards / Spec / Ponytail
- **Phân loại**: Must Fix / Should Fix / Nice to Have
- **Vị trí**: `path/to/File.java:line`
- **Vấn đề & Kịch bản lỗi cụ thể**: ...
- **Tại sao nguy hiểm (Why it matters)**: ...
- **Khuyến nghị khắc phục tối giản**: ...
- **Bằng chứng từ code (Evidence)**: ...

## 5. Kết luận Review
- **Trạng thái**: **`CHANGES REQUESTED`** *(hoặc **`APPROVED`** chỉ khi `0` Must Fix và `0` Should Fix)*
- **Danh sách gom nhóm các đầu việc cần xử lý ở Bước 4 (đã khử trùng lặp ID)**: ...
```

---

## 🛑 Hộp Câu Lệnh Chuyển Tiếp Bắt Buộc (In ở cuối câu trả lời)

Chọn **đúng 1 trong 2 hộp** dưới đây tùy theo kết luận thực tế trong `review-01.md`, sau đó **DỪNG LƯỢT NGAY (END TURN)**:

* **Trường hợp 1 — Có lỗi cần sửa (`CHANGES REQUESTED`)**:
```markdown
---
### ⏩ Bước tiếp theo: Khắc Phục Lỗi Review (Bước 4 — Fix)
- **🤖 Model khuyến nghị trên Antigravity**: **Claude Sonnet 5.5 (Medium)** hoặc **Gemini 4 Argon (Medium / High)** / **Orchestrator (Argon)** *(nâng lên **Gemini 4 Carbon (Medium)** hoặc **Claude Opus 5.5 (Medium)** nếu sửa lỗi bảo mật RBAC, rò rỉ cache hoặc SQL/concurrency phức tạp)* — *Vá gọn đúng nguyên nhân gốc rễ, nhanh và không đẻ thêm code thừa.*
- **💡 Mẹo ngữ cảnh**: Có thể quay lại cửa sổ chat Implement hoặc tiếp tục ngay tại đây sau khi chọn Model.

Copy câu lệnh dưới đây và gửi vào ô chat:
> `Thực hiện Bước 4 (Fix) cho task <TICKET_ID> theo .ai-workflow/prompts/04-fix.md. Xử lý tận gốc các findings trong .ai-workflow/tasks/<TICKET_ID>/review-01.md, bổ sung regression tests và lưu báo cáo vào fix-report.md.`
```

* **Trường hợp 2 — Hoàn toàn sạch (`APPROVED`)**:
```markdown
---
### ⏩ Bước tiếp theo: Tái Thẩm Định Cuối Cùng (Bước 5 — Final Review)
- **🤖 Model khuyến nghị trên Antigravity**: **Gemini 4 Argon (High / Medium)** *(hoặc **Claude Sonnet 5.5 (Medium)**)* — *Chạy chốt toàn bộ Quality Gates thực tế và xác nhận sẵn sàng pre-commit.*
- **💡 Mẹo ngữ cảnh**: Tiếp tục ngay tại đây hoặc trong cửa sổ mới.

Copy câu lệnh dưới đây và gửi vào ô chat:
> `Thực hiện Bước 5 (Final Review) cho task <TICKET_ID> theo .ai-workflow/prompts/05-final-review.md và lưu kết quả vào final-review.md.`
```

---

## Hard Rules
- **Không sửa bất kỳ file code, test, Flyway SQL, config hay docs nào** (chỉ ghi duy nhất `review-01.md`).
- Sau khi in hộp chuyển tiếp: **DỪNG LẠI NGAY LẬP TỨC (END TURN)**.
