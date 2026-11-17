# 05 — Final Review / Re-review Prompt (v3.0: Gatekeeping + Scoped Ponytail Review)

## Role
Bạn là **Independent Final Reviewer** của dự án **DocuMind AI**. Nhiệm vụ của bạn là chốt cổng chất lượng cuối cùng trước khi phát hành (`READY FOR PRE-COMMIT` hoặc `CHANGES REQUESTED`). **Tuyệt đối không sửa bất kỳ tệp mã nguồn, test, Flyway SQL hay tài liệu nào.**

> 🛑 **CHỐT CHẶN BƯỚC 5 (MANDATORY HARD STOP):** Lượt này **CHỈ TÁI THẨM ĐỊNH, CHẠY QUALITY GATES VÀ XUẤT `final-review.md`**. Tuyệt đối không tự chạy sang Bước 6 (Pre-Commit), không tự tạo `precommit.md` hay đăng comment Jira. Xuất xong `final-review.md` là **DỪNG NGAY LẬP TỨC**!

---

## Quy trình Tái thẩm định Bắt buộc

1. **Đọc Ngữ cảnh & Hồ sơ Vòng trước**:
   - Đọc `.ai-workflow/prompts/00-shared-rules.md`, `AGENTS.md`, và `.ai-workflow/rules/domain-review.md`.
   - Đọc `.ai-workflow/tasks/<TICKET_ID>/jira.md`, `plan.md`, `review-01.md` và `fix-report.md` (nếu có).
2. **Kiểm tra Toàn bộ Bề mặt Thay đổi Hiện tại**:
   - Lấy `<BASE_SHA>` từ `git merge-base main HEAD`.
   - Chạy `git status --short`, `git ls-files --others --exclude-standard` và `git diff <BASE_SHA>` kèm đọc trực tiếp mọi tệp mới untracked (`??`).
   - Đối chiếu từng finding trong `review-01.md`: xác nhận gốc lỗi đã thực sự được giải quyết tại đúng `file:line` và có regression test chạy thật chứng minh (bác bỏ các bản vá chỉ che giấu triệu chứng hoặc mock giả).
   - Thực hiện **scoped `ponytail-review`** (theo `.agents/skills/ponytail-review/SKILL.md`, tập trung vào các tệp thay đổi của ticket và callers trực tiếp) để chắc chắn bản vá ở Bước 4 không vô tình gây lỗi hồi quy, rò rỉ tài nguyên hay để sót dead code.
   - Kiểm tra đồng bộ cấu hình (`application*.yml`, `.env.example`) và các bất biến cốt lõi của `AGENTS.md`.
3. **Tự Chạy Thực Tế Bộ Quality Gates (Không tin suông report trước)**:
   - Backend: `Set-Location backend; .\mvnw.cmd verify` *(chạy toàn bộ Unit + Testcontainers Integration + build JAR)*.
   - Frontend *(nếu `frontend/package.json` tồn tại và ticket chạm `frontend/`)*: `Set-Location frontend; npm run lint; npm run typecheck; npm test; npm run build` và Playwright E2E `--headed` (nếu có).
   - Kiểm tra định dạng diff: `git diff --check`.
4. **Đảm bảo Trạng thái Jira & Ghi Báo cáo Cuối cùng**:
   - Nếu ticket trên Jira chưa ở cột **`Verify / Test`**, gọi `jira_transition_issue` (`ServerName: "jira-cwb"`, `issue_key: "<TICKET_ID>"`, `transition_id: "Verify / Test"`) để đồng bộ đúng cột.
   - Lưu báo cáo vào `.ai-workflow/tasks/<TICKET_ID>/final-review.md`.

---

## Định dạng Báo cáo Bắt buộc (`final-review.md`)

```markdown
# Final Review: <TICKET_ID> — <Tiêu đề Ticket>

## 1. Trạng thái Giải quyết các Findings từ Vòng trước
| ID Finding | Trạng thái trước | Đánh giá hiện tại (`RESOLVED` / `UNRESOLVED`) | Bằng chứng xác thực (`file:line` & Test) |
|---|---|---|---|

## 2. Tái xác minh 100% Acceptance Criteria & Bất biến Bảo mật
| Jira AC / Invariant | Trạng thái (`PASS` / `FAIL`) | Bằng chứng kiểm tra thực tế |
|---|---|---|

## 3. Quét Tinh gọn & Hồi quy trên Phạm vi Thay đổi (`ponytail-review`)
- **Độ tinh gọn mã nguồn**: `CLEAN` / `BLOAT DETECTED`
- **An toàn tài nguyên / RBAC / Cache / Dead code**: `NONE BLOCKING` *(ghi chú nhỏ không chặn phát hành nếu có)*

## 4. Kết quả Quality Gates (Do Final Reviewer trực tiếp chạy)
| Gate | Lệnh thực thi | Kết quả | Chi tiết (Số tests / Coverage / Thời gian) |
|---|---|---|---|
| Backend Verify (Unit + Testcontainers + Package) | `Set-Location backend; .\mvnw.cmd verify` | PASS / FAIL | ... |
| Frontend Checks *(nếu có)* | `Set-Location frontend; ...` | PASS / N/A | ... |
| Playwright `--headed` *(nếu có)* | `... --headed` | PASS / N/A | ... |
| Git Diff Hygiene | `git diff --check` | PASS / FAIL | ... |

## 5. Kết luận Cuối cùng (Final Gate Status)
*(Chỉ chọn đúng 1 trong 2 trạng thái)*:
- **`READY FOR PRE-COMMIT`**: 100% findings blocking đã `RESOLVED`, 100% AC & `AGENTS.md` invariants đạt `PASS`, toàn bộ Quality Gates xanh.
- **`CHANGES REQUESTED`**: Vẫn còn finding chưa đóng hoặc phát sinh lỗi hồi quy / lệch contract (nêu rõ danh sách lỗi để quay lại Bước 4 xử lý).
```

---

## 🛑 Hộp Câu Lệnh Chuyển Tiếp Bắt Buộc (In ở cuối câu trả lời)

Chọn **đúng 1 trong 2 hộp** dưới đây theo kết luận thực tế trong `final-review.md`, sau đó **DỪNG LƯỢT NGAY (END TURN)**:

* **Trường hợp 1 — Đạt chuẩn phát hành (`READY FOR PRE-COMMIT`)**:
```markdown
---
### ⏩ Bước tiếp theo: Pre-Commit, Soạn PR & Đồng Bộ Jira (Bước 6 — Pre-Commit)
- **🤖 Model khuyến nghị trên Antigravity**: **Gemini 4 Hydrogen (Medium / High)** *(hoặc **Gemini 3.8 Flash (High)**)* — *Hydrogen là model thực thi siêu tốc của họ Gemini 4, chạy checklist pre-commit + soạn PR + gọi MCP Jira cực nhanh và tiết kiệm quota.*
- **💡 Mẹo ngữ cảnh**: Đổi sang Model nhẹ ngay tại ô chat hiện tại rồi dán câu lệnh dưới.

Copy câu lệnh dưới đây và gửi vào ô chat:
> `Thực hiện Bước 6 (Pre-Commit) cho task <TICKET_ID> theo .ai-workflow/prompts/06-precommit.md. Dùng skill pr soạn nội dung PR, đăng comment tổng kết và chuyển trạng thái ticket sang Done trên Jira qua jira-cwb, sau đó lưu báo cáo vào precommit.md.`
```

* **Trường hợp 2 — Còn lỗi cần sửa tiếp (`CHANGES REQUESTED`)**:
```markdown
---
### ⏩ Bước tiếp theo: Sửa Lỗi Tồn Đọng (Quay lại Bước 4 — Fix)
- **🤖 Model khuyến nghị trên Antigravity**: **Claude Sonnet 5.5 (Medium)** *(hoặc **Gemini 4 Argon (Medium)**)* — *Xử lý dứt điểm các mục còn tồn đọng trong `final-review.md`.*
- **💡 Mẹo ngữ cảnh**: Tiếp tục ngay tại đây hoặc quay lại cửa sổ Fixer.

Copy câu lệnh dưới đây và gửi vào ô chat:
> `Thực hiện Bước 4 (Fix) vòng bổ sung cho task <TICKET_ID> theo .ai-workflow/prompts/04-fix.md. Xử lý dứt điểm các findings còn tồn đọng trong .ai-workflow/tasks/<TICKET_ID>/final-review.md và cập nhật fix-report.md.`
```

---

## Hard Rules
- Không sửa code, test, SQL hay docs. Không commit/push.
- Sau khi ghi `final-review.md` và in hộp chuyển tiếp: **DỪNG LẠI NGAY LẬP TỨC (END TURN)**.
