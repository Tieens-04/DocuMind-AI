# Shared Rules — DocuMind AI Workflow (v4.0: `pstack` + Grok Bot Edition)

Quy tắc kỷ luật bắt buộc cho toàn bộ 6 bước trong `.ai-workflow` của **DocuMind AI**, kết hợp kiến trúc **4-Role Specialist của Lauren Tan (`pstack`)**, cơ chế **Token Economy của Grok Bot (`Wake-ups × Re-reads`)** và khả năng **Cross-Model + Subagents + MCP `jira-cwb` của Google Antigravity**.

---

## 1. Thứ Tự Source of Truth & Luật "Fetch, Never Remember"

Khi đối chiếu thông tin, luôn ưu tiên từ trên xuống dưới và **truy xuất trực tiếp nguồn sống (fetch live)** thay vì dựa vào trí nhớ cũ:

1. **Live Jira Issue + Comments** (lấy qua MCP **`jira-cwb`** kết nối `nguyentien191004.atlassian.net`, lưu tại `.ai-workflow/tasks/<TICKET_ID>/jira.md`; `atlassian-mcp-server` chỉ dùng dự phòng).
2. **Non-Negotiable Invariants trong `AGENTS.md`** & Schema/Dependencies thực tế (`backend/pom.xml`, `backend/src/main/resources/db/migration/V*.sql`): Java 21 + Spring Boot 3.4 + Spring AI 1.0+ GA, Mandatory Vector RBAC (`SecurityContextHolder` -> `Filter.Expression`), Deterministic Refusal (`"Không tìm thấy thông tin trong tài liệu nội bộ"`), Citations + SSE, Async Virtual Threads Ingestion (`<= 50MB`), Redis Cache scoping theo quyền hạn + Bucket4j Rate Limit + Audit Logs, Testcontainers coverage `>= 75%`, Playwright `--headed`, và dải cổng né `flintflow-*`.
3. **Approved Plan & Handoff State** (`.ai-workflow/tasks/<TICKET_ID>/plan.md` & `state.md`, cùng `docs/superpowers/specs/` nếu ticket có sẵn).
4. **Shared Persistent Memory** (`.ai-workflow/MEMORY.md`): Các quyết định kiến trúc (`Decisions`), file mẫu chuẩn (`Sources`), và bài học tổng quát (`Lessons`).
5. **Agent Assumptions**: Mức thấp nhất; bắt buộc phải ghi rõ trong báo cáo.

---

## 2. Kiến Trúc 4 Vai Trò, Kỷ Luật 1 Lượt = 1 Bước & Vòng Đời Trạng Thái Jira

Mỗi lượt chỉ đóng **đúng 1 vai trò cho 1 bước**, đồng bộ đúng trạng thái Jira trên bảng `DA` (`To Do` ➔ `In Progress` ➔ `Verify / Test` ➔ `Done` qua MCP **`jira-cwb`**), ghi artifact + cập nhật `state.md` + chạy `node .ai-workflow/scripts/verify.cjs board`, in Hộp Chuyển Tiếp rồi **DỪNG LƯỢT NGAY (END TURN)**:

| Bước | Vai trò (`pstack` Role) | Tự động đổi Jira (`jira-cwb`) | Đầu ra bắt buộc trong `tasks/<TICKET_ID>/` | Ranh giới dừng (Undo-Priced Gate) |
|---|---|---|---|---|
| **Bước 1: Plan** | `Chief of Staff` + `Researcher` (subagent) | **`In Progress`** (`id: "21"`) | `jira.md`, `plan.md`, `state.md` | Chỉ tạo nhánh sạch từ `main` & lập kế hoạch; không sửa source/test/migration |
| **Bước 2: Implement** | `Engineer` *(hoặc tách BE/FE Specialists)* | *(Giữ `In Progress`)* | `implementation-report.md`, `state.md` | Code + TDD + `verify.cjs check --verify` xanh; không `git commit`, không tự review |
| **Bước 3: Review** | `Reviewer / Checker` (3 `research` subagents) | **`Verify / Test`** (`id: "31"`) | `review-01.md` (`FAILED/SAW/FIX`), `state.md` | Chỉ đọc & kiểm chứng độc lập; không sửa bất kỳ file code/test/SQL/docs nào |
| **Bước 4: Fix** | `Engineer` | *(Giữ `Verify / Test`)* | `fix-report.md`, `state.md` | Vá đúng root-cause + regression tests + `verify.cjs check --verify`; không mở rộng scope |
| **Bước 5: Final Review** | `Final Checker` + `OPS` Prevention | *(Đảm bảo `Verify / Test`)* | `final-review.md`, `state.md` | Tái xác minh độc lập + chạy thật `verify.cjs check --verify` + Prevention Audit |
| **Bước 6: Deliver** | `Deliver & Memory Keeper` | **`Done`** (`id: "41"`, ở bước nhỏ **6.5**, sau khi PR đã tạo) | `precommit.md`, `state.md` (+ `MEMORY.md`) | Thực thi tuần tự 5 bước nhỏ: **6.1 Pre-commit ➔ 6.2 Commit ➔ 6.3 Push ➔ 6.4 Tạo PR ➔ 6.5 Đóng Jira (`Done`)** |

---

## 3. Gói Bàn Giao Gọn `state.md` & Chống Đọc Lại Lịch Sử Chat (`Re-read Tax`)

Để tránh việc mỗi bước sau phải đọc lại hàng trăm nghìn token lịch sử chat cũ hoặc báo cáo dài:

1. **Mọi bước từ 1 đến 6 phải tạo/cập nhật `.ai-workflow/tasks/<TICKET_ID>/state.md` (tối đa 20 dòng)** theo đúng 5 trạng thái chuẩn của `THE BOARD` (`Todo` | `Working` | `Blocked` | `Ready for Review` | `Done`):
   ```markdown
   # Handoff State: <TICKET_ID>

   - **Status**: <Todo | Working | Blocked | Ready for Review | Done>
   - **Step**: <01-plan | 02-implement | 03-review | 04-fix | 05-final-review | 06-precommit>
   - **Branch**: `<type>/da-xx-...`
   - **Objective**: <1 câu mục tiêu cốt lõi của ticket>
   - **Canonical Examples**: `<path/to/Example1.java>`, `<path/to/Example2Test.java>`
   - **Changed Files**: `<danh sách gọn các file M/A/D/?? hiện tại hoặc "none">`
   - **Key Decisions**: <1–2 dòng quyết định kỹ thuật quan trọng nhất>
   - **Strikes**: <0 | 1 | 2>
   - **Open Findings**: <Danh sách mã lỗi chưa đóng, vd: R1-01, R1-02 hoặc "none">
   - **Next Gate**: <Hành động tiếp theo cần thực hiện>
   ```
   *(Tương thích ngược: Nếu ticket khởi tạo từ v3.0 chưa có `state.md`, bước hiện tại tự tạo mới `state.md` khi kết thúc bước).*
2. **Đồng bộ Bảng Công Việc (`.ai-workflow/BOARD.md`)**: Cuối mỗi bước, chạy `node .ai-workflow/scripts/verify.cjs board` (mất `<0.1s`, tốn `0` token LLM) để cập nhật bảng tổng quan toàn dự án (`.ai-workflow/BOARD.md` đã được `.gitignore` che chắn nên không làm bẩn `git status`).
3. **Chiến lược Cắt Lịch Sử Chat (Context Hygiene)**:
   - Khuyến khích mở **New Chat sạch** (hoặc gõ `/compact`) ở **mọi ranh giới đổi vai trò**: Bước 1 → Bước 2 (`Researcher` → `Engineer`), Bước 2 → Bước 3 (`Engineer` → `Reviewer`), Bước 3 → Bước 4 (`Reviewer` → `Engineer`), Bước 4 → Bước 5 (`Engineer` → `Final Checker`). Nhờ `state.md` + `plan.md` / `review-01.md` gọn sắc, cửa sổ mới nắm đủ 100% trạng thái mà giảm 50%–75% lượng token đọc lại.

---

## 4. Công Cụ Kiểm Chứng 1 Lệnh (`verify.cjs` — Script Before Reasoning) & Quy Tắc Diff Đầy Đủ

Máy host Windows **không cài `mvn` toàn cục** — mọi lệnh Maven đều đi qua `backend/mvnw.cmd`. Thay vì chạy rời rạc nhiều lệnh terminal làm phình context bởi log thô, ưu tiên tuyệt đối dùng `.ai-workflow/scripts/verify.cjs`:

1. **Thu thập phạm vi diff (`merge-base main HEAD` + uncommitted + untracked `??`) & quét vệ sinh mã nguồn (Bước 3, 5, 6)**:
   ```powershell
   node .ai-workflow/scripts/verify.cjs diff
   ```
   *(Tự động kiểm tra nhánh `^(bugfix|feat|chore|docs)/da-[0-9]+-`, liệt kê cả diff từ `git merge-base main HEAD` lẫn tệp mới `??`, chạy `git diff <BASE_SHA> --check`, kiểm tra `dev-caller-fallback.enabled: false`, quét `.env*` thật, `*.log`, `System.out`, `printStackTrace()`, bare `@Disabled`, `console.log`, `debugger`, `.only`/`.skip` và secret patterns).*
   - **Cấm dùng `git diff main...HEAD` ba chấm** khi review vì sẽ bỏ sót toàn bộ code chưa commit; luôn dùng `<BASE_SHA> = git merge-base main HEAD` ➔ `git diff <BASE_SHA>` + đọc trực tiếp các tệp `??`.
2. **Chạy trọn bộ Quality Gates với bảng tóm tắt gọn (Bước 2, 4, 5)**:
   ```powershell
   # Chạy targeted tests nhanh khi TDD hoặc kiểm chứng vùng vừa sửa:
   node .ai-workflow/scripts/verify.cjs check --test=ClassTest1,ClassTest2

   # Chạy chốt full Backend (Unit + Testcontainers pgvector/redis/minio + JaCoCo >= 75% + Package) + Frontend (khi có):
   node .ai-workflow/scripts/verify.cjs check --verify

   # Chạy kèm Playwright E2E (luôn ép --headed / headless: false):
   node .ai-workflow/scripts/verify.cjs check --verify --e2e e2e/chat.spec.ts
   ```
3. **Spring AI & RAG KPIs**:
   - Khi thêm/sửa code Spring AI, luôn đối chiếu đúng package **Spring AI `1.0.3` GA** trong `backend/pom.xml` (hoặc tra `context7` MCP) — cấm đoán mò API `0.8.x`.
   - Khi chạm thuật toán chunking, retrieval, `similarityThreshold`, prompt hoặc cache, kiểm chứng ngưỡng Golden Dataset (`Hit@5 >= 85%`, `Hallucination Rate == 0%`, `Cache Hit Ratio >= 30%-40%`) theo `rag-kpi-evaluator`.
4. **Không bao giờ khai khống `PASS` cho cổng chưa chạy thực tế.** Nếu Docker Desktop chưa bật khiến Testcontainers bị chặn, báo rõ `BLOCKED (Docker daemon not running)` và vẫn chạy hết các unit tests / compile checks độc lập.

---

## 5. Cầu Chì "2-Strike Blocked" & Cổng Phê Duyệt Theo Chi Phí Hoàn Tác (`The Gate`)

1. **2-Strike Circuit Breaker (Chống lặp vô tận)**:
   - Trong cùng một bước (Bước 2 hoặc Bước 4): nếu cùng một cách sửa hoặc bài test thất bại **2 lần liên tiếp** theo cùng một kiểu mà chưa tìm ra nguyên nhân gốc, dừng thử mù lần 3.
   - Giữa các vòng Review ↔ Fix: mỗi lần `final-review.md` trả về `CHANGES REQUESTED` trên cùng một lỗi cũ, tăng `Strikes` trong `state.md` thêm `1`. Khi **`Strikes >= 2`**, chuyển `Status: Blocked` trong `state.md`, chạy `node .ai-workflow/scripts/verify.cjs board` và báo ngay cho Human kèm 2 phương án xử lý rõ ràng.
2. **Gate Priced by Undo Cost**:
   - **Tự hoàn tất 100% bước khả nghịch**: khảo sát code, viết test/code local, chạy `verify.cjs`, tạo báo cáo, nháp PR description.
   - **Kiểm soát chặt bước khó/không thể hoàn tác**: xóa dữ liệu, viết thêm Flyway migration (`V<N>__*.sql`) hoặc thêm dependency vào `pom.xml`/`package.json` **ngoài** `plan.md` đã duyệt, working tree bẩn từ ticket khác ở Bước 1, hoặc khi Jira AC mâu thuẫn với `AGENTS.md` ➔ **dừng hỏi Human**. Ở Bước 6, chỉ chạy `6.2 Commit ➔ 6.3 Push ➔ 6.4 Tạo PR ➔ 6.5 Đóng Jira ("Done")` khi `final-review.md` đạt `READY FOR PRE-COMMIT` và `6.1 Pre-commit` đạt `SAFE TO COMMIT` (cấm `--force` hay push thẳng lên `main`).

---

## 6. Chuẩn Tạo Nhánh Git Tự Động Từ `main` (Automated Branch Protocol — Bước 1)

1. Chạy `git status --porcelain`. Nếu có thay đổi chưa commit từ ticket khác ➔ **DỪNG LẠI BÁO HUMAN**, không tự ý stash/reset/checkout đè.
2. Chọn tiền tố theo `issueType` từ Jira: `feat/`, `bugfix/`, `chore/`, hoặc `docs/` + `da-<số>-<slug-2-đến-4-từ>` (vd: `feat/da-19-redis-rbac-cache`). **Tuyệt đối không dùng tiền tố `codex/` hay `feature/`**.
3. Tạo nhánh từ `main`:
   ```powershell
   git checkout main
   git pull --ff-only
   git checkout -b <branch-name>
   ```
   *(Nếu đang đứng sẵn trên đúng nhánh `<branch-name>` của chính ticket đó thì giữ nguyên).*

---

## 7. Mẫu Hộp Gợi Ý Chuyển Tiếp Bắt Buộc (Mandatory Hand-off Box)

Cuối mỗi lượt từ Bước 1 đến Bước 5, in đúng mẫu hộp sau:

```markdown
---

### ⏩ Bước tiếp theo: [Tên bước & Vai trò kế tiếp]

- **🤖 Model khuyến nghị trên Antigravity**: **[Model ưu tiên 1]** _(hoặc [Model thay thế])_ — _[Lý do 1 câu]_
- **💡 Vệ sinh ngữ cảnh (Chống Re-read Tax)**: [Khuyên mở **New Chat sạch** (hoặc chạy `/compact`) rồi dán câu lệnh dưới — trạng thái đã lưu trọn trong `state.md`]

Copy câu lệnh dưới đây và gửi vào ô chat:

> `[Câu prompt tự thân đầy đủ chứa mã <TICKET_ID> và đường dẫn .ai-workflow/prompts/...]`
```
