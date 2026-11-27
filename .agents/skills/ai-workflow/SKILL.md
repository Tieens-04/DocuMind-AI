---
name: ai-workflow
description: >-
  DocuMind AI 6-step Jira engineering pipeline (.ai-workflow v4.0: Chief of Staff router, Researcher/Engineer/Reviewer roles, state.md handoff, verify.cjs proof CLI, BOARD.md & MEMORY.md). Activate when the user runs /ai-workflow, "/ai-workflow board", starts or continues a DA-* Jira ticket ("Làm task DA-XX"), or sends a workflow step prompt referencing plan.md, 01-plan.md, 02-implement.md, 03-review.md, 04-fix.md, 05-final-review.md, or 06-precommit.md.
---

# DocuMind AI — AI Workflow Chief of Staff (v4.0)

Router skill (`Chief of Staff`) cho quy trình 6 bước phát triển theo Jira ticket (`DA-*`) của **DocuMind AI** — tích hợp kiến trúc **4-Role Specialist (`pstack`)** và cơ chế **Token Economy (`Grok Bot`)**.

> 🛑 **QUY TẮC BẤT DI BẤT DỊCH — CHẠY TỪNG BƯỚC MỘT (STRICT STEP-BY-STEP):**
> - Mỗi tin nhắn của người dùng **CHỈ ĐƯỢC THỰC THI ĐÚNG 1 BƯỚC DUY NHẤT** trong bảng bên dưới.
> - **NGHIÊM CẤM** chạy một mạch hết cả task hoặc tự ý nối Bước 1 sang Bước 2, Bước 2 sang Bước 3, Bước 3 sang Bước 4... trong cùng một lượt!
> - Khi đang chạy `/ai-workflow`, **KHÔNG** gọi `brainstorming`, `writing-plans`, `executing-plans`, hay `subagent-driven-development` của plugin `superpowers` (để tránh tự động chạy xuyên bước).
> - Làm xong đúng 1 bước ➔ ghi artifact + cập nhật `state.md` + chạy `node .ai-workflow/scripts/verify.cjs board` ➔ in Hộp Chuyển Tiếp kèm Model khuyên chọn ➔ **DỪNG HẲN LƯỢT NGAY LẬP TỨC (END TURN)**.

## 1. Nạp quy tắc nền (Bắt buộc trước mọi hành động)

1. Đọc [`.ai-workflow/prompts/00-shared-rules.md`](../../../.ai-workflow/prompts/00-shared-rules.md) để nắm luật **1 Lượt = 1 Bước**, gói bàn giao gọn `state.md` (≤ 20 dòng), công cụ kiểm chứng `node .ai-workflow/scripts/verify.cjs`, cầu chì **2-Strike Blocked**, vòng đời trạng thái Jira `jira-cwb`, và **Hộp Chuyển Tiếp**.
2. Tuân thủ tuyệt đối mọi ràng buộc bảo mật & RAG invariants trong [`AGENTS.md`](../../../AGENTS.md).

## 2. Định tuyến Lượt thực thi (Chọn đúng 1 nhánh cho lượt hiện tại)

| Tín hiệu từ người dùng / Trạng thái `tasks/<TICKET_ID>/` | Vai trò & Bước kích hoạt | Tài liệu đọc thực thi | Đầu ra duy nhất của lượt |
|---|---|---|---|
| `/ai-workflow board` hoặc `/ai-workflow status` | **Chief of Staff: Board Sweep** | Chạy `node .ai-workflow/scripts/verify.cjs board` | Cập nhật `.ai-workflow/BOARD.md` + tóm tắt ≤ 8 dòng các ticket đang mở/bị chặn |
| Ticket mới (`/ai-workflow DA-XX`, `"Làm task DA-XX"`, chưa có `plan.md`) | **Bước 1: Goal & Plan** (`Chief` + `Researcher`) | [`01-plan.md`](../../../.ai-workflow/prompts/01-plan.md) + [`MEMORY.md`](../../../.ai-workflow/MEMORY.md) | `jira.md` + chuyển Jira sang **`In Progress`** + nhánh Git từ `main` + `plan.md` + `state.md` + sync `BOARD.md` |
| Duyệt plan / Yêu cầu code (`"Duyệt plan..."`, `02-implement.md`) | **Bước 2: Build** (`Engineer`) | [`02-implement.md`](../../../.ai-workflow/prompts/02-implement.md) + [`MEMORY.md`](../../../.ai-workflow/MEMORY.md) | Code + Tests (Testcontainers/Unit) + `verify.cjs check --verify` + `implementation-report.md` + `state.md` |
| Yêu cầu review độc lập (`"Thực hiện Bước 3..."`, `03-review.md`) | **Bước 3: Review** (`Checker` + 3 Subagents) | [`03-review.md`](../../../.ai-workflow/prompts/03-review.md) + [`domain-review.md`](../../../.ai-workflow/rules/domain-review.md) | Chuyển Jira sang **`Verify / Test`** + `review-01.md` (`FAILED/SAW/FIX`, không sửa code) + `state.md` |
| Yêu cầu sửa findings (`"Thực hiện Bước 4..."`, `04-fix.md`) | **Bước 4: Fix** (`Engineer`) | [`04-fix.md`](../../../.ai-workflow/prompts/04-fix.md) | Code vá gốc lỗi + Regression tests + `verify.cjs check --verify` + `fix-report.md` + `state.md` |
| Yêu cầu tái thẩm định (`"Thực hiện Bước 5..."`, `05-final-review.md`) | **Bước 5: Final Gate** (`Checker`) | [`05-final-review.md`](../../../.ai-workflow/prompts/05-final-review.md) + [`domain-review.md`](../../../.ai-workflow/rules/domain-review.md) | `final-review.md` (kèm Prevention Audit, không sửa code) + `state.md` |
| Yêu cầu bàn giao / phát hành (`"Thực hiện Bước 6..."`, `06-precommit.md`) | **Bước 6: Deliver** (`6.1 Pre-commit` ➔ `6.2 Commit` ➔ `6.3 Push` ➔ `6.4 Tạo PR` ➔ `6.5 Đóng Jira "Done"`) | [`06-precommit.md`](../../../.ai-workflow/prompts/06-precommit.md) + [`MEMORY.md`](../../../.ai-workflow/MEMORY.md) | `precommit.md` + `MEMORY.md` (nếu có) + Commit + Push + GitHub PR + Jira comment & chuyển **`Done`** + `state.md` + `BOARD.md` |

*(Nếu người dùng chỉ gõ `/ai-workflow DA-XX` cho một ticket đang làm dở, đọc `.ai-workflow/tasks/<TICKET_ID>/state.md` — hoặc chạy `node .ai-workflow/scripts/verify.cjs board` nếu chưa có `state.md` — để báo trạng thái hiện tại và in ngay Hộp Chuyển Tiếp cho bước kế tiếp).*

## 3. Tiêu chuẩn hoàn tất lượt (Completion Criterion)

Lượt hiện tại hoàn tất ngay khi:
1. Artifact bắt buộc của bước đó cùng `state.md` đã được ghi vào `.ai-workflow/tasks/<TICKET_ID>/`, và `node .ai-workflow/scripts/verify.cjs board` đã đồng bộ `.ai-workflow/BOARD.md`.
2. Đã in **Hộp Gợi Ý Bước Tiếp Theo** (kèm khuyến nghị Model Antigravity, mẹo cắt lịch sử chat và câu lệnh copy-paste).
3. **Kết thúc lượt ngay lập tức (END TURN)** — dừng tại cổng kiểm soát để chờ người dùng duyệt.
