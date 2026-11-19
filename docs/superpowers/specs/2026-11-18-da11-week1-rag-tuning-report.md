# DA-11 — Week 1 RAG Empirical Calibration & Milestone 1 Tuning Report (`bge-m3`)

- **Ticket:** `DA-11` (`[Day 7] Kiểm thử Tích hợp Tuần 1 (Testcontainers) & Tinh chỉnh RAG`)
- **Date:** `2026-11-18`
- **Embedding Model:** `Ollama bge-m3:latest` (`1024` dimensions, `pgvector` HNSW `vector_cosine_ops`)
- **Infrastructure Stack:** Testcontainers (`pgvector/pgvector:pg16`, `redis:7-alpine`, `cgr.dev/chainguard/minio`) + Spring Boot 3.4.5 + Spring AI 1.0.3 GA
- **Benchmark Harness:** `BgeM3Week1RagTuningBenchmarkTest` (`-Dsmoke=true`)

---

## 1. Bilingual Sample Enterprise Corpus (5 Documents across 4 Formats)

| # | Filename | Format | Dept | Access Level | Language | Key Verifiable Facts |
|---|---|---|---|---|---|---|
| 1 | `vn-hr-handbook-2026.pdf` | `.pdf` (3 pages) | `HR` | `DEPARTMENT` | Vietnamese | Page 1: `60 ngày` thử việc, `85%` lương; Page 2: `14 ngày phép` + `1 ngày/5 năm`; Page 3: `PVI Care` `120 triệu đồng`, khám `tháng 9` |
| 2 | `vn-sales-expense-policy.docx` | `.docx` (`Heading1/2`) | `SALES` | `DEPARTMENT` | Vietnamese | Khách sạn `1.500.000 VNĐ`/đêm, phụ cấp `350.000 VNĐ`/ngày, hoàn ứng `7 ngày làm việc`, hoa hồng `ngày 15` |
| 3 | `en-infosec-access-standard.md` | `.md` (fenced YAML + `##`) | `IT` | `PUBLIC` | English | Passphrase `>= 14 characters`, FIDO2/WebAuthn MFA, API key rotation `90 days`, P1 SOC escalation `15 minutes`, retention `365 days` |
| 4 | `en-cloud-release-runbook.pdf` | `.pdf` (3 pages) | `IT` | `CONFIDENTIAL` | English | Page 1: Canary `10%` (15m) -> `50%` (30m) -> `100%`; Page 2: Rollback `5xx > 1.0%` or `p95 > 800 milliseconds`; Page 3: Postmortem `48 hours` |
| 5 | `bilingual-hybrid-work-charter.txt` | `.txt` (`CHƯƠNG` / `Điều`) | `HR` | `PUBLIC` | Bilingual VI/EN | Remote `2 ngày mỗi tuần` (`2 days per week`), core hours `09:30–16:30 ICT`, mandatory `WireGuard VPN`, screen lock `5 minutes` |

---

## 2. Evaluation Query Suite (`week1_milestone_queries.jsonl` — 20 Queries)

- **`IN_SCOPE` (`14` queries):** Covers Vietnamese (`IN-01`..`IN-06`), English (`IN-07`, `IN-08`, `IN-10`, `IN-11`, `IN-12`), cross-lingual VI→EN (`IN-09`), and bilingual (`IN-13`, `IN-14`) across all 5 documents, 3 departments, and 3 roles (`STANDARD_EMPLOYEE`, `DEPARTMENT_MANAGER`, `SYSTEM_ADMIN`).
- **`OOS_EXTERNAL` (`3` queries):** Real-time stock/Bitcoin price, 2035 gold/oil forecast, 2022 World Cup result.
- **`OOS_RBAC` (`3` queries):** Cross-department / `CONFIDENTIAL` probes verifying zero vector and zero LLM leakage (`HR STANDARD_EMPLOYEE` querying `IT CONFIDENTIAL`, `SALES STANDARD_EMPLOYEE` querying `HR DEPARTMENT`, `HR STANDARD_EMPLOYEE` querying `SALES DEPARTMENT`).

---

## 3. Live `bge-m3` Grid Search Matrix (`chunkSize/overlap` × `similarityThreshold`)

### Baseline Before Tuning (`800 / 150 / 0.65`)
- **Live E2E Result:** **`Hit@5 = 78.57%` (`11/14`)** — **FAILED** the Milestone 1 `>= 85%` KPI gate; `HallucinationRate = 0.00%` (`0/6`).
- **Root Causes Observed on `bge-m3`:**
  1. Cross-lingual/mixed-language chunk in `bilingual-hybrid-work-charter.txt` (`IN-14`) scored **`0.6193`** when bundled inside an `800`-token window (below `0.65`), whereas splitting at `512/100` isolated `Điều 3` into a focused chunk and raised its cosine score to **`0.6294`**.
  2. `en-cloud-release-runbook.pdf` Page 1 (`IN-10`) scored **`0.6231`** (below `0.65`).
  3. Multi-part query `IN-05` (`vn-sales-expense-policy.docx`) retrieved `Điều 1.2` (`0.6657`), while `Điều 2.1` (`7 ngày làm việc`, rank 2) fell between `0.62` and `0.65`, getting cut off at `0.65`.

### Empirical Grid Search Summary (Live `Ollama bge-m3`, RBAC Filtered)

| Chunk / Overlap | Total Chunks | Threshold | `Hit@5` | `MRR@5` | `OOS_Leak` | `MinInHit` | `MaxOOS` | Margin ($\Delta$) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `512 / 100` | `18` | `0.48` | `100.0%` | `0.964` | `33.3%` | `0.6231` | `0.5418` | `+0.0813` |
| `512 / 100` | `18` | `0.52` | `100.0%` | `0.964` | `33.3%` | `0.6231` | `0.5418` | `+0.0813` |
| `512 / 100` | `18` | `0.55` | `100.0%` | `0.964` | `0.0%` | `0.6231` | `0.5418` | `+0.0813` |
| **`512 / 100`** | **`18`** | **`0.58`** | **`100.0%`** | **`0.964`** | **`0.0%`** | **`0.6231`** | **`0.5418`** | **`+0.0813`** |
| `512 / 100` | `18` | `0.60` | `100.0%` | `0.964` | `0.0%` | `0.6231` | `0.5418` | `+0.0813` |
| `512 / 100` | `18` | `0.62` | `100.0%` | `0.964` | `0.0%` | `0.6231` | `0.5418` | `+0.0813` |
| `512 / 100` | `18` | `0.65` | `78.6%` | `0.786` | `0.0%` | `0.6231` | `0.5418` | `+0.0813` |
| `512 / 100` | `18` | `0.68` | `64.3%` | `0.643` | `0.0%` | `0.6231` | `0.5418` | `+0.0813` |
| `650 / 120` | `16` | `0.58` | `100.0%` | `0.964` | `0.0%` | `0.6193` | `0.5418` | `+0.0775` |
| `650 / 120` | `16` | `0.62` | `92.9%` | `0.893` | `0.0%` | `0.6193` | `0.5418` | `+0.0775` |
| `650 / 120` | `16` | `0.65` | `78.6%` | `0.786` | `0.0%` | `0.6193` | `0.5418` | `+0.0775` |
| `800 / 150` | `16` | `0.58` | `100.0%` | `0.964` | `0.0%` | `0.6193` | `0.5418` | `+0.0775` |
| `800 / 150` | `16` | `0.62` | `92.9%` | `0.893` | `0.0%` | `0.6193` | `0.5418` | `+0.0775` |
| `800 / 150` | `16` | `0.65` | `78.6%` | `0.786` | `0.0%` | `0.6193` | `0.5418` | `+0.0775` |

### Per-Query Top-1 Cosine Scores (`512 / 100` Winner)

| Query ID | Category | Top-1 `bge-m3` Score | Top-1 Returned File | Match Rank@5 |
|---|---|---:|---|---:|
| `IN-01` | `IN_SCOPE` | `0.7209` | `vn-hr-handbook-2026.pdf` | `1` |
| `IN-02` | `IN_SCOPE` | `0.7683` | `vn-hr-handbook-2026.pdf` | `1` |
| `IN-03` | `IN_SCOPE` | `0.7540` | `vn-hr-handbook-2026.pdf` | `1` |
| `IN-04` | `IN_SCOPE` | `0.7723` | `vn-sales-expense-policy.docx` | `1` |
| `IN-05` | `IN_SCOPE` | `0.6657` | `vn-sales-expense-policy.docx` | `2` |
| `IN-06` | `IN_SCOPE` | `0.7832` | `vn-sales-expense-policy.docx` | `1` |
| `IN-07` | `IN_SCOPE` | `0.7270` | `en-infosec-access-standard.md` | `1` |
| `IN-08` | `IN_SCOPE` | `0.7511` | `en-infosec-access-standard.md` | `1` |
| `IN-09` | `IN_SCOPE` (Cross-lingual VI→EN) | `0.6859` | `en-infosec-access-standard.md` | `1` |
| `IN-10` | `IN_SCOPE` | `0.6231` | `en-cloud-release-runbook.pdf` | `1` |
| `IN-11` | `IN_SCOPE` | `0.6540` | `en-cloud-release-runbook.pdf` | `1` |
| `IN-12` | `IN_SCOPE` | `0.6595` | `en-cloud-release-runbook.pdf` | `1` |
| `IN-13` | `IN_SCOPE` | `0.7683` | `bilingual-hybrid-work-charter.txt` | `1` |
| `IN-14` | `IN_SCOPE` (Bilingual EN→VI/EN) | `0.6294` | `bilingual-hybrid-work-charter.txt` | `1` |
| `OOS-EXT-01` | `OOS_EXTERNAL` | `0.3713` | `bilingual-hybrid-work-charter.txt` | `0` |
| `OOS-EXT-02` | `OOS_EXTERNAL` | `0.3438` | `vn-sales-expense-policy.docx` | `0` |
| `OOS-EXT-03` | `OOS_EXTERNAL` | `0.2956` | `bilingual-hybrid-work-charter.txt` | `0` |
| `OOS-RBAC-01` | `OOS_RBAC` | `0.5418` | `en-infosec-access-standard.md` | `0` |
| `OOS-RBAC-02` | `OOS_RBAC` | `0.5312` | `vn-sales-expense-policy.docx` | `0` |
| `OOS-RBAC-03` | `OOS_RBAC` | `0.4786` | `vn-hr-handbook-2026.pdf` | `0` |

---

## 4. Final Milestone 1 Configuration Lock & Re-Verification

- **Selected Defaults:**
  - `documind.ingestion.chunking.chunk-size`: **`512`** (`${INGESTION_CHUNK_SIZE:512}`)
  - `documind.ingestion.chunking.chunk-overlap`: **`100`** (`${INGESTION_CHUNK_OVERLAP:100}`)
  - `documind.rag.retrieval.similarity-threshold`: **`0.58`** (`${RAG_SIMILARITY_THRESHOLD:0.58}`)
  - `documind.rag.retrieval.top-k`: **`5`** (`${RAG_TOP_K:5}`)
- **Selection Rationale:**
  - `512 / 100` maximizes the separation margin ($\Delta = +0.0813$) between the lowest `IN_SCOPE` hit (`0.6231`) and highest `OOS` lexical overlap (`0.5418`).
  - `0.58` sits centrally in `[0.5418, 0.6231]` with `+0.0382` guard margin above `MaxOOS` and `+0.0431` headroom below `MinInHit`.
- **Post-Calibration Live `bge-m3` End-to-End Verification (`BgeM3Week1RagTuningBenchmarkTest`):**
  - `E2E Hit@5 = 100.00% (14/14)` (Target: `>= 85%`)
  - `HallucinationRate = 0.00% (0/6)` (Target: `== 0%`)

### Operational Rollout & Cache Invalidation Runbook
- **Preventing Stale Near-Threshold Abstentions (`0.65` -> `0.58`):** Semantic/exact cache keys bind to `(rbac_scope, scope_version, query_hash)` rather than `similarityThreshold`. When deploying this threshold reduction onto an environment with populated caches, execute `UPDATE departments SET cache_version = cache_version + 1;` and flush L1 exact-cache entries so queries that previously abstained in `[0.58, 0.65)` are re-evaluated immediately instead of serving cached abstentions until TTL expiry.
- **Chunk Window Alignment (`800/150` -> `512/100`):** Pre-existing ingested documents chunked at `800/150` should be re-indexed (or re-uploaded in pre-production environments) so stored vector granularity matches the calibrated `512/100` window.
