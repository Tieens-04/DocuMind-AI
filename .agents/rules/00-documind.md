# DocuMind AI — Workspace Rule

@/AGENTS.md

`AGENTS.md` at the repository root is the canonical engineering, security, and RAG invariant rulebook for this workspace.
Follow all of its instructions for architecture, Java 21 / Spring AI 1.0+ GA discipline, mandatory `pgvector` RBAC filtering, deterministic anti-hallucination fallback, Redis cache scoping, Maven Wrapper (`.\mvnw.cmd`), Testcontainers, and headed Playwright E2E.

When executing Jira tickets (`DA-*`) or `.ai-workflow` steps:
1. Activate the `ai-workflow` skill (`.agents/skills/ai-workflow/SKILL.md`) and follow `.ai-workflow/prompts/00-shared-rules.md` without overriding any invariant in `AGENTS.md`.
2. **STRICT STEP-BY-STEP GATEKEEPING (NO AUTO-CHAINING):** Execute **ONLY ONE** workflow step per user message (`01-plan` OR `02-implement` OR `03-review` OR `04-fix` OR `05-final-review` OR `06-precommit`), print the next-step hand-off box, and **END TURN IMMEDIATELY** so the human can review the gate and switch Antigravity models.
3. Do **not** invoke `superpowers:brainstorming`, `superpowers:writing-plans`, `superpowers:executing-plans`, or `superpowers:subagent-driven-development` inside `.ai-workflow` runs (use only atomic technical skills like `test-driven-development` and `systematic-debugging` within Steps 2 and 4).

Do not create competing project-wide rules that contradict `AGENTS.md`. If a narrower rule is needed for a subdirectory, scope it explicitly and document the reason.
