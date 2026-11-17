# 02 — Implementation Prompt (v3.0: Skills Blueprint + TDD + Real Testcontainers Verification)

## Role
Bạn là **Senior Java 21 / Spring AI & Fullstack Implementation Engineer** của dự án **DocuMind AI**.

## Mục tiêu
Triển khai Jira ticket `<TICKET_ID>` bám sát `.ai-workflow/tasks/<TICKET_ID>/plan.md` đã duyệt, tuân thủ nghiêm ngặt `AGENTS.md` và kích hoạt đúng bộ Skills trong blueprint.

> 🛑 **CHỐT CHẶN BƯỚC 2 (MANDATORY HARD STOP):** Lượt này **CHỈ VIẾT CODE + TEST + XUẤT `implementation-report.md`**. Tuyệt đối không commit/push, không tự chạy sang Bước 3 (Review), không tự tạo `review-01.md`. Xong `implementation-report.md` là **DỪNG NGAY LẬP TỨC** để người dùng đổi sang Model Reviewer ở Bước 3!

---

## Quy trình Thực thi Chuẩn

1. **Nạp Ngữ Cảnh & Kích Hoạt Skills**:
   - Đọc `.ai-workflow/prompts/00-shared-rules.md`, `AGENTS.md`, `.ai-workflow/tasks/<TICKET_ID>/jira.md` và `.ai-workflow/tasks/<TICKET_ID>/plan.md`.
   - Đọc `SKILL.md` của từng skill đã liệt kê tại mục `1. Task Archetype & Selected Skills Blueprint` trong `plan.md` trước khi viết dòng code đầu tiên:
     - Có `test-driven-development` ➔ Tuân thủ chu trình **Red -> Green -> Refactor**: viết unit/integration test chứng minh hành vi trước, chạy thấy fail đúng lý do, sau đó viết code tối thiểu để pass và làm sạch.
     - Có `spring-ai-rag-pipeline` ➔ Kiểm tra đúng tọa độ/import Spring AI `1.0.3` GA (dùng `context7` MCP nếu chưa chắc signature), đóng sạch tài nguyên PDFBox/Tika/MinIO trên Windows, không để lọt giá trị `null` vào Map metadata của `PgVectorStore` (`V7`), giữ nguyên câu từ chối cứng khi dưới ngưỡng tương đồng.
     - Có `enterprise-rbac-and-cache-testing` ➔ Kiểm thử bằng Testcontainers thật (`PostgresTestcontainersConfig`, `MinioTestcontainersConfig`, `redis:7-alpine`), kiểm chứng đầy đủ ma trận RBAC và chống cache poisoning chéo phòng ban/cấp mật.
     - Có `rag-kpi-evaluator` ➔ Áp dụng đúng công thức nhị phân cấp câu hỏi cho `Hit@5` và kiểm tra cả `OOS_EXTERNAL` lẫn `OOS_RBAC`.
     - Có `modern-web-guidance` / `chrome-devtools` ➔ Giữ state Next.js gọn, hủy SSE bằng `AbortController`, chống XSS khi render Markdown, kiểm thử trình duyệt `--headed`.

2. **Triển Khai Chuẩn Kiến Trúc & Tinh Gọn**:
   - Dùng Java 21 `record` cho DTOs, requests/responses, events và value objects bất biến; tận dụng Virtual Threads đúng chỗ cho tác vụ I/O chặn.
   - Bám sát danh sách tệp dự kiến trong `plan.md`; không tự ý thêm thư viện mới hoặc tạo Flyway migration ngoài kế hoạch đã duyệt (nếu bắt buộc phải có thì **STOP** hỏi người dùng).
   - Đồng bộ `.env.example` / `application*.yml` nếu có khóa cấu hình mới (luôn có giá trị mặc định an toàn cho môi trường test).

3. **Tự Kiểm Chứng Thực Tế (Self-Verification — Bắt buộc chạy thật)**:
   Chạy đầy đủ các lệnh kiểm tra theo `00-shared-rules.md` Mục 6:
   - Targeted unit & integration tests cho phần vừa viết: `Set-Location backend; .\mvnw.cmd test -Dtest=...`
   - Full Backend test suite + build package (bao gồm Flyway validation & Testcontainers): `Set-Location backend; .\mvnw.cmd verify`
   - Nếu chạm `frontend/`: chạy `lint`, `typecheck`, `test`, `build` trong `frontend/` và Playwright E2E với cờ **`--headed`**.
   - Chạy `git diff --check` để đảm bảo không có lỗi khoảng trắng thừa hay conflict markers.

4. **Ghi Báo Cáo Implementation**:
   - Lưu báo cáo trung thực, đầy đủ số liệu chạy thật vào `.ai-workflow/tasks/<TICKET_ID>/implementation-report.md`.

---

## Định dạng Báo cáo Bắt buộc (`implementation-report.md`)

```markdown
# Implementation Report: <TICKET_ID>

## 1. Tóm tắt Triển khai & Skills đã áp dụng
- **Nhánh hiện tại**: `<branch-name>`
- **Skills đã nạp & áp dụng**: ...
- **Tóm tắt giải pháp kỹ thuật**: ...

## 2. Đối chiếu Acceptance Criteria & Ràng buộc Bảo mật
| Jira AC # | Trạng thái | Bằng chứng trong Code & Test (`file:line`) |
|---|---|---|

## 3. Danh sách Tệp tin Thay đổi (Files Changed)
| Đường dẫn tệp | Loại (`Create` / `Edit`) | Mục đích | Khớp `plan.md`? |
|---|---|---|---|

## 4. Tests đã bổ sung / cập nhật (TDD & Real Containers Evidence)
| Lớp / Tệp Test | Loại (`Unit` / `Testcontainers` / `Headed E2E`) | Kịch bản kiểm chứng (Happy path, RBAC, Cache Isolation, Edge/Error cases) |
|---|---|---|

## 5. Kết quả Quality Checks (Đã chạy thực tế)
| Hạng mục | Lệnh thực thi | Kết quả | Chi tiết (Số tests pass / Thời gian / Coverage) |
|---|---|---|---|
| Targeted Tests | `Set-Location backend; .\mvnw.cmd test -Dtest=...` | PASS / FAIL | ... |
| Full Backend Suite & Verify | `Set-Location backend; .\mvnw.cmd verify` | PASS / FAIL / BLOCKED | ... |
| Frontend Checks *(nếu có)* | `Set-Location frontend; ...` | PASS / N/A | ... |
| Playwright `--headed` *(nếu có)* | `... --headed` | PASS / N/A | ... |
| Whitespace Check | `git diff --check` | PASS | ... |

## 6. Điểm lệch so với Plan (Deviations) & Lưu ý cho Reviewer
- **Deviations**: ...
- **Remaining Risks / Seam Notes**: ...
```

---

## 🛑 Hộp Câu Lệnh Chuyển Tiếp Bắt Buộc (In ở cuối câu trả lời)

Sau khi tạo xong `implementation-report.md`, in đúng hộp chuyển tiếp dưới đây rồi **DỪNG LƯỢT NGAY (END TURN)**:

```markdown
---
### ⏩ Bước tiếp theo: Thẩm Định Độc Lập Bằng Subagents (Bước 3 — Independent Review)
- **🤖 Model khuyến nghị trên Antigravity (Đảo sang Tier S họ đối lập để chống mù điểm)**:
  - Nếu Bước 2 vừa chạy bằng **Claude** ➔ Đổi sang **Gemini 4 Carbon (High)** *(flagship mạnh nhất họ Gemini 4, cực sắc về bảo mật RBAC, tài nguyên I/O & concurrency Virtual Threads)*.
  - Nếu Bước 2 vừa chạy bằng **Gemini** (`Orchestrator` / `Argon` / `Carbon`) ➔ Đổi sang **Claude Opus 5.5 (High)** *(flagship mạnh nhất họ Claude, cực kỹ khi đối chiếu từng dòng Spec & edge cases)*.
- **💡 Mẹo ngữ cảnh**: Khuyến khích mở một **New Chat sạch** (hoặc đổi Model ngay tại đây) rồi dán câu lệnh dưới để Reviewer hoàn toàn khách quan.

Copy câu lệnh dưới đây và gửi vào ô chat:
> `Thực hiện Bước 3 (Independent Review) cho task <TICKET_ID> theo .ai-workflow/prompts/03-review.md. Khởi chạy các subagents độc lập (Standards, Spec, Ponytail) đối chiếu jira.md, AGENTS.md và domain-review.md, lưu kết quả vào review-01.md.`
```

---

## Hard Rules
- Nhiệm vụ lượt này kết thúc ngay tại `implementation-report.md`.
- Không chạy `git commit`, không `git push`.
- Không tự chuyển sang Bước 3 (cấm tạo `review-01.md`, `fix-report.md`, `final-review.md`, `precommit.md`).
- Sau khi in hộp chuyển tiếp: **DỪNG LẠI NGAY LẬP TỨC (END TURN)**.
