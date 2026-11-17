# 01 — Planning Prompt (v3.0: Jira Sync + `main` Branch + Dynamic Skills Blueprint)

## Role
Bạn là **Senior Principal Engineer & Technical Planner** của dự án **DocuMind AI**.

## Mục tiêu
Đồng bộ đầy đủ ngữ cảnh Jira ticket (`DA-*`), tạo nhánh làm việc sạch từ `main`, khảo sát thực tế codebase Java 21 / Spring AI / PostgreSQL `pgvector` / Next.js và lập bản kế hoạch triển khai (`plan.md`) chuẩn xác, tinh gọn, bảo toàn 100% các bất biến bảo mật và RAG trong `AGENTS.md`.

> 🛑 **CHỐT CHẶN BƯỚC 1 (MANDATORY HARD STOP):** Lượt này **CHỈ LẬP KẾ HOẠCH (`jira.md` + `plan.md`)**. Tuyệt đối không viết code, không viết test, không chạy sang Bước 2 (Implement). Xuất xong `plan.md` và hộp gợi ý là **DỪNG NGAY LẬP TỨC** để người dùng duyệt Gate 1 và đổi Model!

---

## Quy trình Thực thi Bắt buộc (Theo đúng thứ tự)

1. **Đọc Luật Nền**:
   - Đọc `.ai-workflow/prompts/00-shared-rules.md` và `AGENTS.md`.
2. **Thu thập Jira Source of Truth (`jira.md`) & Tự Động Chuyển Trạng Thái sang `In Progress`**:
   - Đọc schema của MCP **`jira-cwb`** (`jira_get_issue.json`, `jira_transition_issue.json`) trước khi gọi `call_mcp_tool` *(dự án `DA` nằm tại `nguyentien191004.atlassian.net` kết nối trực tiếp qua `jira-cwb`; chỉ dùng `atlassian-mcp-server` làm dự phòng nếu đổi site)*.
   - Nếu `.ai-workflow/tasks/<TICKET_ID>/jira.md` chưa tồn tại (hoặc người dùng yêu cầu cập nhật mới):
     1. Gọi `jira_get_issue` (`ServerName: "jira-cwb"`, `issue_key: "<TICKET_ID>"`, `fields: "*all"`, `include: "comments,transitions"`, `use_display_names: true`).
     2. **Bắt buộc chuyển trạng thái sang `In Progress`**: Kiểm tra trường `status.name` của issue; nếu đang là `"To Do"` (chưa phải `"In Progress"`), gọi ngay **`jira_transition_issue`** (`ServerName: "jira-cwb"`, `issue_key: "<TICKET_ID>"`, `transition_id: "In Progress"` — hoặc ID `"21"` trong `transitions`) để chuyển ticket sang **`In Progress`**.
     3. Lưu nguyên văn Summary, Issue Type, Parent Epic, Status (ghi nhận rõ `To Do -> In Progress`), Description, Acceptance Criteria và các Comments quan trọng vào `.ai-workflow/tasks/<TICKET_ID>/jira.md`.
   - Nếu `jira.md` đã có sẵn từ trước, vẫn kiểm tra nhanh qua `jira_get_issue` (`fields: "status"`, `include: "transitions"`) để đảm bảo ticket trên Jira đã được chuyển sang **`In Progress`**.
   - Kiểm tra thêm xem trong `docs/superpowers/specs/` có bản thiết kế nào của cùng mã `<TICKET_ID>` hay các ticket tiền đề liền kề (`DA-5`..`DA-9`...) không để nắm khế ước đầu-cuối (seam contracts).
3. **Kiểm tra Git & Tạo Nhánh Mới từ `main`**:
   - Chạy `git status --porcelain`. Nếu có thay đổi chưa commit từ một ticket khác ➔ **STOP & BÁO NGƯỜI DÙNG NGAY** (nếu đang đứng sẵn trên nhánh của chính `<TICKET_ID>` do làm tiếp dở dang thì ghi nhận rõ và giữ nguyên).
   - Chọn tên nhánh chuẩn theo `00-shared-rules.md` (`feat/`, `bugfix/`, `chore/`, `docs/` + `da-xx-<slug>`, cấm dùng `codex/` hay `feature/`).
   - Khi tạo nhánh mới: `git checkout main; git pull --ff-only; git checkout -b <branch-name>`.
4. **Phân loại Task Archetype & Chọn Skills Thực Tế (Dynamic Skill Selection)**:
   Chỉ chọn các skills thực sự có sẵn và phục vụ trực tiếp ticket hiện tại:
   - **RAG Pipeline / Ingestion / Extraction / Chunking / PgVector / SSE Streaming**: `spring-ai-rag-pipeline`, `test-driven-development` *(kèm tra cứu `context7` MCP nếu dùng class Spring AI 1.0+ mới)*.
   - **JWT Auth / Vector RBAC / Redis Semantic-Exact Cache / Bucket4j Rate Limit**: `enterprise-rbac-and-cache-testing`, `test-driven-development`.
   - **Golden Dataset & Đánh giá KPI RAG (`Hit@5`, `Hallucination`, `Cache Hit`)**: `rag-kpi-evaluator`, `test-driven-development`.
   - **Next.js App Router / Tailwind UI / SSE Client / E2E**: `modern-web-guidance`, `chrome-devtools`, `a11y-debugging` *(và Playwright `--headed`)*.
   - **Thiết kế / Kiểm thử Hợp đồng REST API & OpenAPI**: `api-engineer` / `api-testing` / `api-documentation`.
   - **Điều tra Bug / Sửa lỗi Hồi quy**: `systematic-debugging`, `test-driven-development`.
   - **Review & Phát hành cố định cho các bước sau**: `code-review`, `ponytail-review` (Bước 3 & 5), `pr` (Bước 6).
5. **Khảo sát Codebase Sâu (Repo Inspection)**:
   - Đọc trực tiếp các package liên quan trong `backend/src/main/java/com/documind/` (`controller`, `service`, `domain`, `repository`, `infrastructure`, `exception`), `application*.yml` và các bản migration Flyway `backend/src/main/resources/db/migration/V*.sql` (đặc biệt lưu ý các trigger/ràng buộc DB đã có như `V4..V7`).
   - Đọc các lớp hỗ trợ test sẵn có trong `backend/src/test/java/com/documind/support/` (`PostgresTestcontainersConfig`, `MinioTestcontainersConfig`, stubs...) để tái sử dụng container singleton thay vì dựng lại trùng lặp.
   - Nếu chạm giao diện, khảo sát `frontend/` và cấu hình cổng trong `.env.example` / `docker-compose.yml`.
   - Đối chiếu các mục liên quan trong `.ai-workflow/rules/domain-review.md`.

---

## Định dạng Kế hoạch Bắt buộc (`plan.md`)

Ghi kết quả vào `.ai-workflow/tasks/<TICKET_ID>/plan.md`:

```markdown
# Implementation Plan: <TICKET_ID> — <Tiêu đề Ticket>

- **Branch**: `<type>/da-xx-...` (tạo từ `main` @ `<short-sha>`)
- **Jira Type & Status**: `<Story | Task | Bug>` — **`In Progress`** *(đã tự động chuyển từ `To Do`)*
- **Upstream / Downstream Seams**: <Các ticket tiền đề hoặc ticket kế tiếp phụ thuộc vào contract này>

## 1. Task Archetype & Selected Skills Blueprint
- **Task Archetype**: `[RAG Core / Ingestion | Security, Cache & Governance | Frontend Next.js & SSE | Golden Dataset Eval | Bugfix & Hardening | Mixed Fullstack]`
- **Skills kích hoạt ở Bước 2 (Implement)**:
  - `<skill-name>`: <Áp dụng cụ thể vào phần nào của task>
- **Skills kích hoạt ở Bước 3, 5, 6**: `code-review`, `ponytail-review`, `pr`

## 2. Goal & Acceptance Criteria Mapping
| AC # | Yêu cầu từ Jira | Hành vi kỳ vọng & Ràng buộc `AGENTS.md` | Package / Tệp tác động | Phương án Test chứng minh (Unit / Testcontainers / E2E) |
|---|---|---|---|---|

## 3. Current State vs Proposed Architecture
- **Hiện trạng trong codebase**: ...
- **Giải pháp kỹ thuật đề xuất**: ...

## 4. Detailed Component & Invariant Plan
### Backend (`backend/` — Java 21 / Spring Boot 3.4 / Spring AI 1.0.3)
- **Controllers / DTOs (`record`) / OpenAPI / Exception Mapping**: HTTP status, RFC 9110 ProblemDetail, headers.
- **Services / Virtual Threads / AI & Storage Pipeline**: Luồng xử lý, quản lý tài nguyên (`InputStream`/temp file), chuẩn hóa `NFC`, RBAC `Filter.Expression` từ `SecurityContextHolder`, deterministic refusal, hoặc Redis cache key scoping.
- **Persistence & Flyway (`db/migration`)**: Cần thêm migration `V<N>__*.sql` không? *(Mặc định `NO`; nếu `YES`, nêu rõ DDL, trigger/index, tính tương thích ngược và cô lập `prod` khỏi `db/seed`)*.

### Frontend (`frontend/` — Next.js App Router / TypeScript, nếu liên quan)
- **Routes / Components / SSE Client**: Quản lý stream (`AbortController`), render Markdown & Citation cards, xử lý trạng thái Loading / Empty / Error / `429`.

### Configuration & Docs (Nếu thay đổi biến môi trường hoặc tài liệu)
- Cập nhật `application*.yml`, `.env.example` (giữ nguyên cổng né `flintflow-*`), tài liệu kỹ thuật nếu cần.

## 5. Expected Files To Change
| Đường dẫn tệp | Loại (`Create` / `Edit`) | Trách nhiệm cụ thể |
|---|---|---|

## 6. Test Strategy (TDD & Real Infrastructure)
- **Fast Unit Tests (`*Test.java` / Vitest)**: Happy path, biên dữ liệu tiếng Việt, xử lý lỗi & ngoại lệ.
- **Testcontainers Integration Tests (`*IntegrationTest.java`)**: Kiểm chứng thực trên `pgvector/pgvector:pg16`, `redis:7-alpine`, MinIO (RBAC matrix, trigger DB, cache isolation, transaction/concurrency) — không mock `VectorStore`/`JdbcTemplate`/`Redis`.
- **Playwright Headed E2E / RAG Eval** *(nếu thuộc phạm vi)*: Kịch bản chạy `--headed` hoặc đo KPI Golden Dataset.

## 7. Exact Verification Commands
Liệt kê chính xác các lệnh PowerShell sẽ chạy ở Bước 2:
1. Targeted tests: `Set-Location backend; .\mvnw.cmd test -Dtest=...`
2. Full backend suite & package: `Set-Location backend; .\mvnw.cmd verify`
3. *(Nếu chạm `frontend/`)*: `Set-Location frontend; npm run lint; npm run typecheck; npm test; npm run build` & Playwright `--headed`

## 8. Assumptions, Open Questions & Non-goals (YAGNI)
- **Assumptions**: ...
- **Open Questions** *(Nếu có điểm mơ hồ ảnh hưởng tới contract hoặc nghiệp vụ, hỏi rõ để Human chốt tại Gate 1)*: ...
- **Non-goals (YAGNI)**: Những hạng mục thuộc ticket sau tuyệt đối không làm lấn sân.
```

---

## 🛑 Hộp Câu Lệnh Chuyển Tiếp Bắt Buộc (In ở cuối câu trả lời)

Sau khi tạo xong `jira.md` và `plan.md`, tóm tắt ngắn gọn kế hoạch (nêu bật Open Questions nếu có), in đúng hộp dưới đây rồi **DỪNG LƯỢT NGAY (END TURN)**:

```markdown
---
### ⏩ Bước tiếp theo: Triển Khai Mã Nguồn (Bước 2 — Implement)
- **🤖 Model khuyến nghị trên Antigravity**: **Claude Sonnet 5.5 (High / Medium)** hoặc **Orchestrator (Carbon)** / **Gemini 4 Argon (High)** *(nếu xử lý concurrency Virtual Threads, Flyway SQL triggers, hoặc bảo mật RBAC/Cache phức tạp thì dùng **Gemini 4 Carbon (Medium/High)** hoặc **Claude Opus 5.5 (Medium/High)**)* — *Viết Java 21 / Next.js + chạy vòng lặp TDD Testcontainers nhanh, chuẩn và tiết kiệm quota.*
- **💡 Mẹo ngữ cảnh**: Giữ nguyên cửa sổ chat hiện tại (hoặc đổi Model trên thanh chọn rồi gửi câu lệnh dưới).

Nếu bạn đồng ý với kế hoạch trên, hãy copy câu lệnh dưới đây để bắt đầu implement:
> `Duyệt plan. Thực hiện Bước 2 (Implement) cho task <TICKET_ID> theo .ai-workflow/prompts/02-implement.md và các skills trong blueprint của plan.md.`
```

---

## Hard Rules
- Chỉ tạo `jira.md`, nhánh Git mới (khi working tree sạch) và `plan.md`.
- Không tạo hay sửa bất kỳ tệp mã nguồn, test, Flyway SQL hay cấu hình nào của dự án.
- Sau khi in hộp chuyển tiếp: **DỪNG LẠI NGAY LẬP TỨC (END TURN)**.
