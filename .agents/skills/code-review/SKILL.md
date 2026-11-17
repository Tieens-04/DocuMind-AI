---
name: code-review
description: Use when reviewing a branch, Pull Request, or uncommitted working-tree changes along two independent axes (Standards compliance and Spec compliance) via parallel read-only subagents.
---

# Two-Axis Independent Code Review (`Standards` + `Spec`)

Runs two independent read-only subagents (`invoke_subagent` with `TypeName: "research"`) so coding-standard violations and specification gaps never mask or dilute each other.

## Process

### 1. Capture the Complete Change Surface (Committed + Uncommitted + Untracked)

Pin the base reference (`main` unless the user specifies another ref):

1. Resolve merge-base: `git merge-base main HEAD` -> `<BASE_SHA>`.
2. Capture all tracked diffs (covers both committed branch changes and uncommitted working-tree edits):
   `git diff <BASE_SHA>`
3. Capture all newly created untracked project files (excluding gitignored scratch such as `.ai-workflow/tasks/`):
   `git ls-files --others --exclude-standard`

Confirm at least one tracked diff or untracked file exists before dispatching subagents. Never rely on three-dot `git diff main...HEAD` when changes are still uncommitted on the working tree.

### 2. Identify Spec & Standards Sources

- **Spec sources** (in priority order):
  1. `.ai-workflow/tasks/<TICKET_ID>/jira.md` + approved `.ai-workflow/tasks/<TICKET_ID>/plan.md` (when running inside `.ai-workflow`).
  2. Matching design spec / plan under `docs/superpowers/specs/` or `docs/superpowers/plans/`.
  3. Issue key (`DA-*`) fetched via `atlassian-mcp-server` if requested by the user.
- **Standards sources**:
  1. `AGENTS.md` at repository root (non-negotiable architecture, RBAC, anti-hallucination, caching, Maven Wrapper, Testcontainers, and port invariants).
  2. `.ai-workflow/rules/domain-review.md` (DocuMind AI domain checklist).
  3. Relevant domain skills in `.agents/skills/` (`spring-ai-rag-pipeline`, `enterprise-rbac-and-cache-testing`, `rag-kpi-evaluator`).
  4. The **Fowler Code Smell Baseline** below (repo standards always override baseline heuristics):
     - **Mysterious Name**: class, method, record component, or variable name hides intent or unit (`ms`, `tokens`, `bytes`).
     - **Duplicated Code**: repeated RBAC filter builders, metadata maps, stream cleanup, or test setup across classes.
     - **Feature Envy / Leaky Layer**: controller containing business/SQL/S3 logic, or domain model depending on HTTP servlet types.
     - **Data Clumps / Primitive Obsession**: passing raw `(UUID userId, UUID deptId, String role)` tuples instead of typed caller context / Java `record` / enum.
     - **Repeated Switches**: duplicated `Role` or `AccessLevel` switch cascades scattered across services.
     - **Shotgun Surgery / Divergent Change**: single class mixing extraction, embedding, caching, and HTTP mapping.
     - **Speculative Generality**: unused interfaces, config flags, or dead parameters without callers.
     - **Refused Bequest / Resource Leak**: subclass breaking base contract or leaving `InputStream` / `PDDocument` / temp `Path` unclosed on exception paths.

### 3. Dispatch Parallel Read-Only Subagents (`invoke_subagent`)

Pass `<BASE_SHA>`, the changed file list, and the full list of untracked files to both subagents:

- **Subagent 1 (`Standards & Domain Reviewer`)**:
  - Brief: *"Inspect `git diff <BASE_SHA>` and all listed untracked files against `AGENTS.md`, `.ai-workflow/rules/domain-review.md`, and the Fowler smell baseline. Report (a) every concrete breach of a documented project rule or RAG/security invariant with `file:line` and rule citation, and (b) high-signal structural smells with hunk quotes. Distinguish hard rule breaches from smell judgement calls."*
- **Subagent 2 (`Spec & Test Reviewer`)**:
  - Brief: *"Inspect `git diff <BASE_SHA>` and all listed untracked files against the originating Jira AC / spec / plan. Report (a) missing or partially implemented requirements, (b) unrequested scope creep, (c) implementations or tests that look green superficially (e.g., mocking away SQL/pgvector/Redis or skipping edge/error paths) while failing the real requirement. Cite exact spec items and `file:line`."*

### 4. Verify & Aggregate Without Merging Axes

- Verify every reported `file:line` in the actual workspace and drop unsubstantiated guesses.
- Report findings separately under **Standards & Domain** and **Spec & Tests** so one clean axis never hides failures in the other.
