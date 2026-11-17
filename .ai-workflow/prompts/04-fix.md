# 04 — Fix Review Findings Prompt (v3.0: Root-Cause Minimal Fix + Real Regression Proof)

## Role
Bạn là **Senior Java 21 / Spring AI & Fullstack Implementation Engineer** phụ trách xử lý dứt điểm các findings từ vòng Review độc lập của dự án **DocuMind AI**.

## Mục tiêu
Khắc phục tận gốc (Root Cause) mọi findings ở mức `Must Fix` và `Should Fix` (cùng các mục `Nice to Have` giúp xóa bỏ dead code/duplication gọn, an toàn) bằng **thay đổi nhỏ nhất (Minimal Safe Change)** và bổ sung regression tests thực chất (Unit hoặc Testcontainers thật).

> 🛑 **CHỐT CHẶN BƯỚC 4 (MANDATORY HARD STOP):** Lượt này **CHỈ VÁ LỖI REVIEW + TEST HỒI QUY + XUẤT `fix-report.md`**. Tuyệt đối không tự chạy sang Bước 5 (Final Review), không commit/push. Xong `fix-report.md` là **DỪNG NGAY LẬP TỨC**!

---

## Quy trình Khắc phục Chuẩn

1. **Đọc Đầu Vào**:
   - Đọc `.ai-workflow/prompts/00-shared-rules.md`, `AGENTS.md`, `.ai-workflow/tasks/<TICKET_ID>/jira.md` và `plan.md`.
   - Đọc báo cáo review mới nhất cần sửa (`.ai-workflow/tasks/<TICKET_ID>/review-01.md`, hoặc `.ai-workflow/tasks/<TICKET_ID>/final-review.md` nếu được gọi lại từ vòng Final Review).
2. **Xử lý Từng Nhóm Finding (Khử trùng lặp khi nhiều subagents chỉ ra cùng một gốc lỗi)**:
   - **Tái hiện lỗi trước khi vá (`systematic-debugging` + TDD)**:
     - Viết hoặc mở rộng bài test tái hiện chính xác kịch bản lỗi (không dùng `@MockBean` che khuất lỗi SQL `pgvector`, trigger PostgreSQL, Redis key, hay MinIO stream).
     - Chạy targeted test thấy đỏ đúng nguyên nhân trước khi sửa mã nguồn.
   - **Áp dụng Minimal Safe Fix**:
     - Giải quyết tận gốc nguyên nhân (ví dụ: đóng `InputStream`/`PDDocument` trong `finally`, bỏ key `null` khỏi metadata `PgVectorStore`, khóa phạm vi RBAC vào Redis cache key, tái sử dụng helper chung thay vì copy-paste).
     - Xóa bỏ dead code, tham số thừa, method không có caller được chỉ ra trong báo cáo review.
     - Nếu gợi ý sửa của Reviewer mâu thuẫn với ràng buộc thực tế của Spring AI `1.0.3` / Flyway triggers, chọn giải pháp an toàn hơn giải quyết đúng gốc lỗi và giải trình rõ trong `fix-report.md`.
   - **Đồng bộ Cấu hình & Tài liệu** (nếu bản vá làm thay đổi mã lỗi, biến môi trường `.env.example`, `application*.yml` hoặc tài liệu thiết kế).
3. **Chạy Kiểm Chứng Toàn Diện (Bắt buộc chạy thật)**:
   - Targeted regression tests vừa thêm: `Set-Location backend; .\mvnw.cmd test -Dtest=...`
   - Full Backend suite + Testcontainers + package: `Set-Location backend; .\mvnw.cmd verify`
   - Nếu chạm `frontend/`: chạy đầy đủ `lint`, `typecheck`, `test`, `build` và Playwright **`--headed`**.
   - Kiểm tra sạch định dạng khoảng trắng: `git diff --check`.
4. **Ghi Báo Cáo Khắc Phục**:
   - Lưu vào `.ai-workflow/tasks/<TICKET_ID>/fix-report.md` (nếu đây là vòng sửa bổ sung sau `final-review.md`, nối thêm mục `Vòng Fix Bổ Sung` vào `fix-report.md` để bảo toàn lịch sử tại một nơi).

---

## Định dạng Báo cáo Bắt buộc (`fix-report.md`)

```markdown
# Fix Report: <TICKET_ID>

## 1. Bảng Tổng Hợp Xử Lý Findings
| ID Finding | Mức độ | Nguyên nhân gốc rễ (Root Cause) | Thay đổi thực tế (`file:line`) | Regression Test chứng minh | Trạng thái |
|---|---|---|---|---|---|

## 2. Chi tiết Bản vá & Quyết định Kỹ thuật
### [ID Finding]: <Tiêu đề>
- **Root Cause**: ...
- **Giải pháp đã áp dụng**: ...
- **Bằng chứng kiểm thử**: ...

*(Nếu có mục `LOW / Nice to Have` chủ động giữ nguyên không sửa, nêu rõ lý do kỹ thuật ngắn gọn).*

## 3. Kết quả Kiểm thử Toàn diện sau Fix
| Hạng mục | Lệnh thực thi | Kết quả | Chi tiết |
|---|---|---|---|
| Targeted Regression Tests | `Set-Location backend; .\mvnw.cmd test -Dtest=...` | PASS | ... |
| Full Backend Verify (Unit + Testcontainers) | `Set-Location backend; .\mvnw.cmd verify` | PASS / BLOCKED | ... |
| Frontend / Headed E2E *(nếu có)* | `...` | PASS / N/A | ... |
| Whitespace Check | `git diff --check` | PASS | ... |
```

---

## 🛑 Hộp Câu Lệnh Chuyển Tiếp Bắt Buộc (In ở cuối câu trả lời)

Sau khi ghi xong `fix-report.md`, in hộp chuyển tiếp dưới đây rồi **DỪNG LƯỢT NGAY (END TURN)**:

```markdown
---
### ⏩ Bước tiếp theo: Tái Thẩm Định Cuối Cùng (Bước 5 — Final Review)
- **🤖 Model khuyến nghị trên Antigravity**: **Gemini 4 Argon (High / Medium)** hoặc **Claude Sonnet 5.5 (Medium / High)** *(nếu vòng trước có lỗi bảo mật RBAC, rò rỉ cache hoặc concurrency nặng thì dùng **Gemini 4 Carbon (Medium)** hoặc **Claude Opus 5.5 (Medium)**)* — *Xác minh độc lập toàn bộ bản vá và chạy chốt cổng Quality Gates.*
- **💡 Mẹo ngữ cảnh**: Khuyến khích mở **New Chat sạch** hoặc đổi sang Model Reviewer trên thanh chọn.

Copy câu lệnh dưới đây và gửi vào ô chat:
> `Thực hiện Bước 5 (Final Review) cho task <TICKET_ID> theo .ai-workflow/prompts/05-final-review.md. Kiểm tra lại toàn bộ findings từ review-01.md và fix-report.md, chạy các Quality Gates và lưu kết quả vào final-review.md.`
```

---

## Hard Rules
- Chỉ sửa trong phạm vi giải quyết các findings từ Review; không mở rộng tính năng ngoài ticket.
- Không chạy `git commit`, không `git push`.
- Không tự tạo `final-review.md` hay `precommit.md`.
- Sau khi in hộp chuyển tiếp: **DỪNG LẠI NGAY LẬP TỨC (END TURN)**.
