---
name: ai-workflow
description: Use when the user runs /ai-workflow, starts or continues a DA-* Jira ticket ("Làm task DA-XX"), or sends a workflow step prompt referencing plan.md, 01-plan.md, 02-implement.md, 03-review.md, 04-fix.md, 05-final-review.md, or 06-precommit.md.
---

# DocuMind AI — AI Workflow Dispatcher (v3.0)

Router skill điều phối quy trình 6 bước phát triển theo Jira ticket (`DA-*`) của dự án **DocuMind AI**.

> 🛑 **QUY TẮC BẤT DI BẤT DỊCH — CHẠY TỪNG BƯỚC MỘT (STRICT STEP-BY-STEP):**
> - Mỗi tin nhắn của người dùng **CHỈ ĐƯỢC THỰC THI ĐÚNG 1 BƯỚC DUY NHẤT** trong 6 bước bên dưới.
> - **NGHIÊM CẤM** chạy một mạch hết cả task hoặc tự ý nối Bước 1 sang Bước 2, Bước 2 sang Bước 3, Bước 3 sang Bước 4... trong cùng một lượt!
> - Khi đang chạy `/ai-workflow`, **KHÔNG** gọi `brainstorming`, `writing-plans`, `executing-plans`, hay `subagent-driven-development` của plugin `superpowers` (để tránh tự động chạy xuyên bước).
> - Làm xong đúng 1 bước ➔ ghi file báo cáo của bước đó ➔ in hộp gợi ý bước tiếp theo + Model khuyên chọn ➔ **DỪNG HẲN LƯỢT NGAY LẬP TỨC (END TURN)** chờ người dùng kiểm tra và ra lệnh bước kế tiếp.

## 1. Nạp quy tắc nền (Bắt buộc mọi lượt)

Trước khi thực hiện bất kỳ thao tác nào:
1. Đọc [`.ai-workflow/prompts/00-shared-rules.md`](../../../.ai-workflow/prompts/00-shared-rules.md) để nắm luật **1 Lượt = 1 Bước (Hard Stop)**, chuẩn tạo nhánh từ `main`, quy tắc lấy diff đầy đủ (`merge-base` + untracked files), lệnh kiểm chứng Maven Wrapper / Next.js / Playwright `--headed`, và mẫu **Hộp Gợi Ý Chuyển Tiếp kèm Model Antigravity**.
2. Tuân thủ tuyệt đối mọi ràng buộc bảo mật & RAG invariants trong [`AGENTS.md`](../../../AGENTS.md).

## 2. Định tuyến Bước thực thi (Chọn đúng 1 bước cho lượt hiện tại)

Xác định bước người dùng đang gọi dựa trên câu lệnh (hoặc trạng thái các file đã có trong `.ai-workflow/tasks/<TICKET_ID>/` nếu người dùng chỉ gõ `/ai-workflow DA-XX`):

| Tín hiệu từ người dùng / Trạng thái file | Bước cần chạy | File hướng dẫn bắt buộc đọc | Output duy nhất của lượt |
|---|---|---|---|
| Khởi động ticket mới (`/ai-workflow DA-XX`, `"Làm task DA-XX"`, chưa có `plan.md`) | **Bước 1: Plan** | [`.ai-workflow/prompts/01-plan.md`](../../../.ai-workflow/prompts/01-plan.md) | `jira.md` + chuyển Jira sang **`In Progress`** + nhánh Git mới từ `main` + `plan.md` |
| Duyệt plan / Yêu cầu code (`"Duyệt plan..."`, `02-implement.md`) | **Bước 2: Implement** | [`.ai-workflow/prompts/02-implement.md`](../../../.ai-workflow/prompts/02-implement.md) | Code + Tests (Testcontainers/Unit) + `implementation-report.md` |
| Yêu cầu review độc lập (`"Thực hiện Bước 3..."`, `03-review.md`) | **Bước 3: Review** | [`.ai-workflow/prompts/03-review.md`](../../../.ai-workflow/prompts/03-review.md) + [`domain-review.md`](../../../.ai-workflow/rules/domain-review.md) | Chuyển Jira sang **`Verify / Test`** + `review-01.md` (tuyệt đối không sửa code) |
| Yêu cầu sửa findings (`"Thực hiện Bước 4..."`, `"Fix các findings..."`, `04-fix.md`) | **Bước 4: Fix** | [`.ai-workflow/prompts/04-fix.md`](../../../.ai-workflow/prompts/04-fix.md) | Code vá lỗi + Regression tests + `fix-report.md` |
| Yêu cầu tái thẩm định (`"Thực hiện Bước 5..."`, `05-final-review.md`) | **Bước 5: Final Review** | [`.ai-workflow/prompts/05-final-review.md`](../../../.ai-workflow/prompts/05-final-review.md) + [`domain-review.md`](../../../.ai-workflow/rules/domain-review.md) | `final-review.md` (tuyệt đối không sửa code) |
| Yêu cầu pre-commit / tạo PR (`"Thực hiện Bước 6..."`, `06-precommit.md`) | **Bước 6: Pre-Commit** | [`.ai-workflow/prompts/06-precommit.md`](../../../.ai-workflow/prompts/06-precommit.md) | `precommit.md` + Jira comment + chuyển Jira sang **`Done`** + khối lệnh Git cho Human |

## 3. Tiêu chuẩn hoàn tất lượt (Completion Criterion)

Mỗi lượt kết thúc ngay khi:
1. Artifact tương ứng của bước đó đã được lưu vào `.ai-workflow/tasks/<TICKET_ID>/`.
2. Đã in **Hộp Gợi Ý Bước Tiếp Theo** (gồm khuyến nghị **Model trên Antigravity** và câu lệnh copy-paste tự thân) ở cuối câu trả lời.
3. **Dừng lượt ngay lập tức (END TURN)** — chờ người dùng duyệt cổng kiểm soát kế tiếp.
