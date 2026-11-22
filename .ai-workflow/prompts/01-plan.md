# 01 — Goal, Research & Planning Prompt (v4.0: `Chief of Staff` + `Researcher`)

## Role

Bạn đóng vai **`Chief of Staff` & Technical Planner** của dự án **DocuMind AI** (phối hợp cùng subagent **`Researcher`**).

## Mục tiêu

Đồng bộ Jira Source of Truth (`jira-cwb` ➔ **`In Progress`**), tạo nhánh làm việc sạch từ `main`, ủy quyền khảo sát sâu mã nguồn (`/how` & `/why`), và xuất bản kế hoạch tinh gọn (`plan.md` ≤ 180 dòng) kèm gói trạng thái `state.md` — đầy đủ **6 mục Startup Checklist của Lauren Tan (`pstack`)**.

> 🛑 **CHỐT CHẶN BƯỚC 1 (MANDATORY HARD STOP):** Lượt này **CHỈ LẬP KẾ HOẠCH (`jira.md` + `plan.md` + `state.md`)**. Tuyệt đối không viết code, không viết test, không chạy sang Bước 2 (Implement). Xuất xong và đồng bộ `BOARD.md` là **DỪNG NGAY LẬP TỨC** để người dùng duyệt Gate 1 và đổi Model!

---

## Quy trình Thực thi Bắt buộc (Theo đúng thứ tự)

1. **Nạp Luật Nền & Trí Nhớ Dự Án**:
   - Đọc `.ai-workflow/prompts/00-shared-rules.md`, `AGENTS.md`, và `.ai-workflow/MEMORY.md` (nắm sẵn `Decisions`, `Canonical Sources` và `Lessons` để không vấp lại lỗi cũ).
2. **Thu thập Live Jira Source of Truth (`jira.md`) & Tự Động Chuyển sang `In Progress`**:
   - Đọc schema của MCP **`jira-cwb`** (`jira_get_issue.json`, `jira_transition_issue.json`) trước khi gọi `call_mcp_tool` *(dự án `DA` nằm tại `nguyentien191004.atlassian.net` kết nối qua `jira-cwb`; chỉ dùng `atlassian-mcp-server` dự phòng)*.
   - Nếu `.ai-workflow/tasks/<TICKET_ID>/jira.md` chưa có (hoặc người dùng yêu cầu cập nhật mới):
     1. Gọi `jira_get_issue` (`ServerName: "jira-cwb"`, `issue_key: "<TICKET_ID>"`, `fields: "*all"`, `include: "comments,transitions"`, `use_display_names: true`).
     2. **Bắt buộc chuyển trạng thái sang `In Progress`**: Nếu `status.name` đang là `"To Do"`, gọi ngay `jira_transition_issue` (`ServerName: "jira-cwb"`, `issue_key: "<TICKET_ID>"`, `transition_id: "In Progress"` — hoặc ID `"21"`).
     3. Lưu nguyên văn Summary, Issue Type, Parent Epic, Status (`To Do -> In Progress`), Description, Acceptance Criteria và Comments quan trọng vào `.ai-workflow/tasks/<TICKET_ID>/jira.md`.
   - Nếu `jira.md` đã có sẵn, kiểm tra nhanh qua `jira_get_issue` (`fields: "status"`) để đảm bảo ticket đã ở **`In Progress`**.
   - Kiểm tra thêm `docs/superpowers/specs/` xem có bản thiết kế liên quan cùng `<TICKET_ID>` hay ticket tiền đề không.
3. **Kiểm tra Git Sạch & Tạo Nhánh từ `main`**:
   - Chạy `git status --porcelain`. Nếu có thay đổi chưa commit từ ticket khác ➔ **DỪNG LẠI BÁO HUMAN**.
   - Chọn tên nhánh chuẩn theo `00-shared-rules.md` (`feat/`, `bugfix/`, `chore/`, `docs/` + `da-xx-<slug>`, cấm `codex/` và `feature/`).
   - Chạy `git checkout main; git pull --ff-only; git checkout -b <branch-name>` *(hoặc giữ nguyên nếu đang đứng sẵn trên nhánh của chính `<TICKET_ID>`)*.
4. **Ủy quyền Khảo sát Codebase cho `Researcher` (`03 Research` — `/how` & `/why`)**:
   - Với ticket chạm từ ≥ 3 file hoặc chạm logic nhạy cảm (Vector RBAC, SSE streaming, Redis cache/Bucket4j, Flyway triggers `V4..V7`, MinIO/Ingestion), khởi chạy 1 subagent `invoke_subagent` (`TypeName: "research"`, `Role: "Codebase & History Researcher"`) để giữ sạch cửa sổ chat chính:
     - **`/how` (Cơ chế hiện tại)**: Truy luồng thực thi thực tế qua `controller` → `service` → `domain/repository` → `infrastructure` và Flyway `V*.sql` kèm `file:line`.
     - **`/why` (Lịch sử & Ràng buộc ngầm)**: Chạy `git log -n 5 --oneline -- <files>` (hoặc `git log -S`) trên vùng code định sửa để hiểu lý do tồn tại của các guard bảo mật / concurrency / `V7` metadata trước đó.
     - **`Canonical Examples`**: Chọn 1–2 file mẫu chuẩn nhất sẵn có trong repo (tham chiếu `.ai-workflow/MEMORY.md` mục 2) để làm khuôn mẫu cho Bước 2.
     - Yêu cầu `Researcher` trả về báo cáo cô đọng ≤ 30 dòng kèm trích dẫn `file:line`. *(Với task siêu nhỏ 1–2 file, Planner có thể đọc trực tiếp).*
5. **Chọn Skills Thực Tế & Quyết định Chia Vai (`Startup Checklist`)**:
   - **RAG Pipeline / Ingestion / Extraction / Chunking / PgVector / SSE**: `spring-ai-rag-pipeline`, `test-driven-development` *(tra `context7` MCP nếu dùng API Spring AI 1.0+ mới)*.
   - **JWT Auth / Vector RBAC / Redis Cache / Bucket4j Rate Limit**: `enterprise-rbac-and-cache-testing`, `test-driven-development`.
   - **Golden Dataset & KPI RAG (`Hit@5`, `Hallucination`, `Cache Hit`)**: `rag-kpi-evaluator`, `test-driven-development`.
   - **Next.js App Router / Tailwind UI / SSE Client / E2E**: `modern-web-guidance`, `chrome-devtools`, `a11y-debugging` *(và Playwright `--headed`)*.
   - **REST API Contract / OpenAPI**: `api-engineer` / `api-testing` / `api-documentation`.
   - **Bugfix / Regression**: `systematic-debugging`, `test-driven-development`.
   - **Execution Mode**: Mặc định `Single Engineer`; chỉ chọn `Specialist Split (Backend Specialist + Frontend Specialist)` khi ticket chạm đồng thời backend phức tạp VÀ nhiều màn hình Next.js/E2E.
6. **Ghi `plan.md`, `state.md` & Đồng bộ `BOARD.md`**:
   - Ghi `.ai-workflow/tasks/<TICKET_ID>/plan.md` (cô đọng, ưu tiên bảng thay vì văn xuôi dài).
   - Ghi `.ai-workflow/tasks/<TICKET_ID>/state.md` (≤ 20 dòng theo mẫu tại `00-shared-rules.md`, `Status: Working`, `Step: 01-plan`, `Strikes: 0`, `Next Gate: Gate 1 — Approve plan.md & run 02-implement.md`).
   - Chạy `node .ai-workflow/scripts/verify.cjs board`.

---

## Định dạng Kế hoạch Bắt buộc (`plan.md`)

```markdown
# Implementation Plan: <TICKET_ID> — <Tiêu đề Ticket>

- **Branch**: `<type>/da-xx-...` (từ `main` @ `<short-sha>`)
- **Jira Type & Status**: `<Story | Task | Bug>` — **`In Progress`** *(đã tự động chuyển qua `jira-cwb`)*
- **Upstream / Downstream Seams**: <Các ticket tiền đề hoặc kế tiếp phụ thuộc vào contract này>

## 1. Startup Checklist (`pstack` 6-Point Setup)

1. **Scope & Non-goals**:
   - _In scope_: ...
   - _Out of scope (YAGNI)_: ...
2. **Tools, Mode & Skills Blueprint**:
   - _Execution Mode_: `Single Engineer` _(hoặc `Specialist Split: BE + FE`)_
   - _Step 2 Skills_: `<skill-1>` (<áp dụng vào đâu>), `<skill-2>` (...)
   - _Step 3–6 Skills_: `code-review`, `ponytail-review`, `pr`
3. **Canonical Repo Examples (Bắt buộc đọc ở Bước 2 trước khi code)**:
   - `<path/to/CanonicalService.java:L1-L80>` — Mẫu chuẩn cho <RBAC / SSE / Cache / Ingestion>
   - `<path/to/CanonicalIntegrationTest.java:L1-L90>` — Mẫu chuẩn cho <Testcontainers fixture>
4. **Verification Gate Command**:
   - `node .ai-workflow/scripts/verify.cjs check --verify` *(hoặc `--test=<Class>` khi TDD; thêm `--e2e <spec>` nếu có Playwright `--headed`)*
5. **Review & Escalation Path**:
   - Tự sửa tối đa `2 strikes` cho cùng một lỗi; nếu `Strikes >= 2` hoặc phát sinh Flyway migration / dependency mới ngoài plan ➔ chuyển `Blocked` và hỏi Human.

## 2. Acceptance Criteria & Proof Matrix

| AC # | Yêu cầu từ Jira | Hành vi kỳ vọng & Ràng buộc `AGENTS.md` | Package / Tệp tác động | Bài Test chứng minh (Unit / Testcontainers / E2E) |
|---|---|---|---|---|

## 3. Root Context (`/how` & `/why`) vs Proposed Design

- **Hiện trạng & Ràng buộc lịch sử (`/how` + `/why`)**: ...
- **Thiết kế đề xuất (nhỏ nhất giải quyết trọn vẹn AC)**: ...

## 4. Component & Contract Changes

- **Backend (`backend/`)** _(nếu có)_: Controllers / DTOs (`record`) / OpenAPI / RFC 9110 ProblemDetail · Services / Virtual Threads / Vector RBAC / Cache Key Scoping / Audit Logs · Flyway Migration (`NO` mặc định; nếu `YES` nêu rõ `V<N>__*.sql`, tương thích ngược & cô lập `prod`).
- **Frontend (`frontend/`)** _(nếu có)_: Routes / Components / SSE `AbortController` / Markdown XSS safety / States (`loading`, `empty`, `error`, `429`) / Playwright `--headed`.
- **Config & Docs** _(nếu có)_: `application*.yml`, `.env.example` (giữ cổng né `flintflow-*`), tài liệu liên quan.

## 5. Expected Files To Change

| Đường dẫn tệp | Loại (`Create` / `Edit`) | Trách nhiệm cụ thể |
|---|---|---|

## 6. Assumptions & Open Questions

- **Assumptions**: ...
- **Open Questions** _(Nếu có điểm mơ hồ ảnh hưởng nghiệp vụ/contract, nêu rõ để Human chốt tại Gate 1)_: ...
```

---

## 🛑 Hộp Câu Lệnh Chuyển Tiếp Bắt Buộc (In ở cuối câu trả lời)

Sau khi tạo xong `jira.md`, `plan.md`, `state.md` và đồng bộ `BOARD.md`, tóm tắt ngắn gọn kế hoạch (≤ 10 dòng, kèm Open Questions nếu có) và in hộp sau rồi **DỪNG LƯỢT NGAY (END TURN)**:

```markdown
---

### ⏩ Bước tiếp theo: Triển Khai Mã Nguồn (Bước 2 — `Engineer` Build)

- **🤖 Model khuyến nghị trên Antigravity**: **Claude Sonnet 5.5 (High / Medium)** hoặc **Orchestrator (Carbon)** / **Gemini 4 Argon (High)** _(nếu xử lý concurrency Virtual Threads, Flyway SQL triggers, hoặc bảo mật RBAC/Cache phức tạp thì dùng **Gemini 4 Carbon (Medium/High)** hoặc **Claude Opus 5.5 (Medium/High)**)_ — _Viết Java 21 / Next.js + chạy vòng lặp TDD Testcontainers nhanh, chuẩn và tiết kiệm quota._
- **💡 Vệ sinh ngữ cảnh (Chống Re-read Tax)**: Khuyên mở **New Chat sạch** (hoặc gõ `/compact`) trước khi gửi câu lệnh dưới — toàn bộ thiết kế và file mẫu đã đóng gói gọn trong `state.md` + `plan.md`, giúp Bước 2 không phải đọc lại lịch sử mò file của Bước 1.

Nếu bạn đồng ý với kế hoạch trên, hãy copy câu lệnh dưới đây để bắt đầu implement:

> `Duyệt plan. Thực hiện Bước 2 (Implement) cho task <TICKET_ID> theo .ai-workflow/prompts/02-implement.md.`
```

---

## Hard Rules

- Chỉ tạo `jira.md`, nhánh Git mới (khi working tree sạch), `plan.md`, `state.md` và cập nhật `BOARD.md`.
- Giữ nguyên vẹn 100% source code, test, Flyway SQL, config và `docs/` của dự án ở bước này.
- In xong hộp chuyển tiếp: **DỪNG LƯỢT NGAY (END TURN)**.
