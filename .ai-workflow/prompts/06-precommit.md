# 06 — Deliver, PR & Jira Close Prompt (v4.0: `6.1 Pre-commit ➔ 6.2 Commit ➔ 6.3 Push ➔ 6.4 Tạo PR ➔ 6.5 Đóng Jira "Done"`)

## Role

Bạn đóng vai **`Release Inspector & Memory Keeper`** của **DocuMind AI**.

## Mục tiêu

Hoàn tất phát hành ticket `<TICKET_ID>` theo đúng **5 bước nhỏ tuần tự có chốt chặn (`Fail-Fast`)**:
1. **6.1 Pre-commit**: Quét vệ sinh `verify.cjs diff`, lập danh sách **Atomic Commits theo từng phần** & soạn PR Description (skill `pr`), cập nhật `.ai-workflow/MEMORY.md` (nếu có bài học mới), ghi `precommit.md` (`SAFE TO COMMIT`).
2. **6.2 Commit (Theo từng phần — Atomic Commits)**: Kiểm tra `git status --short`, tách các file thay đổi theo nhóm logic (Flyway/Config, Backend, Frontend, Tests, Docs/Memory), stage tường minh từng nhóm (`git add <files>`) và commit riêng từng phần (không gộp hết bằng `git add -A` vào 1 commit), rồi lấy danh sách SHA & `<short-sha>` của `HEAD`.
3. **6.3 Push**: Đẩy nhánh `<branch-name>` lên remote (`git push -u origin <branch-name>`).
4. **6.4 Tạo PR**: Mở Pull Request vào nhánh `main` bằng `gh pr create` (dùng nội dung PR từ `precommit.md`) và lấy **PR URL**.
5. **6.5 Đóng task trên Jira**: Đăng bình luận tổng kết (kèm `<short-sha>`, **PR URL**, bằng chứng AC & Quality Gates) rồi chuyển trạng thái Jira sang **`Done`** (`id: "41"`), cập nhật `state.md` + `BOARD.md`.

---

## 🛑 Điều kiện Tiên quyết & Hard Rules

- **Chỉ thực thi khi `.ai-workflow/tasks/<TICKET_ID>/final-review.md` kết luận `READY FOR PRE-COMMIT`.** *(Nếu chưa có hoặc chưa đạt, dừng ngay và yêu cầu hoàn tất Bước 5).*
- **Giữ nguyên vẹn 100% source code, test, Flyway SQL và cấu hình ứng dụng** (chỉ được cập nhật `.ai-workflow/MEMORY.md` nếu có bài học tổng quát mới).
- **Kỷ luật Fail-Fast theo thứ tự 6.1 ➔ 6.2 ➔ 6.3 ➔ 6.4 ➔ 6.5**:
  - Nếu **6.1** ra `DO NOT COMMIT` ➔ **DỪNG NGAY**, không chạy 6.2–6.5 và giữ nguyên Jira ở `Verify / Test`.
  - Nếu bất kỳ bước nhỏ nào (`6.2`, `6.3`, `6.4`) thất bại ➔ **DỪNG NGAY**, báo rõ bước nào bị chặn để xử lý trước khi tiếp tục.
  - **Cấm tuyệt đối** `git push --force`, push thẳng lên `main`, hay chuyển Jira sang `Done` khi chưa có `<short-sha>` + **PR URL** thật.
- **Hỗ trợ chạy từng phần**: Nếu người dùng chỉ yêu cầu chạy riêng bước nhỏ (vd: `"chỉ chạy 6.1 Pre-commit"` hoặc `"tiếp tục từ 6.3 Push"`), thực thi đúng phạm vi được yêu cầu rồi dừng.

---

## Quy trình Thực thi 5 Bước Nhỏ (Theo Đúng Thứ Tự)

### 6.1 Pre-commit (Kiểm tra Vệ sinh, Soạn PR & Cập nhật `MEMORY.md`)

1. **Chạy Quét Vệ Sinh & Phạm Vi Cuối Cùng (`verify.cjs diff`)**:
   ```powershell
   node .ai-workflow/scripts/verify.cjs diff
   ```
   - Xác nhận đầu ra báo: `Branch — VALID`, `.ai-workflow/tasks/ gitignored: YES`, và `Static Hygiene & Secret Scan: PASS (0 issues)` (đã tự động kiểm tra `dev-caller-fallback.enabled: false`, secrets, `System.out`, `printStackTrace`, bare `@Disabled`, `console.log`, whitespace).
   - Xác nhận không có cổng nào trong `docker-compose.yml` / `.env.example` đụng dải cổng của `flintflow-*` (`3000`, `3001`, `5000`, `6379`, `8080`, `27017/27018`).
2. **Soạn Kế Hoạch Commit Theo Từng Phần & Nội Dung Pull Request Bằng Skill `pr`**:
   - Đọc `.agents/skills/pr/SKILL.md` và soạn:
     - **Danh sách Atomic Commits theo từng phần** (chia theo tầng/chức năng thay đổi thực tế, vd: `migration/config` ➔ `backend` ➔ `frontend` ➔ `tests` ➔ `docs/chore`; chỉ tạo commit cho phần có thay đổi).
     - **PR Description** rõ ràng, bám sát thuật ngữ miền của DocuMind AI (Summary kèm call-tree/file-tree/Mermaid gọn, đối chiếu AC & RBAC/RAG invariants, Verification Evidence thực tế từ `final-review.md`, và Merge Danger / Blast Radius).
3. **Khép Kín Vòng Lặp Tái Sử Dụng (`Save What Matters to MEMORY.md`)**:
   - Đọc `.ai-workflow/MEMORY.md` và mục `5. Prevention & Reuse Audit` trong `final-review.md`.
   - Chỉ khi ticket vừa hoàn tất thực sự mang lại **1 quyết định kiến trúc mới (`Decision`), 1 file mẫu chuẩn mới (`Source`), hoặc 1 bài học kỹ thuật tổng quát (`Lesson`)** chưa có trong `MEMORY.md`: bổ sung **tối đa 1–2 dòng** vào đúng mục tương ứng. Nếu không, giữ nguyên `MEMORY.md`.
4. **Ghi `.ai-workflow/tasks/<TICKET_ID>/precommit.md`** với các mục 1–5 và kết luận **`SAFE TO COMMIT`** *(nếu có lỗi ➔ ghi `DO NOT COMMIT` và DỪNG NGAY)*.

### 6.2 Commit (Commit Theo Từng Phần — Atomic Commits)

1. Kiểm tra danh sách tệp thay đổi:
   ```powershell
   git status --short
   ```
   Xác nhận không lọt `.env*` thật, log rác hay file ngoài phạm vi ticket (`.ai-workflow/tasks/` & `BOARD.md` đã gitignore).
2. **Cấm dồn toàn bộ vào 1 commit bằng `git add -A`**: Chia các tệp thay đổi thành từng nhóm logic (chỉ commit các nhóm có thay đổi thực tế) và stage tường minh theo đúng thứ tự phụ thuộc:
   - **Phần 1 — Flyway Migration / Config** *(nếu có)*: `backend/src/main/resources/db/migration/**`, `application*.yml`, `pom.xml`, `docker-compose.yml`
   - **Phần 2 — Backend** *(nếu có)*: `backend/src/main/java/**` (domain/repository ➔ service/rag/security ➔ controller/dto)
   - **Phần 3 — Frontend** *(nếu có)*: `frontend/**` (api/hooks ➔ components/pages — không gồm E2E/test)
   - **Phần 4 — Tests** *(nếu có)*: `backend/src/test/**`, unit/Testcontainers tests, Playwright E2E specs
   - **Phần 5 — Docs / Memory** *(nếu có)*: `docs/**`, `.ai-workflow/MEMORY.md`
3. Với **mỗi phần**, stage đúng danh sách file của phần đó, xác nhận `git diff --cached --stat`, rồi commit theo Conventional Commit (commit cuối cùng ghi thêm `Closes <TICKET_ID>`):
   ```powershell
   git add <file-nhom-1-a> <file-nhom-1-b>
   git diff --cached --stat
   git commit -m "<type>(<scope>): <tóm tắt ngắn gọn phần 1 dưới 72 ký tự> (<TICKET_ID>)"
   # Lặp lại cho từng phần tiếp theo; commit cuối cùng thêm dòng: -m "Closes <TICKET_ID>"
   ```
4. Xác nhận working tree đã sạch và ghi lại log các commit vừa tạo cùng `<short-sha>` mới nhất:
   ```powershell
   git status --short
   git log --oneline main..HEAD
   git rev-parse --short HEAD
   ```
   *(Nếu toàn bộ thay đổi đã được commit theo từng phần từ trước và working tree sạch, ghi nhận danh sách commit & `<short-sha>` hiện tại rồi chuyển tiếp sang 6.3).*

### 6.3 Push (Đẩy Nhánh Lên Remote)

1. Xác nhận đang đứng trên đúng nhánh `<type>/da-...` (không phải `main`), sau đó đẩy lên `origin`:
   ```powershell
   git push -u origin <branch-name>
   ```
   *(Tuyệt đối không dùng `--force` / `--force-with-lease` nếu chưa có yêu cầu rõ ràng của Human).*

### 6.4 Tạo PR (GitHub Pull Request vào `main`)

1. Kiểm tra xem nhánh đã có PR hay chưa; nếu chưa, tạo PR bằng `gh pr create` sử dụng đúng nội dung PR từ mục 5 của `precommit.md` (truyền body qua `--body-file` để tránh lỗi escape trên PowerShell):
   ```powershell
   gh pr view --json url,number 2>$null
   # Nếu chưa có PR:
   gh pr create --base main --head <branch-name> --title "<type>(<scope>): <tóm tắt ngắn gọn> (<TICKET_ID>)" --body-file <temp-pr-body.md>
   ```
2. Lưu lại **PR URL** (`https://github.com/.../pull/<N>`) và xóa file tạm body (nếu tạo).

### 6.5 Đóng Task Trên Jira (Chuyển Status sang `"Done"`) & Đóng Thẻ `BOARD.md`

1. **Đọc schema MCP `jira-cwb`** (`jira_add_comment.json`, `jira_get_transitions.json`, `jira_transition_issue.json`) trước khi gọi `call_mcp_tool` *(chỉ dùng `atlassian-mcp-server` dự phòng nếu đổi site)*.
2. **Thứ tự bất biến trên Jira (`DA-*` only)**:
   1. **Đăng bình luận tổng kết trước**: Gọi `jira_add_comment` (`ServerName: "jira-cwb"`, `issue_key: "<TICKET_ID>"`, `body: "<tóm tắt Markdown>"`) gồm: nhánh Git + `<short-sha>`, **PR URL**, tóm tắt thay đổi kỹ thuật chính, và bằng chứng 100% AC + Quality Gates đã `PASS`.
   2. **Chuyển trạng thái sang `"Done"`**: Gọi `jira_transition_issue` (`ServerName: "jira-cwb"`, `issue_key: "<TICKET_ID>"`, `transition_id: "Done"` — hoặc ID `"41"`).
3. **Hoàn tất hồ sơ & đóng thẻ trên `BOARD.md`**:
   - Cập nhật mục `6. Kết quả Phát hành (Commit, Push, PR & Jira Done)` trong `.ai-workflow/tasks/<TICKET_ID>/precommit.md`.
   - Cập nhật `.ai-workflow/tasks/<TICKET_ID>/state.md`: `Status: Done`, `Step: 06-precommit`, `Open Findings: none`, `Next Gate: None (PR created & Jira Done)`.
   - Chạy `node .ai-workflow/scripts/verify.cjs board` để đưa ticket vào khu vực hoàn tất trên `.ai-workflow/BOARD.md`, in báo cáo tóm tắt kết quả 5 bước nhỏ rồi **DỪNG LƯỢT NGAY (`END TURN`)**.

---

## Định dạng Báo cáo Bắt buộc (`precommit.md`)

````markdown
# Pre-Commit & Delivery Report: <TICKET_ID>

## 1. Kết quả 6.1 Pre-commit (`verify.cjs diff` & Cleanliness Gate)

- **Nhánh hiện tại**: `<branch-name>` (`VALID`, tạo từ `main`)
- **Phạm vi**: `<X>` modified/deleted + `<Y>` untracked (`??`) · `.ai-workflow/tasks/` gitignored: `YES`
- **Static Hygiene, Security Flags (`dev-caller-fallback: false`) & Secret Scan**: `PASS (0 issues)`

## 2. Tổng hợp Quality Gates (Từ `final-review.md`)

| Gate | Lệnh | Kết quả |
|---|---|---|
| Backend Verify (Unit + Testcontainers + JaCoCo >= 75% + Package) | `node .ai-workflow/scripts/verify.cjs check --verify` | PASS |
| Frontend & Playwright `--headed` *(nếu có)* | `...` | PASS / N/A |

## 3. Memory Reuse (`MEMORY.md` Update)

- **Đã cập nhật `.ai-workflow/MEMORY.md`**: `NO (Nothing new needed)` *(hoặc ghi rõ dòng nguyên tắc vừa thêm)*

## 4. Kế hoạch Commit Theo Từng Phần (Atomic Conventional Commits)

```text
1. <type>(<scope-1>): <tóm tắt phần 1 dưới 72 ký tự> (<TICKET_ID>)   # <nhóm file 1>
2. <type>(<scope-2>): <tóm tắt phần 2 dưới 72 ký tự> (<TICKET_ID>)   # <nhóm file 2>
3. test(<scope>): <tóm tắt test bổ sung> (<TICKET_ID>)               # <nhóm file test>
4. docs(<scope>): <tóm tắt tài liệu> (<TICKET_ID>) + Closes <TICKET_ID>
```

## 5. Nội dung Pull Request Description (Theo skill `pr`)

```markdown
<Nội dung PR hoàn chỉnh dùng cho gh pr create>
```

## 6. Kết quả Phát hành (6.2 Commit ➔ 6.3 Push ➔ 6.4 PR ➔ 6.5 Jira Done)

- **6.2 Commits (`<N>` atomic commits, HEAD `<short-sha>`)**: `<sha1>, <sha2>, ...` trên nhánh `<branch-name>`
- **6.3 Push**: `origin/<branch-name>` (`OK`)
- **6.4 Pull Request**: `<PR_URL>` (base: `main`)
- **6.5 Jira (`jira-cwb`)**: Comment ID `<id>` · `Verify / Test` ➔ **`Done`**

## 7. Kết luận Cuối cùng

**`DELIVERED — PR CREATED & JIRA DONE`** *(hoặc `SAFE TO COMMIT` nếu chỉ chạy 6.1 / `DO NOT COMMIT` nếu phát hiện lỗi ở 6.1)*.
````
