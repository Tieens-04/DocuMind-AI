# DocuMind AI — AI Development Workflow (v4.0: `pstack` + Grok Bot + Antigravity Edition)

Hệ thống điều phối phát triển phần mềm 6 bước dành riêng cho **DocuMind AI** (Java 21, Spring Boot 3.4, Spring AI 1.0.3 GA, PostgreSQL 16 `pgvector`, Redis 7, MinIO, Next.js) trên **Google Antigravity**, hợp nhất 3 trụ cột:

1. **Kiến trúc 4 Vai trò Chuyên biệt của Lauren Tan (`pstack`)**: `Chief of Staff` (điều phối) → `Researcher` (khảo sát `/how` & `/why` bằng subagent) → `Engineer` (TDD + chống AI slop) → `Reviewer / Checker` (3 subagents độc lập + `Comment-Sicko`) → `Memory Reuse` (`MEMORY.md`).
2. **Cơ chế Tiết kiệm Token của Grok Bot (`Wake-ups × Re-reads`)**: Gói bàn giao siêu gọn `state.md` (≤ 20 dòng), bảng `BOARD.md` 5 trạng thái, CLI kiểm chứng 1 lệnh `verify.cjs` (thay cho nhiều lệnh rời rạc), cầu chì **2-Strike Blocked** và cắt lịch sử chat giữa các vai trò.
3. **Cross-Model Review & Jira Automation trên Antigravity**: Đảo họ Model **Gemini 4 ↔ Claude 5.5** giữa người viết code và người chấm bài để triệt tiêu điểm mù bảo mật Vector RBAC / Cache Isolation / Virtual Threads, đồng bộ tự động vòng đời `DA-*` (`To Do` ➔ `In Progress` ➔ `Verify / Test` ➔ `Done`) qua MCP `jira-cwb`.

---

## 1. Các Lệnh Khởi Động Nhanh Trên Antigravity

| Mục đích | Câu lệnh gõ vào ô chat Antigravity |
|---|---|
| **Khởi động ticket mới (Bước 1)** | `/ai-workflow DA-19` *(hoặc `Làm task DA-19`)* |
| **Xem bảng tiến độ toàn dự án (`THE BOARD`)** | `/ai-workflow board` *(hoặc chạy terminal: `node .ai-workflow/scripts/verify.cjs board`)* |
| **Kiểm tra trạng thái 1 ticket đang làm dở** | `/ai-workflow DA-18` |

Ở cuối mỗi bước, Agent tự động cập nhật `state.md` + `BOARD.md` và in ra **Hộp Chuyển Tiếp** gồm: **Model khuyên chọn**, **mẹo mở New Chat / `/compact`** và **câu lệnh copy-paste** cho bước kế tiếp.

---

## 2. Vòng Đời 6 Bước, 4 Vai Trò & Các Cổng Kiểm Soát (`Undo-Priced Gates`)

```text
[BẠN gõ: "/ai-workflow DA-XX"]
       │
       ▼
[Bước 1: Goal, Research & Plan — Chief of Staff + Researcher (01-plan.md)]
  • Đọc MEMORY.md + kéo live Jira qua `jira-cwb` ➔ chuyển `In Progress` + lưu jira.md
  • Kiểm tra working tree sạch ➔ tạo nhánh từ `main` (feat/ | bugfix/ | chore/ | docs/)
  • Ủy quyền subagent `Researcher` chạy `/how` + `/why` (git log) + chọn Canonical Examples
  • Xuất plan.md (6-point Startup Checklist) + state.md + đồng bộ BOARD.md
       │
       ▼ ──► [GATE 1]: Duyệt plan.md ➔ Khuyên mở New Chat sạch (hoặc `/compact`) & đổi sang Model Engineer
[Bước 2: Build — Engineer (02-implement.md)]
  • Đọc state.md + plan.md + Canonical Repo Examples (không cần đọc lại lịch sử mò file Bước 1)
  • TDD + Java 21 / Spring AI 1.0.3 / Next.js + Anti-Slop (`Comment-Sicko` ngay lúc viết)
  • Chạy 1 lệnh: `node .ai-workflow/scripts/verify.cjs check --verify` (Unit + Testcontainers + JaCoCo >= 75%)
  • Xuất implementation-report.md + cập nhật state.md (`Ready for Review`)
       │
       ▼ ──► [GATE 2]: Mở New Chat sạch + Đảo sang họ Model Tier S đối lập (Gemini ↔ Claude)
[Bước 3: Independent Review — Checker + 3 Subagents (03-review.md)]
  • Chạy `verify.cjs diff` (`merge-base main HEAD` + uncommitted + file mới `??` + quét hygiene/secrets)
  • 3 Subagents độc lập: `Standards & Domain` + `Spec & Test` + `Ponytail & Comment-Sicko`
  • Chuyển Jira sang `Verify / Test` + xuất review-01.md chuẩn gọn `FAILED / SAW / FIX` (KHÔNG sửa code)
       │
       ▼ ──► [GATE 3]: Phân nhánh theo kết quả review-01.md
   ┌───┴──────────────────────────────────────────┐
   │ CHANGES REQUESTED                            │ APPROVED (0 Must/Should Fix)
   ▼                                              │
[Bước 4: Fix — Engineer (04-fix.md)]              │
  • Đọc gọn state.md + các khối FAILED/SAW/FIX    │
  • Tái hiện lỗi ➔ vá gốc ➔ verify.cjs check      │
  • Cầu chì 2-Strike: kẹt 2 lần ➔ `Blocked`       │
   │                                              │
   └──────────────► [Bước 5: Final Gate & Prevention Audit — Final Checker (05-final-review.md)] ◄──┘
                      • Chạy thật `node .ai-workflow/scripts/verify.cjs check --verify`
                      • Tái xác minh 100% AC + Scoped Ponytail/Comment-Sicko + 4-Step Prevention Audit
                      • Xuất final-review.md (`READY FOR PRE-COMMIT` hoặc cầu chì `Blocked` nếu Strikes >= 2)
                            │
                            ▼ ──► [GATE 4]: Đổi sang Model siêu nhẹ (Hydrogen / Flash)
                    [Bước 6: Deliver & Reuse — Release & Memory Keeper (06-precommit.md)]
                      • Chạy `verify.cjs diff` + soạn PR bằng skill `pr` + đăng comment & chuyển Jira `Done`
                      • Chắt lọc Decision/Source/Lesson mới (nếu có) vào `.ai-workflow/MEMORY.md`
                      • Đánh dấu `Done` trên `BOARD.md` + Dừng tại `The Undo Gate` (in lệnh Git cho BẠN)
```

---

## 3. Công Cụ Kiểm Chứng 1 Lệnh (`verify.cjs`)

```powershell
# 1. Xem phạm vi diff (merge-base main HEAD + uncommitted + file mới ??) & quét vệ sinh/secrets (<0.2s):
node .ai-workflow/scripts/verify.cjs diff

# 2. Chạy nhanh targeted backend tests trong lúc TDD:
node .ai-workflow/scripts/verify.cjs check --test=Week2CrossGateRbacIntegrationTest

# 3. Chạy trọn bộ Quality Gates (Hygiene + .\mvnw.cmd verify [Unit + Testcontainers + JaCoCo >= 75%] + Frontend):
node .ai-workflow/scripts/verify.cjs check --verify

# 4. Chạy kèm Playwright E2E (luôn tự động ép --headed / headless: false):
node .ai-workflow/scripts/verify.cjs check --verify --e2e e2e/chat.spec.ts

# 5. Đồng bộ bảng trạng thái toàn bộ các ticket DA-* (.ai-workflow/BOARD.md):
node .ai-workflow/scripts/verify.cjs board

# 6. Tự kiểm thử chính công cụ verify.cjs:
node --test .ai-workflow/scripts/verify.test.cjs
```

---

## 4. Chiến Lược Chọn Model & Cắt Lịch Sử Chat (`Anti-Re-Read Tax`)

Công thức chi phí token thực tế là **`Số vòng gọi công cụ × Độ dài lịch sử chat đọc lại`**. Nhờ đã có `state.md` (≤ 20 dòng) + `MEMORY.md`, bạn **nên mở New Chat sạch (hoặc gõ `/compact`)** mỗi khi chuyển vai:

| Bước | Vai trò (`pstack`) | Model Khuyên Dùng Số 1 (Cross-Model) | Phương Án Tiết Kiệm | Ngữ Cảnh Chat |
|---|---|---|---|---|
| **Bước 1: Plan** | `Chief` + `Researcher` | **Gemini 4 Carbon (High)** hoặc **Claude Opus 5.5 (High)** | **Gemini 4 Argon (High)** | Mở chat mới cho mỗi ticket `DA-*` |
| **Bước 2: Build** | `Engineer` | **Claude Sonnet 5.5 (High / Medium)** hoặc **Orchestrator (Carbon)** | **Orchestrator (Argon)** / **Gemini 4 Argon (High)** | **Mở New Chat sạch** (hoặc `/compact`) |
| **Bước 3: Review** | `Checker` (3 Subagents) | **Đảo họ Tier S với Bước 2:**<br>• Bước 2 Claude ➔ **Gemini 4 Carbon (High)**<br>• Bước 2 Gemini ➔ **Claude Opus 5.5 (High)** | **Gemini 4 Carbon (Medium / High)** | **Bắt buộc mở New Chat sạch** |
| **Bước 4: Fix** | `Engineer` | **Claude Sonnet 5.5 (Medium)** | **Gemini 4 Argon (Medium)** / **Orchestrator (Argon)** | **Mở New Chat sạch** (chỉ nạp `state.md` + `review-01.md`) |
| **Bước 5: Final** | `Final Checker` | **Gemini 4 Argon (High / Medium)** hoặc **Claude Sonnet 5.5 (Medium)** | **Gemini 4 Argon (Medium)** | Mở New Chat sạch hoặc `/compact` |
| **Bước 6: Deliver** | `Release & Memory` | **Gemini 4 Hydrogen (Medium / High)** | **Gemini 3.8 Flash (High)** | Dùng ngay chat hiện tại |

---

## 5. Cấu Trúc Thư Mục `.ai-workflow` (v4.0)

```text
DocuMind-AI/
├── AGENTS.md                                      # Luật gốc bất biến của dự án (tracked in Git)
├── .agents/
│   ├── rules/
│   │   └── 00-documind.md                         # Con trỏ tự động nối AGENTS.md + /ai-workflow
│   └── skills/
│       ├── ai-workflow/SKILL.md                   # Slash router `Chief of Staff` (`/ai-workflow`)
│       ├── spring-ai-rag-pipeline/SKILL.md        # Chuẩn Ingestion, PgVector, RBAC Filter & SSE
│       ├── enterprise-rbac-and-cache-testing/     # Chuẩn Testcontainers RBAC, Redis Cache & Bucket4j
│       ├── rag-kpi-evaluator/SKILL.md             # Chuẩn đo KPI Golden Dataset
│       ├── code-review/SKILL.md                   # Review 2 trục độc lập (Standards + Spec)
│       ├── ponytail-review/SKILL.md               # Review chịu tải thực tế, tài nguyên & anti-bloat
│       └── pr/SKILL.md                            # Chuẩn soạn Pull Request kèm bằng chứng
└── .ai-workflow/
    ├── README.md                                  # Cẩm nang vận hành v4.0 (tracked in Git)
    ├── MEMORY.md                                  # Bộ nhớ dài hạn chống mục nát (tracked in Git)
    ├── BOARD.md                                   # Bảng 5 trạng thái tự động đồng bộ (gitignored)
    ├── scripts/
    │   ├── verify.cjs                             # CLI kiểm chứng 1 lệnh (diff | check | board)
    │   └── verify.test.cjs                        # Unit tests bảo vệ verify.cjs (node:test)
    ├── prompts/                                   # 7 file quy trình chuẩn v4.0 (tracked in Git)
    │   ├── 00-shared-rules.md
    │   ├── 01-plan.md
    │   ├── 02-implement.md
    │   ├── 03-review.md
    │   ├── 04-fix.md
    │   ├── 05-final-review.md
    │   └── 06-precommit.md
    ├── rules/
    │   └── domain-review.md                       # Checklist nghiệp vụ RAG & bảo mật của DocuMind AI
    └── tasks/
        ├── .gitkeep                               # Giữ khung thư mục trên Git
        └── DA-XX/                                 # Hồ sơ nháp từng ticket (gitignored)
            ├── state.md                           # Gói bàn giao siêu gọn ≤ 20 dòng (mới ở v4.0)
            ├── jira.md
            ├── plan.md
            ├── implementation-report.md
            ├── review-01.md
            ├── fix-report.md
            ├── final-review.md
            └── precommit.md
```
