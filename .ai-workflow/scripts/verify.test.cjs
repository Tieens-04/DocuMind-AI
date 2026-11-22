const assert = require('node:assert/strict');
const { mkdtemp, mkdir, rm, writeFile, readFile } = require('node:fs/promises');
const { tmpdir } = require('node:os');
const path = require('node:path');
const { test } = require('node:test');

const {
  CANONICAL_STATUSES,
  validateBranchName,
  parseGitStatusPorcelain,
  classifyScopes,
  scanCleanliness,
  parseStateMarkdown,
  inferTicketState,
  renderBoardMarkdown,
  buildBoardFromDisk,
  buildWinCmdCommand,
  extractMavenSummary,
} = require('./verify.cjs');

test('validateBranchName enforces DocuMind DA-* branch conventions and rejects codex/ or feature/', () => {
  assert.deepEqual(validateBranchName('feat/da-19-redis-rbac-cache'), {
    ok: true,
    reason: null,
  });
  assert.deepEqual(validateBranchName('bugfix/da-14-vector-filter'), {
    ok: true,
    reason: null,
  });
  assert.deepEqual(validateBranchName('chore/da-18-week2-rbac-sse-suite'), {
    ok: true,
    reason: null,
  });
  assert.deepEqual(validateBranchName('docs/da-25-architecture-guide'), {
    ok: true,
    reason: null,
  });

  for (const invalid of [
    'codex/da-18-fix',
    'feature/da-18-fix',
    'feat/DA-18-uppercase',
    'feat/cwb-18-wrong-project',
    'main',
    'master',
    '',
  ]) {
    const res = validateBranchName(invalid);
    assert.equal(res.ok, false, `expected "${invalid}" to be rejected`);
    assert.ok(res.reason && res.reason.length > 0);
  }
});

test('parseGitStatusPorcelain and classifyScopes handle modified, renamed, deleted, and untracked ?? files', () => {
  const sample = [
    ' M backend/src/main/java/com/documind/service/rag/RagChatService.java',
    'A  backend/src/main/resources/db/migration/V8__add_cache_metrics.sql',
    'D  frontend/src/components/OldBadge.tsx',
    'R  docs/old.md -> docs/new.md',
    '?? backend/src/test/java/com/documind/integration/Week2CrossGateRbacIntegrationTest.java',
    '?? frontend/e2e/chat.spec.ts',
    '?? eval/golden-dataset.json',
    '?? .ai-workflow/tasks/DA-18/state.md',
  ].join('\n');

  const parsed = parseGitStatusPorcelain(sample);
  assert.deepEqual(parsed.modified, [
    'backend/src/main/java/com/documind/service/rag/RagChatService.java',
    'backend/src/main/resources/db/migration/V8__add_cache_metrics.sql',
    'docs/new.md',
  ]);
  assert.deepEqual(parsed.deleted, ['frontend/src/components/OldBadge.tsx']);
  assert.deepEqual(parsed.untracked, [
    'backend/src/test/java/com/documind/integration/Week2CrossGateRbacIntegrationTest.java',
    'frontend/e2e/chat.spec.ts',
    'eval/golden-dataset.json',
  ]);

  const scopes = classifyScopes(parsed.liveFiles, parsed.deleted);
  assert.equal(scopes.touchesBackend, true);
  assert.equal(scopes.touchesFlyway, true);
  assert.equal(scopes.touchesFrontend, true);
  assert.equal(scopes.touchesE2E, true);
  assert.equal(scopes.touchesEval, true);
  assert.equal(scopes.touchesDocs, true);
});

test('scanCleanliness catches junk files, System.out, printStackTrace, bare @Disabled, console.log, and secrets', () => {
  const dirty = scanCleanliness({
    liveFiles: [
      '.env.local',
      'backend-error.log',
      'backend/src/main/java/com/documind/service/rag/RagChatService.java',
      'backend/src/test/java/com/documind/service/rag/RagChatServiceTest.java',
      'frontend/src/app/chat/page.tsx',
    ],
    addedLinesByFile: new Map([
      [
        'backend/src/main/java/com/documind/service/rag/RagChatService.java',
        [
          { line: 42, text: '    System.out.println("debug chunk");' },
          { line: 43, text: '    e.printStackTrace();' },
        ],
      ],
      [
        'backend/src/test/java/com/documind/service/rag/RagChatServiceTest.java',
        [{ line: 12, text: '  @Disabled' }],
      ],
      [
        'frontend/src/app/chat/page.tsx',
        [
          { line: 8, text: 'console.log("stream chunk");' },
          { line: 9, text: 'const leak = "gsk_1234567890abcdefghijklmnopqrstuvwxyz";' },
        ],
      ],
    ]),
  });

  assert.equal(dirty.ok, false);
  assert.ok(dirty.issues.some((msg) => msg.includes('.env.local')));
  assert.ok(dirty.issues.some((msg) => msg.includes('backend-error.log')));
  assert.ok(dirty.issues.some((msg) => msg.includes('System.out/err')));
  assert.ok(dirty.issues.some((msg) => msg.includes('printStackTrace')));
  assert.ok(dirty.issues.some((msg) => msg.includes('@Disabled')));
  assert.ok(dirty.issues.some((msg) => msg.includes('console.log')));
  assert.ok(dirty.issues.some((msg) => msg.includes('Groq API key')));

  const clean = scanCleanliness({
    liveFiles: [
      '.env.example',
      'backend/src/test/java/com/documind/service/rag/RagChatServiceTest.java',
    ],
    addedLinesByFile: new Map([
      [
        'backend/src/test/java/com/documind/service/rag/RagChatServiceTest.java',
        [{ line: 15, text: '  @Disabled("Requires live Ollama smoke host")' }],
      ],
    ]),
  });

  assert.deepEqual(clean, { ok: true, issues: [] });
});

test('parseStateMarkdown, inferTicketState, and extractMavenSummary work for DA-* tickets', () => {
  assert.deepEqual(CANONICAL_STATUSES, ['Todo', 'Working', 'Blocked', 'Ready for Review', 'Done']);

  const parsed = parseStateMarkdown(
    [
      '# Handoff State: DA-18',
      '- **Status**: Ready for Review',
      '- **Step**: 02-implement',
      '- **Branch**: `chore/da-18-week2-rbac-sse-suite`',
      '- **Objective**: Milestone 2 RBAC & SSE concurrency suite',
      '- **Strikes**: 0',
      '- **Next Gate**: Run 03-review.md',
    ].join('\n'),
  );
  assert.equal(parsed.status, 'Ready for Review');
  assert.equal(parsed.step, '02-implement');
  assert.equal(parsed.branch, 'chore/da-18-week2-rbac-sse-suite');
  assert.equal(parsed.strikes, 0);

  const legacyImpl = inferTicketState({
    ticketId: 'DA-18',
    files: ['jira.md', 'plan.md', 'implementation-report.md'],
    stateContent: null,
    mergedTickets: new Set(['DA-17']),
    currentBranch: 'chore/da-18-week2-rbac-sse-suite',
  });
  assert.equal(legacyImpl.status, 'Ready for Review');
  assert.match(legacyImpl.nextGate, /03-review/i);

  const merged = inferTicketState({
    ticketId: 'DA-17',
    files: ['jira.md', 'plan.md', 'precommit.md'],
    stateContent: null,
    mergedTickets: new Set(['DA-17']),
    currentBranch: 'main',
  });
  assert.equal(merged.status, 'Done');

  const summary = extractMavenSummary(
    '[INFO] Tests run: 444, Failures: 0, Errors: 0, Skipped: 10\n[INFO] All coverage checks have been met.\n[INFO] BUILD SUCCESS',
    '42.5',
  );
  assert.match(summary, /42\.5s/);
  assert.match(summary, /Tests run: 444/);
  assert.match(summary, /JaCoCo >= 75% PASS/);
});

test('buildBoardFromDisk writes deterministic BOARD.md with Blocked tickets surfaced first', async (t) => {
  const tempRoot = await mkdtemp(path.join(tmpdir(), 'documind-board-test-'));
  t.after(() => rm(tempRoot, { recursive: true, force: true }));

  const tasksDir = path.join(tempRoot, '.ai-workflow', 'tasks');
  await mkdir(path.join(tasksDir, 'DA-18'), { recursive: true });
  await mkdir(path.join(tasksDir, 'DA-17'), { recursive: true });
  await mkdir(path.join(tasksDir, 'DA-19'), { recursive: true });

  await writeFile(
    path.join(tasksDir, 'DA-18', 'plan.md'),
    '# Implementation Plan: DA-18 — Milestone 2 RBAC & SSE Suite\n',
  );
  await writeFile(path.join(tasksDir, 'DA-18', 'implementation-report.md'), '# Report\n');
  await writeFile(path.join(tasksDir, 'DA-17', 'precommit.md'), '**`SAFE TO COMMIT`**\n');
  await writeFile(
    path.join(tasksDir, 'DA-19', 'state.md'),
    [
      '# Handoff State: DA-19',
      '- **Status**: Blocked',
      '- **Step**: 04-fix',
      '- **Branch**: `feat/da-19-redis-cache`',
      '- **Objective**: RBAC-scoped L1/L2 cache',
      '- **Strikes**: 2',
      '- **Next Gate**: Human decision on scope_version invalidation',
    ].join('\n'),
  );

  const boardPath = path.join(tempRoot, '.ai-workflow', 'BOARD.md');
  const result = await buildBoardFromDisk({
    repoRoot: tempRoot,
    mergedTickets: new Set(['DA-17']),
    currentBranch: 'chore/da-18-week2-rbac-sse-suite',
    writeToDisk: true,
  });

  assert.equal(result.active.length, 2);
  assert.equal(result.active[0].ticketId, 'DA-19');
  assert.equal(result.active[1].ticketId, 'DA-18');
  assert.equal(result.done.length, 1);
  assert.equal(result.done[0].ticketId, 'DA-17');

  const written = await readFile(boardPath, 'utf8');
  assert.equal(written, renderBoardMarkdown(result));
});

test('buildWinCmdCommand preserves -Dtest=ClassA,ClassB quoting for cmd.exe /d /s /c', () => {
  const cmd = buildWinCmdCommand('mvnw.cmd', [
    'test',
    '-Dtest=Week2CrossGateRbacIntegrationTest,Week2SseConcurrencyAndResilienceIntegrationTest',
  ]);
  assert.equal(
    cmd,
    '"mvnw.cmd test "-Dtest=Week2CrossGateRbacIntegrationTest,Week2SseConcurrencyAndResilienceIntegrationTest""',
  );
});
