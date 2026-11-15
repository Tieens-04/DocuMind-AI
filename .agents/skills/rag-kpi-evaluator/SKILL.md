---
name: rag-kpi-evaluator
description: Use when building, running, or reviewing the Golden Dataset evaluation suite or measuring RAG KPIs such as Top-5 Retrieval Precision, Hallucination Rate, or Redis Cache Hit Ratio in DocuMind AI.
---

# RAG Golden Dataset & KPI Evaluator

## Overview
Defines the Golden Dataset schema, mathematically sound metric formulas, and automated pass/fail gates required to prove DocuMind AI's three quantitative KPIs (`Retrieval Hit@5 >= 85%`, `Hallucination Rate == 0%`, `Cache Hit Ratio >= 30%-40%`).

## Golden Dataset Schema (`src/test/resources/eval/golden_dataset.jsonl`)

Never anchor ground truth to volatile auto-generated chunk UUIDs (`chunk_id`). Anchor every test case to **content-stable source metadata** (`expectedFile`, `expectedPage`, `expectedKeyPhrase`) and caller RBAC identity:

```json
{"id":"IN-01","category":"IN_SCOPE","role":"STANDARD_EMPLOYEE","dept":"HR","query":"Thời gian thử việc tối đa đối với vị trí kỹ sư là bao lâu?","expectedFile":"hr_onboarding_sop.pdf","expectedPage":4,"expectedKeyPhrase":"60 ngày"}
{"id":"OOS-EXT-01","category":"OOS_EXTERNAL","role":"STANDARD_EMPLOYEE","dept":"HR","query":"Dự báo giá vàng thế giới năm 2030 là bao nhiêu?"}
{"id":"OOS-RBAC-01","category":"OOS_RBAC","role":"STANDARD_EMPLOYEE","dept":"ENG","query":"Quỹ thưởng điều hành bí mật của phòng Tài chính Q4 là bao nhiêu?","forbiddenPhrase":"2.5 tỷ"}
{"id":"CACHE-01","category":"CACHE_WORKLOAD","role":"STANDARD_EMPLOYEE","dept":"HR","query":"Quy trình xin nghỉ phép năm như thế nào?","expectCacheHit":false,"crossScope":false}
{"id":"CACHE-02","category":"CACHE_WORKLOAD","role":"STANDARD_EMPLOYEE","dept":"HR","query":"Hướng dẫn đăng ký nghỉ phép hằng năm?","expectCacheHit":true,"crossScope":false}
{"id":"CACHE-03","category":"CACHE_WORKLOAD","role":"STANDARD_EMPLOYEE","dept":"FIN","query":"Quy trình xin nghỉ phép năm như thế nào?","expectCacheHit":false,"crossScope":true}
```

## Exact Metric Formulas & Pass/Fail Gates

1. **Question-Level Top-5 Retrieval Precision / Hit Rate (`>= 85%`):**
   - Spec definition: *"85% of test questions retrieve the chunk containing the answer within Top-5 results."*
   - A question $q \in Q_{\text{IN\_SCOPE}}$ is a **Hit** (`1`) iff at least one chunk in `top5(q)` matches `expectedFile`, `expectedPage`, and contains `expectedKeyPhrase` (case-insensitive normalized).
   - Formula: `HitRate@5 = sum(isHit(q) for q in Q_IN_SCOPE) / count(Q_IN_SCOPE)` (also log `MRR@5` for ranking diagnostics).
   - **Never** divide `|top5 ∩ relevant| / 5.0` when a single chunk holds the answer — that caps a perfect single-chunk retrieval at `0.20` (`20%`)!

2. **Out-of-Scope Hallucination Rate (`== 0.0%`):**
   - Must evaluate **both** `OOS_EXTERNAL` (facts absent from corpus) AND `OOS_RBAC` (answer exists in `pgvector` under another department or `CONFIDENTIAL`, unauthorized for caller).
   - A case hallucinates/leaks (`1`) if `response != "Không tìm thấy thông tin trong tài liệu nội bộ"`, OR `abstained != true`, OR `forbiddenPhrase` appears anywhere in response/citations.
   - Formula: `HallucinationRate = violations / count(Q_OOS_EXTERNAL ∪ Q_OOS_RBAC)` -> assert `== 0.0`.

3. **Repeated-Query Cache Hit Ratio (`>= 30%` to `40%`):**
   - Run ordered `CACHE_WORKLOAD` stream (mix of ~60% unique queries and ~40% exact/semantic repeats within same RBAC scope, plus cross-scope control queries).
   - Formula over authorized workload: `CacheHitRatio = authorizedCacheHits / count(Q_CACHE_AUTHORIZED)` -> assert `>= 0.30`.
   - Cross-scope control queries (`CACHE-03`) MUST assert `cacheHit == false` (zero cross-department cache bleed).

## Common Mistakes

| Mistake | Fix |
|---|---|
| Dividing single-chunk ground truth by `5.0` | Use binary question-level `Hit@5` (`exists c in top5`) per the product KPI specification. |
| Hardcoding `chunk_id` in `golden_dataset.jsonl` | Re-chunking invalidates UUIDs; match `(file_name, page_number, expectedKeyPhrase)`. |
| Testing `OOS` with external trivia only | Include `OOS_RBAC` queries targeting real docs in other departments to prove `0%` leakage. |
| Counting cross-department cache bleed as a "Cache Hit" | Separate authorized repeat hits from cross-scope control misses (`expectCacheHit: false`). |
