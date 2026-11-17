# DocuMind AI — AI Development Workflow (v3.0: Antigravity Native & Cross-Model Edition)

Quy trình phát triển phần mềm chuẩn 6 bước được may đo riêng cho **DocuMind AI** (Java 21, Spring Boot 3.4, Spring AI 1.0.3 GA, PostgreSQL 16 `pgvector`, Redis 7, MinIO, Next.js) trên **Google Antigravity**, kết hợp:
- **Slash Skill khởi động nhanh (`/ai-workflow`)** tại `.agents/skills/ai-workflow/SKILL.md`.
- **Kỷ luật 1 Lượt = 1 Bước (Hard Stop)** kèm **Hộp Gợi Ý Câu Lệnh + Model Chuyển Tiếp Tự Động**.
- **Chiến lược Đảo Họ Model (Cross-Model Review)** giữa **Gemini 4** và **Claude 5.5** nhằm triệt tiêu điểm mù khi thẩm định bảo mật Vector RBAC / Cache Isolation / Virtual Threads và chia tải đều 2 bể hạn mức (Quota Pools) trên Antigravity.

---

## 1. Cách Khởi Động Nhanh Nhất Trên Antigravity

Mở khung chat Antigravity, chọn Model cho Bước 1 (xem bảng mục 3) và gõ **1 câu ngắn gọn**:

```text
/ai-workflow DA-9
```
*(hoặc gõ tự nhiên: `Làm task DA-9 theo .ai-workflow`)*

Từ đó về sau, **ở cuối mỗi bước Agent sẽ tự động in ra một Hộp Chuyển Tiếp** gồm:
1. **Model khuyên chọn** trên thanh Model Selection cho bước kế tiếp.
2. **Câu lệnh chính xác** để bạn chỉ việc copy-paste vào ô chat.

---

## 2. Sơ Đồ Vòng Đời 6 Bước & Các Cổng Kiểm Soát (Human Gates)

```text
[BẠN gõ: "/ai-workflow DA-XX"]
       │
       ▼
[Bước 1: Plan (01-plan.md)]
  • Kéo Jira `DA-XX` qua `jira-cwb` ➔ tự động chuyển Jira sang **`In Progress`** + lưu jira.md
  • Kiểm tra working tree sạch ➔ tạo nhánh từ `main` (feat/ | bugfix/ | chore/ | docs/)
  • Khảo sát code + Flyway SQL + chọn Skills chuyên ngành ➔ xuất plan.md
       │
       ▼ ──► [GATE 1]: Bạn duyệt plan.md, đổi sang Model Implement & dán prompt gợi ý
[Bước 2: Implement (02-implement.md)]
  • TDD + Java 21 / Spring AI 1.0.3 / Next.js theo Skills Blueprint
  • Chạy thật: `.\mvnw.cmd verify` (Unit + Testcontainers pgvector/redis/minio) + Playwright `--headed`
  • Xuất implementation-report.md (KHÔNG commit)
       │
       ▼ ──► [GATE 2]: Đổi sang họ Model Tier S đối lập (khuyên mở New Chat sạch) & dán prompt gợi ý
[Bước 3: Independent Review (03-review.md + domain-review.md)]
  • Quét trọn vẹn `git diff $(git merge-base main HEAD)` + toàn bộ file mới untracked (`??`)
  • Chạy 3 Subagents độc lập: Standards & Domain + Spec & Test + Ponytail
  • Tự động chuyển Jira sang **`Verify / Test`** + xuất review-01.md (KHÔNG sửa code)
       │
       ▼ ──► [GATE 3]: Xem kết quả review-01.md
   ┌───┴──────────────────────────────────────┐
   │ CHANGES REQUESTED (Có findings)          │ APPROVED (0 lỗi blocking)
   ▼                                          │
[Bước 4: Fix (04-fix.md)]                     │
  • Vá tận gốc + thêm regression test thật    │
  • Chạy lại `.\mvnw.cmd verify` ➔ fix-report │
   │                                          │
   └──────────────► [Bước 5: Final Review (05-final-review.md)] ◄───┘
                      • Đối chiếu 100% findings đã RESOLVED & 100% AC / AGENTS.md PASS
                      • Scoped ponytail-review + tự chạy lại Quality Gates
                      • Xuất final-review.md
                            │
                            ▼ ──► [GATE 4]: Đạt `READY FOR PRE-COMMIT` ➔ chuyển sang Model nhẹ (Hydrogen/Flash)
                    [Bước 6: Pre-Commit & Release (06-precommit.md)]
                      • Quét sạch git status / API keys / dev-caller-fallback / ports / debug logs
                      • Soạn PR bằng skill `pr` + đăng comment Jira (`jira_add_comment`)
                      • Tự động chuyển Jira sang **`Done`** + xuất precommit.md + in lệnh Git cho BẠN
```

---

## 3. Thứ Tự Sức Mạnh & Chiến Lược Chọn Model Trên Antigravity

### 3.1. Xếp hạng thực tế các Model trên Antigravity (Từ Mạnh nhất ➔ Nhẹ/Nhanh nhất)
1. **Tier S — Flagship Frontier (Đỉnh nhất về suy luận sâu, kiến trúc & bắt bug bảo mật/đồng thời)**:
   - **Gemini 4 Carbon (`High` > `Medium` > `Low`)**: Flagship mạnh nhất của Google DeepMind (đặc biệt sắc bén về kiến trúc hệ thống, cybersecurity/RBAC, I/O stream và concurrency Virtual Threads).
   - **Claude Opus 5.5 (`High` > `Medium` > `Low`)**: Siêu mô hình đầu bảng của Anthropic (đối trọng ngang ngửa với Carbon, cực mạnh khi đọc hiểu spec phức tạp và viết code lớn ít sót yêu cầu).
2. **Tier S- / A+ — Chế độ Nhạc trưởng Đa Model (Tối ưu tốc độ & tiết kiệm ~24% Quota)**:
   - **Orchestrator (Carbon)**: Tự động dùng **Carbon** lập kế hoạch/gỡ ca khó + giao việc sửa file/chạy lệnh Maven lặp lại cho **Hydrogen**.
   - **Orchestrator (Argon)**: Dùng **Argon** làm nhạc trưởng + **Hydrogen** thực thi.
3. **Tier A — Balanced Frontier Engineering (Chủ lực viết code, TDD, UI hằng ngày)**:
   - **Claude Sonnet 5.5 (`High` > `Medium` > `Low`)** & **Gemini 4 Argon (`High` > `Medium` > `Low`)**.
4. **Tier B — Fast Execution Engine (Siêu nhanh, nhẹ, tiết kiệm quota)**:
   - **Gemini 4 Hydrogen (`XHigh` > `High` > `Medium` > `Low`)**: Model thực thi tốc độ cao nhẹ nhất họ Gemini 4.
5. **Tier C — Ultra-Light / Phụ trợ**:
   - **Gemini 3.8 Flash (`High` > `Medium` > `Low`)** *(mặc định cho `browser` subagent)* & **GPT-OSS 120B (`Medium`)**.

### 3.2. Bảng chọn Model theo từng bước (Chia đều 2 bể Quota Gemini & Claude)

| Bước | File Prompt | Khuyên dùng số 1 (Cross-Model Chuẩn) | Phương án 2 (Thuần Gemini / Tiết kiệm) | Có nên mở New Chat? |
|---|---|---|---|---|
| **Bước 1: Plan** | `01-plan.md` | **Gemini 4 Carbon (High)** hoặc **Claude Opus 5.5 (High)** | **Gemini 4 Argon (High)** *(task vừa/nhỏ)* | Bắt đầu chat mới cho mỗi ticket `DA-*` |
| **Bước 2: Implement** | `02-implement.md` | **Claude Sonnet 5.5 (High / Medium)** hoặc **Orchestrator (Carbon)** | **Orchestrator (Argon)** / **Gemini 4 Argon (High)** | Giữ nguyên chat Bước 1 (đổi Model nếu cần) |
| **Bước 3: Review** | `03-review.md` | **Đảo ngược họ Tier S với Bước 2:**<br>• Bước 2 Claude ➔ **Gemini 4 Carbon (High)**<br>• Bước 2 Gemini ➔ **Claude Opus 5.5 (High)** | **Gemini 4 Carbon (Medium / High)** | **Nên mở New Chat sạch** để triệt tiêu thiên kiến người viết code |
| **Bước 4: Fix** | `04-fix.md` | **Claude Sonnet 5.5 (Medium)** | **Gemini 4 Argon (Medium)** / **Orchestrator (Argon)** | Quay lại chat Bước 2 hoặc dùng tiếp chat hiện tại |
| **Bước 5: Final Review** | `05-final-review.md` | **Gemini 4 Argon (High / Medium)** hoặc **Claude Sonnet 5.5 (Medium)** | **Gemini 4 Argon (Medium)** | Mở New Chat sạch hoặc đổi sang Model Reviewer |
| **Bước 6: Pre-Commit** | `06-precommit.md` | **Gemini 4 Hydrogen (Medium / High)** | **Gemini 3.8 Flash (High)** | Dùng ngay ô chat hiện tại cho nhanh |

> 💡 **Lưu ý về Subagents ở Bước 3**: Công cụ `invoke_subagent` trên Antigravity mặc định chạy với `Model: "inherit"` (thừa kế đúng Model bạn đang chọn trên thanh công cụ ở lượt đó). Vì vậy chọn **Tier S (`Carbon High` hoặc `Opus 5.5 High`)** ở Bước 3 giúp cả 3 Subagents (`Standards & Domain`, `Spec & Test`, `Ponytail`) đều đạt độ sắc bén cao nhất.

---

## 4. Cấu Trúc Thư Mục `.ai-workflow` & Skills Liên Kết

```text
DocuMind-AI/
├── AGENTS.md                                      # Luật gốc bất biến của dự án (tracked in Git)
├── .agents/
│   ├── rules/
│   │   └── 00-documind.md                         # Con trỏ tự động nối AGENTS.md + /ai-workflow
│   └── skills/                                    # 7 skills tinh gọn (không gây tràn Context Budget)
│       ├── ai-workflow/SKILL.md                   # Slash router `/ai-workflow`
│       ├── spring-ai-rag-pipeline/SKILL.md        # Chuẩn Ingestion, PgVector, RBAC Filter & SSE
│       ├── enterprise-rbac-and-cache-testing/     # Chuẩn Testcontainers RBAC, Redis Cache & Bucket4j
│       ├── rag-kpi-evaluator/SKILL.md             # Chuẩn đo KPI Golden Dataset (Hit@5, Hallucination, Cache)
│       ├── code-review/SKILL.md                   # Review 2 trục độc lập (Standards + Spec)
│       ├── ponytail-review/SKILL.md               # Review chịu tải thực tế, tài nguyên & anti-bloat
│       └── pr/SKILL.md                            # Chuẩn soạn Pull Request kèm bằng chứng
└── .ai-workflow/
    ├── README.md                                  # Cẩm nang vận hành này (tracked in Git)
    ├── prompts/                                   # 7 file quy trình chuẩn (tracked in Git)
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
        └── DA-XX/                                 # Hồ sơ nháp từng ticket (được .gitignore tự động ẩn)
            ├── jira.md
            ├── plan.md
            ├── implementation-report.md
            ├── review-01.md
            ├── fix-report.md                      # (Chỉ sinh ra khi review-01.md có findings)
            ├── final-review.md
            └── precommit.md
```

---

## 5. Bảng Tra Nhanh Câu Lệnh Các Bước (Khi Gõ Tay Hoặc Đang Làm Dở Giữa Chừng)

| Tình huống | Câu lệnh gửi vào Antigravity |
|---|---|
| **Bắt đầu ticket mới (Bước 1)** | `/ai-workflow DA-XX` |
| **Chạy Bước 2 (Implement)** | `Duyệt plan. Thực hiện Bước 2 (Implement) cho task DA-XX theo .ai-workflow/prompts/02-implement.md và các skills trong blueprint của plan.md.` |
| **Chạy Bước 3 (Review độc lập)** | `Thực hiện Bước 3 (Independent Review) cho task DA-XX theo .ai-workflow/prompts/03-review.md. Khởi chạy các subagents độc lập (Standards, Spec, Ponytail) đối chiếu jira.md, AGENTS.md và domain-review.md, lưu kết quả vào review-01.md.` |
| **Chạy Bước 4 (Fix lỗi review)** | `Thực hiện Bước 4 (Fix) cho task DA-XX theo .ai-workflow/prompts/04-fix.md. Xử lý tận gốc các findings trong .ai-workflow/tasks/DA-XX/review-01.md, bổ sung regression tests và lưu báo cáo vào fix-report.md.` |
| **Chạy Bước 5 (Final Review)** | `Thực hiện Bước 5 (Final Review) cho task DA-XX theo .ai-workflow/prompts/05-final-review.md. Kiểm tra lại toàn bộ findings từ review-01.md và fix-report.md, chạy các Quality Gates và lưu kết quả vào final-review.md.` |
| **Chạy Bước 6 (Pre-Commit & PR)** | `Thực hiện Bước 6 (Pre-Commit) cho task DA-XX theo .ai-workflow/prompts/06-precommit.md. Dùng skill pr soạn nội dung PR, đăng comment tổng kết và chuyển trạng thái ticket sang Done trên Jira qua jira-cwb, sau đó lưu báo cáo vào precommit.md.` |
