# Shared Rules — DocuMind AI Workflow (v3.0: Antigravity Optimized)

Quy tắc kỷ luật bắt buộc áp dụng xuyên suốt 6 bước trong `.ai-workflow` của dự án **DocuMind AI**. Được thiết kế chuyên biệt cho môi trường **Google Antigravity** (chuyển đổi chéo họ Model Gemini 4 / Claude 5.5, Subagents độc lập, Atlassian MCP, Windows Maven Wrapper & Testcontainers).

---

## 1. Thứ Tự Ưu Tiên Source of Truth

Khi có xung đột thông tin, luôn ưu tiên theo thứ tự từ cao xuống thấp:
1. **Jira Issue Description + Comments / Product Decisions** (lấy qua `atlassian-mcp-server`, lưu tại `.ai-workflow/tasks/<TICKET_ID>/jira.md`).
2. **Non-Negotiable Invariants trong `AGENTS.md`**: Java 21 + Spring Boot 3.3+ + Spring AI 1.0+ GA, Mandatory Vector RBAC (`SecurityContextHolder` -> `Filter.Expression`), Deterministic Refusal (`"Không tìm thấy thông tin trong tài liệu nội bộ"`), Citations + SSE, Async Virtual Threads Ingestion (`<= 50MB`), Redis Cache scoping theo quyền hạn + Bucket4j Rate Limit + Audit Logs, Testcontainers coverage `>= 75%`, Playwright `--headed`, và dải cổng né `flintflow-*`.
3. **Approved Implementation Plan** (`.ai-workflow/tasks/<TICKET_ID>/plan.md`, cùng tài liệu thiết kế liên quan trong `docs/superpowers/specs/` nếu ticket có sẵn).
4. **Agent Assumptions**: Mức ưu tiên thấp nhất; bắt buộc phải ghi tường minh trong báo cáo để Human kiểm soát.

---

## 2. Kỷ Luật 1 Lượt = 1 Bước (Single-Step Execution Gate)

Mỗi lượt trả lời của Agent chỉ thực hiện **đúng 1 bước** mà người dùng vừa gọi, lưu đúng file báo cáo của bước đó vào `.ai-workflow/tasks/<TICKET_ID>/`, in hộp câu lệnh chuyển tiếp rồi **kết thúc lượt ngay lập tức (END TURN)**:

| Bước | Hành động được phép | File xuất bắt buộc trong `.ai-workflow/tasks/<TICKET_ID>/` | Ranh giới dừng (Hard Stop) |
|---|---|---|---|
| **Bước 1: Plan** | Kéo Jira `DA-*`, tạo nhánh Git sạch từ `main`, khảo sát code, lập kế hoạch | `jira.md`, `plan.md` | Không sửa source/test/migration; dừng chờ duyệt Gate 1 |
| **Bước 2: Implement** | Viết tests (TDD) + code theo `plan.md`, chạy thực tế bộ kiểm tra chất lượng | `implementation-report.md` | Không commit/push, không tự viết `review-01.md`; dừng chờ Gate 2 |
| **Bước 3: Review** | Khởi chạy 3 Subagents độc lập kiểm tra toàn bộ diff + untracked files | `review-01.md` | Không sửa bất kỳ file code/test/migration/docs nào; dừng chờ Gate 3 |
| **Bước 4: Fix** | Sửa tận gốc các findings đã xác nhận + bổ sung regression tests thực chất | `fix-report.md` | Không mở rộng scope, không commit, không tự viết `final-review.md` |
| **Bước 5: Final Review** | Đối chiếu findings cũ, chạy thực tế Quality Gates & quét độ tinh gọn | `final-review.md` | Không sửa code, không commit; dừng chờ Gate 4 |
| **Bước 6: Pre-Commit** | Kiểm tra working tree, soạn PR bằng skill `pr`, bình luận tổng kết lên Jira | `precommit.md` | Không tự `git commit` / `git push`; xuất khối lệnh để Human tự chạy |

---

## 3. Hộp Gợi Ý Chuyển Tiếp Kèm Khuyến Nghị Model (Mandatory Hand-off Box)

Ở cuối **mọi** câu trả lời khi kết thúc bước (từ Bước 1 đến Bước 5), Agent bắt buộc in hộp chuyển tiếp theo đúng mẫu sau để người dùng biết ngay nên chọn **Model nào trên thanh Model Selection của Antigravity** trước khi gửi prompt kế tiếp:

```markdown
---
### ⏩ Bước tiếp theo: [Tên bước tiếp theo]
- **🤖 Model khuyến nghị trên Antigravity**: **[Model ưu tiên 1]** *(hoặc [Model thay thế])* — *[Lý do 1 câu ngắn gọn]*
- **💡 Mẹo ngữ cảnh**: [Giữ nguyên cửa sổ chat hiện tại / Khuyên mở New Chat sạch để review khách quan]

Copy câu lệnh dưới đây và gửi vào ô chat:
> `[Câu prompt tự thân đầy đủ chứa mã <TICKET_ID> và đường dẫn .ai-workflow/prompts/...]`
```

---

## 4. Quy Tắc Thu Thập Đầy Đủ Thay Đổi Khi Review (Merge-Base + Uncommitted + Untracked Rule)

Trong `.ai-workflow`, Bước 2 và Bước 4 **không chạy `git commit`**, hoặc đôi khi người dùng kích hoạt Bước 3 trên một nhánh tính năng đã có vài commit trước đó kèm các tệp đang sửa dở. Vì vậy, tại Bước 3 (`03-review.md`) và Bước 5 (`05-final-review.md`), Reviewer và các Subagents **cấm dùng `git diff main...HEAD` ba chấm** (vì sẽ bỏ sót toàn bộ thay đổi chưa commit), mà bắt buộc thu thập theo 3 bước:
1. Lấy commit gốc phân nhánh từ `main`: `git merge-base main HEAD` -> `<BASE_SHA>`.
2. Chạy `git diff <BASE_SHA>` để lấy **trọn vẹn cả các commit đã có trên nhánh lẫn các chỉnh sửa chưa commit** trên working tree.
3. Chạy `git ls-files --others --exclude-standard` (hoặc `git status --short`) để lấy danh sách và đọc trực tiếp toàn bộ nội dung các **tệp mới chưa theo dõi (`??`)** (Java classes, Flyway SQL, tests, Next.js components, docs...). Lưu ý `.ai-workflow/tasks/` đã được `.gitignore` tự động loại trừ.

---

## 5. Chuẩn Tạo Nhánh Git Tự Động Từ `main` (Automated Branch Protocol)

Khi khởi động một ticket `DA-*` mới ở Bước 1:
1. **Kiểm tra working tree sạch**: Chạy `git status --porcelain`. Nếu phát hiện có thay đổi chưa commit thuộc về một ticket khác ➔ **DỪNG LẠI & BÁO NGƯỜI DÙNG NGAY**, tuyệt đối không tự ý `git stash`, `git reset` hay `git checkout` làm hỏng bài đang làm dở.
2. **Đọc Jira Issue** qua `atlassian-mcp-server` (`getJiraIssue` với `view: "full"`, kèm `listJiraIssueComments` nếu `comments` > 0) để xác định `issueType`, `summary` và các chốt nghiệp vụ.
3. **Chọn tiền tố nhánh chuẩn**:
   - `feat/<ticket-viết-thường>-<slug>`: Khi `issueType` là `Story`, `Feature`, `New Feature`, hoặc xây dựng module mới (vd: `feat/da-9-async-ingestion-worker`).
   - `bugfix/<ticket-viết-thường>-<slug>`: Khi `issueType` là `Bug` hoặc vá lỗi hồi quy.
   - `chore/<ticket-viết-thường>-<slug>`: Khi `issueType` là `Task` / `Sub-task` hạ tầng, CI/CD, cấu hình, refactor, test harness.
   - `docs/<ticket-viết-thường>-<slug>`: Khi chỉ cập nhật tài liệu kiến trúc / hướng dẫn.
   - **Quy tắc cứng**: Mã ticket viết thường (`da-9`, `da-10`...), slug tiếng Anh ngắn gọn 2–4 từ dạng kebab-case. **Tuyệt đối không dùng tiền tố `codex/` hay `feature/`**.
4. **Tạo nhánh từ nhánh gốc `main`** (nhánh mặc định của DocuMind AI là `main`):
   ```powershell
   git checkout main
   git pull --ff-only
   git checkout -b <branch-name>
   ```
   *(Nếu đang đứng sẵn trên đúng nhánh `<branch-name>` của chính ticket đó thì giữ nguyên, không tạo lại).*

---

## 6. Bộ Lệnh Kiểm Chứng Chuẩn Trên Windows Host (Canonical Verification Suite)

Máy host Windows **không cài `mvn` toàn cục** — mọi lệnh Backend bắt buộc gọi qua Maven Wrapper **`.\mvnw.cmd`** bên trong thư mục `backend/`:

1. **Backend Targeted Tests (Chạy nhanh khi TDD hoặc kiểm chứng vùng vừa sửa)**:
   ```powershell
   Set-Location backend; .\mvnw.cmd test -Dtest=<TenClassTest1>,<TenClassTest2>
   ```
2. **Backend Full Unit + Testcontainers Integration Suite & Package (Bắt buộc khi chạm `backend/`, Flyway SQL, `docker-compose.yml`)**:
   ```powershell
   Set-Location backend; .\mvnw.cmd verify
   ```
   *(Lưu ý: cờ mặc định `-Dsmoke=false` chạy toàn bộ Unit + Testcontainers `pgvector:pg16` / `redis:7-alpine` / MinIO độc lập; chỉ thêm `-Dsmoke=true` khi cần kiểm tra kết nối trực tiếp tới dịch vụ Ollama/Docker host đang chạy thật).*
3. **Spring AI Version Check (Khi thêm/sửa code Spring AI)**:
   Luôn đối chiếu đúng package của **Spring AI `1.0.3` GA** trong `backend/pom.xml` (hoặc tra qua `context7` MCP) — cấm đoán mò API `0.8.x` cũ.
4. **Frontend Next.js Checks (Kích hoạt ngay khi `frontend/package.json` tồn tại và ticket chạm `frontend/`)**:
   ```powershell
   Set-Location frontend; npm run lint; npm run typecheck; npm test; npm run build
   ```
5. **Playwright E2E — Bắt buộc Headed Mode (Khi chạm luồng UI / E2E)**:
   Luôn truyền **`--headed`** (`headless: false`) để hiển thị trình duyệt trực tiếp trên màn hình, tuyệt đối không chạy ngầm headless.
6. **RAG Golden Dataset KPIs (Khi chạm thuật toán chunking, retrieval, `similarityThreshold`, prompt hoặc semantic cache)**:
   Kiểm chứng các ngưỡng `Hit@5 >= 85%`, `Hallucination Rate == 0%`, `Cache Hit Ratio >= 30%-40%` theo skill `rag-kpi-evaluator`.

Không bao giờ khai khống `PASS` cho lệnh chưa thực sự chạy. Nếu Docker Desktop chưa bật khiến Testcontainers bị chặn, phải báo rõ trạng thái `BLOCKED (Docker daemon not running)` và vẫn chạy hết các unit tests / compile checks độc lập.

---

## 7. Điều Kiện Dừng Khẩn Cấp (Stop Conditions)

Dừng ngay lập tức và hỏi ý kiến người dùng khi gặp một trong các tình huống:
1. Jira Acceptance Criteria mâu thuẫn nội tại hoặc vi phạm trực tiếp các bất biến bảo mật trong `AGENTS.md` (RBAC, Anti-Hallucination, Cache Isolation, Port Coexistence).
2. Phát hiện cần viết thêm Flyway migration (`V<N>__*.sql`) hoặc thêm dependency mới vào `pom.xml` / `package.json` **ngoài** `plan.md` đã duyệt.
3. Working tree đang có thay đổi chưa commit của một ticket khác khi bắt đầu Bước 1.
4. Bất kỳ phép kiểm tra biên dịch, test hoặc build nào đang `FAIL` thực sự (không được chuyển sang bước kế tiếp khi code đang đỏ).
