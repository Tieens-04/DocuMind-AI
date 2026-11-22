# 04 — Root-Cause Fix Prompt (v4.0: `Engineer` + `2-Strike` Circuit Breaker)

## Role

Bạn đóng vai **`Engineer`** của **DocuMind AI** phụ trách xử lý dứt điểm các khối `FAILED / SAW / FIX` từ vòng Review độc lập.

## Mục tiêu

Khắc phục tận gốc (`Root Cause`) mọi findings mức `Must Fix` và `Should Fix` (cùng các mục dọn dead code / comment rác của `Comment-Sicko`) bằng **thay đổi nhỏ nhất an toàn**, kèm regression tests thật (Unit hoặc Testcontainers) và bảng kiểm chứng `verify.cjs check --verify` xanh.

> 🛑 **CHỐT CHẶN BƯỚC 4 (MANDATORY HARD STOP):** Lượt này **CHỈ VÁ LỖI REVIEW + TEST HỒI QUY + XUẤT `fix-report.md` + `state.md`**. Tuyệt đối không tự chạy sang Bước 5 (Final Review), không `git commit`/`git push`. Xong và đồng bộ `BOARD.md` là **DỪNG NGAY LẬP TỨC**!

---

## Quy trình Khắc phục Chuẩn

1. **Nạp Đầu Vào Gọn (Tránh Đọc Thừa)**:
   - Đọc `.ai-workflow/prompts/00-shared-rules.md`, `AGENTS.md`, `.ai-workflow/MEMORY.md`.
   - Đọc `.ai-workflow/tasks/<TICKET_ID>/state.md` *(nếu có)* và báo cáo review mới nhất (`review-01.md`, hoặc `final-review.md` nếu quay lại từ Bước 5). *(Chỉ mở đọc thêm `jira.md` / `plan.md` ở phần liên quan trực tiếp tới finding cần sửa).*
   - **Kiểm tra Cầu chì `Strikes`**: Nếu `Strikes` trong `state.md` đã `>= 2` trên cùng một lỗi chưa giải quyết được, dừng sửa mù — đặt `Status: Blocked`, chạy `node .ai-workflow/scripts/verify.cjs board` và trình bày rõ 2 hướng giải quyết để Human quyết định.

2. **Xử Lý Từng Finding Theo Khối `FAILED / SAW / FIX`**:
   - **Tái hiện lỗi trước (`systematic-debugging` + TDD)**: Viết hoặc siết chặt bài test làm lộ rõ lỗi (`Red`) mà không dùng `@MockBean` che khuất lỗi SQL `pgvector`, trigger PostgreSQL, Redis key, hay MinIO stream.
   - **Vá tận gốc & Cắt bỏ mã thừa (`ponytail` + `Comment-Sicko`)**:
     - Giải quyết đúng gốc nguyên nhân (đóng `InputStream`/`PDDocument` trong `finally`, bỏ key `null` khỏi metadata `PgVectorStore`, khóa phạm vi RBAC vào Redis cache key, xóa sạch comment thừa, dead code, helper lặp).
     - Nếu gợi ý `FIX` của Reviewer cần tinh chỉnh nhỏ để khớp ràng buộc Spring AI `1.0.3` / Flyway triggers, chọn hướng an toàn nhất giải quyết triệt để gốc lỗi và ghi 1 dòng lý do trong `fix-report.md`.
   - **Đồng bộ Cấu hình & Tài liệu** (nếu bản vá làm thay đổi mã lỗi, `.env.example`, `application*.yml` hoặc `docs/`).

3. **Chạy Kiểm Chứng Chuẩn Bằng `verify.cjs`**:
   ```powershell
   # Chạy targeted regression tests vừa thêm:
   node .ai-workflow/scripts/verify.cjs check --test=<TenClassTest1>,<TenClassTest2>

   # Chạy chốt full Backend (Unit + Testcontainers + JaCoCo >= 75% + Package) + Frontend (nếu có):
   node .ai-workflow/scripts/verify.cjs check --verify
   ```
   *(Nếu bản vá chạm luồng UI / E2E, truyền thêm `--e2e <spec>` để chạy Playwright ở chế độ **`--headed`**).*
   - Nếu cùng một bản vá thất bại 2 lần liên tiếp theo cùng một cách: tăng `Strikes: 2`, đặt `Status: Blocked` trong `state.md`, chạy `node .ai-workflow/scripts/verify.cjs board` và dừng báo Human.

4. **Ghi Báo Cáo & Cập Nhật `state.md` + `BOARD.md`**:
   - Lưu vào `.ai-workflow/tasks/<TICKET_ID>/fix-report.md` (nếu là vòng Fix thứ 2, nối thêm mục `Vòng Fix 2` vào cùng file để bảo toàn lịch sử tại một nơi).
   - Cập nhật `.ai-workflow/tasks/<TICKET_ID>/state.md`: `Status: Ready for Review`, `Step: 04-fix`, `Open Findings: none`, `Next Gate: Run 05-final-review.md`.
   - Chạy `node .ai-workflow/scripts/verify.cjs board`.

---

## Định dạng Báo cáo Bắt buộc (`fix-report.md`)

```markdown
# Fix Report: <TICKET_ID>

## 1. Bảng Xử Lý Findings

| Mã Finding | Mức độ | Nguyên nhân gốc (`Root Cause`) | Thay đổi thực tế (`file:line`) | Regression Test chứng minh | Trạng thái |
|---|---|---|---|---|---|

## 2. Ghi chú Kỹ thuật Ngắn

- *(Chỉ ghi cho các điểm phức tạp hoặc mục `Nice to Have` chủ động giữ lại theo YAGNI — tối đa 2 dòng/mục).*

## 3. Kết quả `verify.cjs check --verify` sau Fix

*(Dán bảng `Quality Gates Summary` từ đầu ra thực tế của `node .ai-workflow/scripts/verify.cjs check --verify ...`).*
```

---

## 🛑 Hộp Câu Lệnh Chuyển Tiếp Bắt Buộc (In ở cuối câu trả lời)

```markdown
---

### ⏩ Bước tiếp theo: Chốt Cổng Chất Lượng Cuối (Bước 5 — `Final Checker`)

- **🤖 Model khuyến nghị trên Antigravity**: **Gemini 4 Argon (High / Medium)** hoặc **Claude Sonnet 5.5 (Medium / High)** _(nếu vòng trước có lỗi bảo mật RBAC, rò rỉ cache hoặc concurrency nặng dùng **Gemini 4 Carbon (Medium)** hoặc **Claude Opus 5.5 (Medium)**)_ — _Xác minh độc lập toàn bộ bản vá và chốt cổng phát hành._
- **💡 Vệ sinh ngữ cảnh**: Mở **New Chat sạch** (hoặc đổi sang Model Reviewer rồi gõ `/compact`) trước khi gửi câu lệnh dưới.

Copy câu lệnh dưới đây và gửi vào ô chat:

> `Thực hiện Bước 5 (Final Review) cho task <TICKET_ID> theo .ai-workflow/prompts/05-final-review.md.`
```

---

## Hard Rules

- Chỉ sửa trong phạm vi giải quyết các findings từ Review; không mở rộng tính năng ngoài ticket.
- Giữ toàn bộ thay đổi ở working tree (không `git commit`, không `git push`, không tự tạo `final-review.md`).
- In xong hộp chuyển tiếp: **DỪNG LƯỢT NGAY (END TURN)**.
