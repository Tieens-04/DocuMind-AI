#!/usr/bin/env node
'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');

const CANONICAL_STATUSES = ['Todo', 'Working', 'Blocked', 'Ready for Review', 'Done'];
const STATUS_PRIORITY = new Map([
  ['Blocked', 0],
  ['Ready for Review', 1],
  ['Working', 2],
  ['Todo', 3],
  ['Done', 4],
]);

const TEXT_SCAN_EXT = new Set([
  '.java',
  '.kt',
  '.xml',
  '.sql',
  '.yml',
  '.yaml',
  '.properties',
  '.ts',
  '.tsx',
  '.js',
  '.jsx',
  '.cjs',
  '.mjs',
  '.json',
  '.env',
  '.sh',
  '.ps1',
]);

function normalizeSlashes(filePath) {
  return filePath.replace(/\\/g, '/');
}

function unquoteGitPath(raw) {
  const trimmed = raw.trim();
  if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
    return trimmed.slice(1, -1).replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  }
  return trimmed;
}

function validateBranchName(branch) {
  const name = (branch || '').trim();
  if (!name || name === 'HEAD') {
    return { ok: false, reason: 'Detached or empty HEAD; expected <type>/da-<id>-<slug>' };
  }
  if (name === 'main' || name === 'master') {
    return {
      ok: false,
      reason: `On default branch "${name}"; create a task branch from main first`,
    };
  }
  if (name.startsWith('codex/') || name.startsWith('feature/')) {
    return {
      ok: false,
      reason: `Forbidden prefix on "${name}"; workflow allows only feat/, bugfix/, chore/, docs/`,
    };
  }
  if (!/^(bugfix|feat|chore|docs)\/da-[0-9]+(?:-[a-z0-9]+)*$/.test(name)) {
    return {
      ok: false,
      reason: `Branch "${name}" must match ^(bugfix|feat|chore|docs)/da-<id>-<lowercase-slug>$`,
    };
  }
  return { ok: true, reason: null };
}

function parseGitStatusPorcelain(porcelainOutput) {
  const modified = [];
  const deleted = [];
  const untracked = [];

  for (const rawLine of (porcelainOutput || '').split(/\r?\n/)) {
    if (!rawLine || rawLine.length < 4) continue;
    const status = rawLine.slice(0, 2);
    let target = rawLine.slice(3);

    if (status === '??') {
      const file = normalizeSlashes(unquoteGitPath(target));
      if (!file.startsWith('.ai-workflow/') && !file.startsWith('.agents/')) {
        untracked.push(file);
      }
      continue;
    }

    if (target.includes(' -> ')) {
      target = target.split(' -> ').pop();
    }
    const file = normalizeSlashes(unquoteGitPath(target));
    if (file.startsWith('.ai-workflow/') || file.startsWith('.agents/')) continue;

    if (status.includes('D')) {
      deleted.push(file);
    } else {
      modified.push(file);
    }
  }

  return {
    modified,
    deleted,
    untracked,
    liveFiles: [...modified, ...untracked],
  };
}

function classifyScopes(liveFiles, deletedFiles = []) {
  const all = [...liveFiles, ...deletedFiles].map(normalizeSlashes);
  const touchesBackend = all.some((f) => f.startsWith('backend/'));
  const touchesFlyway = all.some((f) => f.startsWith('backend/src/main/resources/db/migration/'));
  const touchesFrontend = all.some((f) => f.startsWith('frontend/'));
  const touchesE2E = all.some(
    (f) =>
      f.startsWith('frontend/e2e/') ||
      f.startsWith('e2e/') ||
      /(?:^|\/)playwright\.config\.[cm]?[jt]s$/.test(f),
  );
  const touchesEval = all.some((f) => f.startsWith('eval/'));
  const touchesInfra = all.some(
    (f) => f === 'docker-compose.yml' || f === '.env.example' || f.startsWith('.github/'),
  );
  const touchesDocs = all.some((f) => f.startsWith('docs/'));

  return {
    touchesBackend,
    touchesFlyway,
    touchesFrontend,
    touchesE2E,
    touchesEval,
    touchesInfra,
    touchesDocs,
  };
}

const SECRET_PATTERNS = [
  { name: 'Private key header', regex: /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/ },
  { name: 'GitHub token', regex: /\bgh[pousr]_[A-Za-z0-9]{36,}\b/ },
  { name: 'OpenAI/API sk- token', regex: /\bsk-(?:proj-)?[A-Za-z0-9]{32,}\b/ },
  { name: 'Groq API key', regex: /\bgsk_[A-Za-z0-9]{32,}\b/ },
  { name: 'Google/Gemini API key', regex: /\bAIza[0-9A-Za-z_-]{35}\b/ },
];

function isTestFile(file) {
  return (
    file.startsWith('backend/src/test/') ||
    file.includes('/__tests__/') ||
    /\.(?:test|spec)\.[cm]?[jt]sx?$/.test(file) ||
    file.startsWith('frontend/e2e/') ||
    file.startsWith('e2e/')
  );
}

function isJavaMainSource(file) {
  return file.startsWith('backend/src/main/java/') && file.endsWith('.java');
}

function isFrontendRuntimeSource(file) {
  if (isTestFile(file)) return false;
  return (
    file.startsWith('frontend/src/') ||
    file.startsWith('frontend/app/') ||
    file.startsWith('frontend/components/')
  );
}

function scanCleanliness({ liveFiles, addedLinesByFile }) {
  const issues = [];

  for (const rawFile of liveFiles) {
    const file = normalizeSlashes(rawFile);
    const base = path.posix.basename(file);
    if ((base === '.env' || base.startsWith('.env.')) && !base.endsWith('.example')) {
      issues.push(`${file}: real .env* file must never be tracked or left unignored`);
    }
    if (base.endsWith('.log')) {
      issues.push(`${file}: unexpected *.log artifact in working tree`);
    }
    if (file.startsWith('backend/target/')) {
      issues.push(`${file}: Maven build output under backend/target/ must stay gitignored`);
    }
  }

  for (const [rawFile, lines] of addedLinesByFile.entries()) {
    const file = normalizeSlashes(rawFile);
    const base = path.posix.basename(file);
    const isExample = base.endsWith('.example');

    for (const { line, text } of lines) {
      if (isJavaMainSource(file)) {
        if (/\bSystem\.(?:out|err)\.print(?:ln|f)?\s*\(/.test(text)) {
          issues.push(`${file}:${line}: System.out/err print in main Java source (use SLF4J logger)`);
        }
        if (/\.printStackTrace\s*\(/.test(text)) {
          issues.push(`${file}:${line}: printStackTrace() in main Java source`);
        }
      }

      if (isFrontendRuntimeSource(file)) {
        if (/\bdebugger\s*;?/.test(text)) {
          issues.push(`${file}:${line}: leftover debugger statement`);
        }
        if (/\bconsole\.log\s*\(/.test(text)) {
          issues.push(`${file}:${line}: added console.log in frontend runtime source`);
        }
      }

      if (isTestFile(file)) {
        const jsFocusMatch = /\b((?:describe|it|test)\.(?:only|skip))\b/.exec(text);
        if (jsFocusMatch) {
          issues.push(`${file}:${line}: accidental ${jsFocusMatch[1]} in test file`);
        }
        if (file.endsWith('.java') && /@Disabled\s*(?:\(\s*\))?\s*$/.test(text.trim())) {
          issues.push(`${file}:${line}: bare @Disabled without justification in Java test`);
        }
      }

      if (file === 'backend/src/main/resources/application.yml') {
        if (/dev-caller-fallback\s*:/.test(text) && /true/.test(text)) {
          issues.push(`${file}:${line}: dev-caller-fallback must stay false in application.yml`);
        }
      }

      if (!isExample) {
        for (const pattern of SECRET_PATTERNS) {
          if (pattern.regex.test(text)) {
            issues.push(`${file}:${line}: potential secret detected (${pattern.name})`);
          }
        }
      }
    }
  }

  return { ok: issues.length === 0, issues };
}

function checkApplicationYmlSafety(repoRoot) {
  const appYmlPath = path.join(repoRoot, 'backend', 'src', 'main', 'resources', 'application.yml');
  if (!fs.existsSync(appYmlPath)) return { ok: true, reason: null };

  const content = fs.readFileSync(appYmlPath, 'utf8');
  const lines = content.split(/\r?\n/);
  let inDevFallback = false;
  let devFallbackIndent = -1;

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (/^\s*#/.test(line) || line.trim().length === 0) continue;
    const indent = line.search(/\S/);

    if (/^\s*dev-caller-fallback\s*:/.test(line)) {
      inDevFallback = true;
      devFallbackIndent = indent;
      continue;
    }

    if (inDevFallback) {
      if (indent <= devFallbackIndent) {
        inDevFallback = false;
      } else if (/^\s*enabled\s*:\s*(?:true|\$\{[^}]+:true\})/i.test(line)) {
        return {
          ok: false,
          reason: `backend/src/main/resources/application.yml:${i + 1}: dev-caller-fallback.enabled must default to false`,
        };
      }
    }
  }

  return { ok: true, reason: null };
}

function parseUnifiedZeroAddedLines(diffText) {
  const map = new Map();
  let currentFile = null;
  let currentNewLine = 0;

  for (const line of (diffText || '').split(/\r?\n/)) {
    if (line.startsWith('+++ b/')) {
      currentFile = normalizeSlashes(unquoteGitPath(line.slice(6)));
      continue;
    }
    if (line.startsWith('+++ /dev/null')) {
      currentFile = null;
      continue;
    }
    const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
    if (hunk) {
      currentNewLine = Number(hunk[1]);
      continue;
    }
    if (
      !currentFile ||
      currentFile.startsWith('.ai-workflow/') ||
      currentFile.startsWith('.agents/')
    ) {
      continue;
    }
    if (line.startsWith('+') && !line.startsWith('+++')) {
      if (!map.has(currentFile)) map.set(currentFile, []);
      map.get(currentFile).push({ line: currentNewLine, text: line.slice(1) });
      currentNewLine += 1;
    }
  }

  return map;
}

function parseStateMarkdown(markdown) {
  const fields = {
    status: null,
    step: null,
    branch: null,
    objective: null,
    strikes: 0,
    nextGate: null,
  };
  if (!markdown) return fields;

  const extract = (label) => {
    const regex = new RegExp(`^\\s*[-*]\\s*\\*\\*${label}\\*\\*:\\s*(.+)$`, 'im');
    const match = regex.exec(markdown);
    return match ? match[1].trim().replace(/^`+|`+$/g, '') : null;
  };

  const rawStatus = extract('Status');
  if (rawStatus) {
    const matched = CANONICAL_STATUSES.find((s) => s.toLowerCase() === rawStatus.toLowerCase());
    if (matched) fields.status = matched;
  }
  fields.step = extract('Step');
  fields.branch = extract('Branch');
  fields.objective = extract('Objective');
  const rawStrikes = extract('Strikes');
  if (rawStrikes && /^\d+$/.test(rawStrikes)) {
    fields.strikes = Number(rawStrikes);
  }
  fields.nextGate = extract('Next Gate');
  return fields;
}

function inferTicketState({
  ticketId,
  files,
  stateContent,
  finalReviewContent = '',
  reviewContent = '',
  planContent = '',
  mergedTickets = new Set(),
  activeBranchTickets = null,
  currentBranch = '',
}) {
  const fileSet = new Set(files);
  const parsed = parseStateMarkdown(stateContent);
  const upperId = ticketId.toUpperCase();
  const isMerged = mergedTickets.has(upperId);

  let title = parsed.objective || '';
  if (!title && planContent) {
    const heading = /^#\s+Implementation Plan:\s*[A-Z]+-\d+\s*[—–-]\s*(.+)$/m.exec(planContent);
    if (heading) title = heading[1].trim();
  }

  const branchFromPlan = (() => {
    if (parsed.branch) return parsed.branch;
    const match = /^\s*[-*]\s*\*\*Branch\*\*:\s*`([^`]+)`/m.exec(planContent || '');
    return match ? match[1].trim() : '';
  })();

  if (isMerged) {
    return {
      ticketId,
      status: 'Done',
      step: '06-precommit (merged)',
      branch: branchFromPlan || 'main',
      objective: title || 'Merged to main',
      strikes: parsed.strikes,
      nextGate: 'None (merged)',
    };
  }

  if (parsed.status) {
    return {
      ticketId,
      status: parsed.status,
      step: parsed.step || 'unknown',
      branch:
        branchFromPlan ||
        (currentBranch.toLowerCase().includes(ticketId.toLowerCase()) ? currentBranch : '-'),
      objective: title || '-',
      strikes: parsed.strikes,
      nextGate: parsed.nextGate || '-',
    };
  }

  if (
    activeBranchTickets !== null &&
    !activeBranchTickets.has(upperId) &&
    !currentBranch.toLowerCase().includes(ticketId.toLowerCase()) &&
    fileSet.has('review-01.md')
  ) {
    return {
      ticketId,
      status: 'Done',
      step: '06-precommit (archived)',
      branch: branchFromPlan || 'main',
      objective: title || 'Completed (legacy)',
      strikes: 0,
      nextGate: 'None (archived)',
    };
  }

  if (fileSet.has('precommit.md') || fileSet.has('precommit-report.md')) {
    return {
      ticketId,
      status: 'Done',
      step: '06-precommit',
      branch: branchFromPlan || '-',
      objective: title || 'Pre-commit complete',
      strikes: 0,
      nextGate: 'Human git commit & push',
    };
  }
  if (fileSet.has('final-review.md')) {
    const changesRequested = /CHANGES REQUESTED/.test(finalReviewContent);
    return {
      ticketId,
      status: changesRequested ? 'Working' : 'Ready for Review',
      step: changesRequested ? '04-fix (re-opened)' : '05-final-review -> 06-precommit',
      branch: branchFromPlan || '-',
      objective: title || '-',
      strikes: changesRequested ? 1 : 0,
      nextGate: changesRequested ? 'Run 04-fix.md' : 'Run 06-precommit.md',
    };
  }
  if (fileSet.has('fix-report.md')) {
    return {
      ticketId,
      status: 'Ready for Review',
      step: '04-fix -> 05-final-review',
      branch: branchFromPlan || '-',
      objective: title || '-',
      strikes: 0,
      nextGate: 'Run 05-final-review.md',
    };
  }
  if (fileSet.has('review-01.md')) {
    const approved = /\bAPPROVED\b/.test(reviewContent) && !/CHANGES REQUESTED/.test(reviewContent);
    return {
      ticketId,
      status: approved ? 'Ready for Review' : 'Working',
      step: approved ? '03-review -> 05-final-review' : '03-review -> 04-fix',
      branch: branchFromPlan || '-',
      objective: title || '-',
      strikes: 0,
      nextGate: approved ? 'Run 05-final-review.md' : 'Run 04-fix.md',
    };
  }
  if (fileSet.has('implementation-report.md')) {
    return {
      ticketId,
      status: 'Ready for Review',
      step: '02-implement -> 03-review',
      branch: branchFromPlan || '-',
      objective: title || '-',
      strikes: 0,
      nextGate: 'Run 03-review.md (New Chat + Cross-Model)',
    };
  }
  if (fileSet.has('plan.md')) {
    return {
      ticketId,
      status: 'Working',
      step: '01-plan -> 02-implement',
      branch: branchFromPlan || '-',
      objective: title || '-',
      strikes: 0,
      nextGate: 'Gate 1: Approve plan & run 02-implement.md',
    };
  }

  return {
    ticketId,
    status: 'Todo',
    step: '01-plan',
    branch: '-',
    objective: title || 'Initialized',
    strikes: 0,
    nextGate: 'Run 01-plan.md',
  };
}

function ticketNumber(ticketId) {
  const m = /(\d+)$/.exec(ticketId);
  return m ? Number(m[1]) : 0;
}

function compareTicketRows(a, b) {
  const pa = STATUS_PRIORITY.get(a.status) ?? 99;
  const pb = STATUS_PRIORITY.get(b.status) ?? 99;
  if (pa !== pb) return pa - pb;
  return ticketNumber(b.ticketId) - ticketNumber(a.ticketId);
}

function escapeCell(text) {
  return String(text || '-')
    .replace(/\|/g, '\\|')
    .replace(/\r?\n/g, ' ')
    .trim();
}

function renderBoardMarkdown({ active, done, currentBranch }) {
  const lines = [
    '# DocuMind AI — AI Workflow Board',
    '',
    `> Auto-synced by \`node .ai-workflow/scripts/verify.cjs board\`. Statuses: \`Todo\` | \`Working\` | \`Blocked\` | \`Ready for Review\` | \`Done\`.`,
    `> Current Git branch: \`${currentBranch || 'unknown'}\` · Active tickets: **${active.length}** · Completed archive: **${done.length}**`,
    '',
    '## 1. Active Queue (`Blocked` / `Ready for Review` / `Working` / `Todo`)',
    '',
  ];

  if (active.length === 0) {
    lines.push('_No active tickets in flight. Start one with `/ai-workflow DA-XX`._', '');
  } else {
    lines.push(
      '| Ticket | Status | Step | Branch | Strikes | Objective | Next Gate |',
      '|---|---|---|---|---:|---|---|',
    );
    for (const row of active) {
      lines.push(
        `| **${escapeCell(row.ticketId)}** | \`${escapeCell(row.status)}\` | ${escapeCell(row.step)} | \`${escapeCell(row.branch)}\` | ${row.strikes} | ${escapeCell(row.objective)} | ${escapeCell(row.nextGate)} |`,
      );
    }
    lines.push('');
  }

  lines.push(`## 2. Completed Tickets (\`Done\` — ${done.length} total)`, '');
  if (done.length === 0) {
    lines.push('_None yet._', '');
  } else {
    const recentDone = done.slice(0, 15);
    lines.push('| Ticket | Step | Objective |', '|---|---|---|');
    for (const row of recentDone) {
      lines.push(
        `| ${escapeCell(row.ticketId)} | ${escapeCell(row.step)} | ${escapeCell(row.objective)} |`,
      );
    }
    if (done.length > recentDone.length) {
      lines.push(
        '',
        `_Plus ${done.length - recentDone.length} older completed tickets in \`.ai-workflow/tasks/\`._`,
      );
    }
    lines.push('');
  }

  return lines.join('\n');
}

async function readOptionalUtf8(filePath) {
  try {
    return await fsp.readFile(filePath, 'utf8');
  } catch {
    return '';
  }
}

async function buildBoardFromDisk({
  repoRoot,
  mergedTickets,
  activeBranchTickets = null,
  currentBranch,
  writeToDisk = true,
}) {
  const tasksRoot = path.join(repoRoot, '.ai-workflow', 'tasks');
  let entries = [];
  try {
    entries = await fsp.readdir(tasksRoot, { withFileTypes: true });
  } catch {
    entries = [];
  }

  const rows = [];
  for (const entry of entries) {
    if (!entry.isDirectory() || !/^DA-\d+$/i.test(entry.name)) continue;
    const ticketId = entry.name.toUpperCase();
    const dir = path.join(tasksRoot, entry.name);
    const files = await fsp.readdir(dir);

    const [stateContent, finalReviewContent, reviewContent, planContent] = await Promise.all([
      readOptionalUtf8(path.join(dir, 'state.md')),
      readOptionalUtf8(path.join(dir, 'final-review.md')),
      readOptionalUtf8(path.join(dir, 'review-01.md')),
      readOptionalUtf8(path.join(dir, 'plan.md')),
    ]);

    rows.push(
      inferTicketState({
        ticketId,
        files,
        stateContent: stateContent || null,
        finalReviewContent,
        reviewContent,
        planContent,
        mergedTickets,
        activeBranchTickets,
        currentBranch,
      }),
    );
  }

  rows.sort(compareTicketRows);
  const active = rows.filter((r) => r.status !== 'Done');
  const done = rows.filter((r) => r.status === 'Done');
  const result = { active, done, currentBranch };

  if (writeToDisk) {
    const boardPath = path.join(repoRoot, '.ai-workflow', 'BOARD.md');
    await fsp.mkdir(path.dirname(boardPath), { recursive: true });
    await fsp.writeFile(boardPath, renderBoardMarkdown(result), 'utf8');
  }

  return result;
}

function runGit(repoRoot, args) {
  return spawnSync('git', args, {
    cwd: repoRoot,
    encoding: 'utf8',
    maxBuffer: 20 * 1024 * 1024,
  });
}

function quoteWinArg(arg) {
  if (!/[\s"&|<>^(),=]/.test(arg)) return arg;
  return `"${arg.replace(/"/g, '\\"')}"`;
}

function buildWinCmdCommand(executable, args) {
  const rendered = [executable, ...args.map(quoteWinArg)].join(' ');
  return `"${rendered}"`;
}

function runMaven(repoRoot, mvnArgs) {
  const backendDir = path.join(repoRoot, 'backend');
  const started = Date.now();
  const res =
    process.platform === 'win32'
      ? spawnSync('cmd.exe', ['/d', '/s', '/c', buildWinCmdCommand('mvnw.cmd', mvnArgs)], {
          cwd: backendDir,
          encoding: 'utf8',
          maxBuffer: 40 * 1024 * 1024,
          windowsVerbatimArguments: true,
        })
      : spawnSync('./mvnw', mvnArgs, {
          cwd: backendDir,
          encoding: 'utf8',
          maxBuffer: 40 * 1024 * 1024,
        });

  const durationSec = ((Date.now() - started) / 1000).toFixed(1);
  return {
    ok: res.status === 0 && !res.error,
    status: res.status,
    durationSec,
    output: `${res.stdout || ''}\n${res.stderr || ''}`.trim(),
  };
}

function runNpm(cwd, npmArgs) {
  const started = Date.now();
  const res =
    process.platform === 'win32'
      ? spawnSync('cmd.exe', ['/d', '/s', '/c', buildWinCmdCommand('npm', npmArgs)], {
          cwd,
          encoding: 'utf8',
          maxBuffer: 30 * 1024 * 1024,
          windowsVerbatimArguments: true,
        })
      : spawnSync('npm', npmArgs, {
          cwd,
          encoding: 'utf8',
          maxBuffer: 30 * 1024 * 1024,
        });

  const durationSec = ((Date.now() - started) / 1000).toFixed(1);
  return {
    ok: res.status === 0 && !res.error,
    status: res.status,
    durationSec,
    output: `${res.stdout || ''}\n${res.stderr || ''}`.trim(),
  };
}

function extractMavenSummary(output, durationSec) {
  const lines = (output || '').split(/\r?\n/);
  let lastResults = null;
  for (const line of lines) {
    if (/Tests run:\s*\d+,\s*Failures:\s*\d+,\s*Errors:\s*\d+/.test(line)) {
      lastResults = line.replace(/^\[(?:INFO|ERROR|WARNING)\]\s*/, '').trim();
    }
  }
  const jacocoMet = lines.some((l) => /All coverage checks have been met/i.test(l));
  const parts = [`${durationSec}s`];
  if (lastResults) parts.push(lastResults);
  if (jacocoMet) parts.push('JaCoCo >= 75% PASS');
  return parts.join(' · ');
}

function collectGitBranchState(repoRoot) {
  const merged = new Set();
  const activeBranches = new Set();

  const logRes = runGit(repoRoot, ['log', 'main', '--oneline']);
  if (logRes.status === 0) {
    const regex = /\b(DA-\d+)\b/gi;
    let match;
    while ((match = regex.exec(logRes.stdout)) !== null) {
      merged.add(match[1].toUpperCase());
    }
  }

  const mainShaRes = runGit(repoRoot, ['rev-parse', 'main']);
  const mainSha = mainShaRes.status === 0 ? (mainShaRes.stdout || '').trim() : '';

  const allRefsRes = runGit(repoRoot, [
    'for-each-ref',
    '--format=%(refname:short) %(objectname)',
    'refs/heads/',
  ]);
  const mergedRefsRes = runGit(repoRoot, [
    'for-each-ref',
    '--merged=main',
    '--format=%(refname:short) %(objectname)',
    'refs/heads/',
  ]);

  const mergedRefNames = new Set();
  if (mergedRefsRes.status === 0) {
    for (const line of (mergedRefsRes.stdout || '').split(/\r?\n/)) {
      const [refName, sha] = line.trim().split(/\s+/);
      if (refName && sha && sha !== mainSha) {
        mergedRefNames.add(refName);
      }
    }
  }

  if (allRefsRes.status === 0) {
    for (const line of (allRefsRes.stdout || '').split(/\r?\n/)) {
      const [refName] = line.trim().split(/\s+/);
      if (!refName) continue;
      const ticketMatch = /\b(da-\d+)\b/i.exec(refName);
      if (!ticketMatch) continue;
      const ticketId = ticketMatch[1].toUpperCase();
      if (mergedRefNames.has(refName)) {
        merged.add(ticketId);
      } else {
        activeBranches.add(ticketId);
      }
    }
  }

  return { mergedTickets: merged, activeBranchTickets: activeBranches };
}

function collectWorkingTreeContext(repoRoot) {
  const branchRes = runGit(repoRoot, ['branch', '--show-current']);
  const branch = (branchRes.stdout || '').trim();
  const branchCheck = validateBranchName(branch);

  const mergeBaseRes = runGit(repoRoot, ['merge-base', 'main', 'HEAD']);
  const baseSha = mergeBaseRes.status === 0 ? (mergeBaseRes.stdout || '').trim() : 'HEAD';

  // Committed files since merge-base + working tree changes
  const branchFilesRes =
    baseSha && baseSha !== 'HEAD'
      ? runGit(repoRoot, ['diff', '--name-status', baseSha])
      : { status: 1, stdout: '' };

  const statusRes = runGit(repoRoot, ['status', '--porcelain=v1', '-uall']);
  const parsedStatus = parseGitStatusPorcelain(statusRes.stdout || '');

  const modifiedSet = new Set(parsedStatus.modified);
  const deletedSet = new Set(parsedStatus.deleted);
  if (branchFilesRes.status === 0) {
    for (const rawLine of (branchFilesRes.stdout || '').split(/\r?\n/)) {
      if (!rawLine.trim()) continue;
      const parts = rawLine.split('\t');
      const code = parts[0];
      const file = normalizeSlashes(unquoteGitPath(parts[parts.length - 1] || ''));
      if (!file || file.startsWith('.ai-workflow/') || file.startsWith('.agents/')) continue;
      if (code.startsWith('D')) {
        deletedSet.add(file);
        modifiedSet.delete(file);
      } else {
        modifiedSet.add(file);
      }
    }
  }

  const combinedStatus = {
    modified: [...modifiedSet],
    deleted: [...deletedSet],
    untracked: parsedStatus.untracked,
    liveFiles: [...modifiedSet, ...parsedStatus.untracked],
  };

  const scopes = classifyScopes(combinedStatus.liveFiles, combinedStatus.deleted);

  const diffCheckRes = runGit(repoRoot, ['diff', baseSha, '--check']);
  const whitespaceIssues =
    diffCheckRes.status === 0
      ? []
      : (diffCheckRes.stdout || diffCheckRes.stderr || '')
          .split(/\r?\n/)
          .filter(Boolean)
          .slice(0, 10);

  const unifiedRes = runGit(repoRoot, ['diff', baseSha, '--unified=0', '--no-color']);
  const addedLinesByFile = parseUnifiedZeroAddedLines(unifiedRes.stdout || '');

  for (const untrackedFile of combinedStatus.untracked) {
    const ext = path.posix.extname(untrackedFile).toLowerCase();
    const base = path.posix.basename(untrackedFile);
    if (!TEXT_SCAN_EXT.has(ext) && !base.startsWith('.env')) continue;
    try {
      const content = fs.readFileSync(path.join(repoRoot, untrackedFile), 'utf8');
      const lines = content.split(/\r?\n/).map((text, idx) => ({ line: idx + 1, text }));
      addedLinesByFile.set(untrackedFile, lines);
    } catch {
      // Ignore unreadable files
    }
  }

  const cleanliness = scanCleanliness({
    liveFiles: combinedStatus.liveFiles,
    addedLinesByFile,
  });

  const appYmlCheck = checkApplicationYmlSafety(repoRoot);
  if (!appYmlCheck.ok) {
    cleanliness.ok = false;
    cleanliness.issues.push(appYmlCheck.reason);
  }

  const tasksIgnoreCheck = runGit(repoRoot, [
    'check-ignore',
    '-q',
    '--no-index',
    '.ai-workflow/tasks/DA-999/state.md',
  ]);
  const tasksIgnored = tasksIgnoreCheck.status === 0;

  return {
    branch,
    baseSha: baseSha.slice(0, 7),
    branchCheck,
    parsedStatus: combinedStatus,
    scopes,
    whitespaceIssues,
    cleanliness,
    tasksIgnored,
  };
}

function printDiffReport(ctx) {
  const activeScopes =
    [
      ctx.scopes.touchesBackend && 'backend',
      ctx.scopes.touchesFlyway && 'flyway',
      ctx.scopes.touchesFrontend && 'frontend',
      ctx.scopes.touchesE2E && 'e2e',
      ctx.scopes.touchesEval && 'eval',
      ctx.scopes.touchesInfra && 'infra',
      ctx.scopes.touchesDocs && 'docs',
    ]
      .filter(Boolean)
      .join(', ') || 'none (clean working tree)';

  console.log('## AI-Workflow Working-Tree Scope & Hygiene (DocuMind AI)');
  console.log(
    `- **Branch**: \`${ctx.branch || 'DETACHED'}\` (base \`${ctx.baseSha}\`) — ${ctx.branchCheck.ok ? 'VALID' : `INVALID (${ctx.branchCheck.reason})`}`,
  );
  console.log(
    `- **\`.ai-workflow/tasks/\` gitignored**: ${ctx.tasksIgnored ? 'YES' : 'NO (WARNING!)'}`,
  );
  console.log(`- **Touched Scopes**: \`${activeScopes}\``);
  console.log(
    `- **Files**: ${ctx.parsedStatus.modified.length} modified/added · ${ctx.parsedStatus.deleted.length} deleted · **${ctx.parsedStatus.untracked.length} untracked (\`??\`)**`,
  );

  if (ctx.parsedStatus.modified.length > 0) {
    console.log(
      `  - Tracked changed: ${ctx.parsedStatus.modified.map((f) => `\`${f}\``).join(', ')}`,
    );
  }
  if (ctx.parsedStatus.deleted.length > 0) {
    console.log(`  - Deleted: ${ctx.parsedStatus.deleted.map((f) => `\`${f}\``).join(', ')}`);
  }
  if (ctx.parsedStatus.untracked.length > 0) {
    console.log(
      `  - Untracked (\`??\`): ${ctx.parsedStatus.untracked.map((f) => `\`${f}\``).join(', ')}`,
    );
  }

  const allHygieneIssues = [
    ...ctx.whitespaceIssues.map((w) => `git diff --check: ${w}`),
    ...ctx.cleanliness.issues,
  ];
  if (allHygieneIssues.length === 0) {
    console.log('- **Static Hygiene & Secret Scan**: `PASS` (0 issues)');
  } else {
    console.log(
      `- **Static Hygiene & Secret Scan**: \`FAIL\` (${allHygieneIssues.length} issue(s))`,
    );
    for (const issue of allHygieneIssues) {
      console.log(`  - ${issue}`);
    }
  }
}

function parseCliArgs(argv) {
  const args = argv.slice(2);
  let subcommand = 'check';
  if (args[0] && !args[0].startsWith('-')) {
    subcommand = args.shift();
  }

  const opts = {
    subcommand,
    verify: false,
    allowMain: false,
    skipBackend: false,
    frontend: false,
    testPattern: null,
    e2eSpecs: [],
  };

  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === '--verify' || arg === '--build') opts.verify = true;
    else if (arg === '--allow-main' || arg === '--allow-master') opts.allowMain = true;
    else if (arg === '--skip-backend') opts.skipBackend = true;
    else if (arg === '--frontend') opts.frontend = true;
    else if (arg.startsWith('--test=')) {
      opts.testPattern = arg.slice('--test='.length);
    } else if (arg === '--test' && args[i + 1]) {
      i += 1;
      opts.testPattern = args[i];
    } else if (arg.startsWith('--e2e=')) {
      opts.e2eSpecs.push(arg.slice('--e2e='.length));
    } else if (arg === '--e2e') {
      while (args[i + 1] && !args[i + 1].startsWith('-')) {
        i += 1;
        opts.e2eSpecs.push(args[i]);
      }
    }
  }

  return opts;
}

function tailLines(text, count = 40) {
  const lines = (text || '').split(/\r?\n/).filter((l) => l.trim().length > 0);
  if (lines.length <= count) return lines.join('\n');
  return `... (${lines.length - count} earlier lines omitted)\n${lines.slice(-count).join('\n')}`;
}

async function runCli() {
  const repoRoot = path.resolve(__dirname, '..', '..');
  const opts = parseCliArgs(process.argv);

  if (opts.subcommand === 'board') {
    const branchRes = runGit(repoRoot, ['branch', '--show-current']);
    const currentBranch = (branchRes.stdout || '').trim();
    const { mergedTickets, activeBranchTickets } = collectGitBranchState(repoRoot);
    const board = await buildBoardFromDisk({
      repoRoot,
      mergedTickets,
      activeBranchTickets,
      currentBranch,
      writeToDisk: true,
    });
    console.log(
      `[ai-workflow board] Synced .ai-workflow/BOARD.md — Active: ${board.active.length}, Done: ${board.done.length}.`,
    );
    for (const row of board.active) {
      console.log(
        `  - ${row.ticketId} [${row.status}] (${row.step}) branch=${row.branch} -> ${row.nextGate}`,
      );
    }
    return;
  }

  const ctx = collectWorkingTreeContext(repoRoot);
  printDiffReport(ctx);

  const branchOk = opts.allowMain || ctx.branchCheck.ok;
  const hygieneOk =
    ctx.tasksIgnored && ctx.whitespaceIssues.length === 0 && ctx.cleanliness.ok;

  if (opts.subcommand === 'diff' || opts.subcommand === 'scope') {
    process.exit(branchOk && hygieneOk ? 0 : 1);
  }

  if (opts.subcommand !== 'check') {
    console.error(`Unknown subcommand "${opts.subcommand}". Expected: check | diff | board`);
    process.exit(1);
  }

  const gates = [];
  const failures = [];

  gates.push({
    name: 'Branch & Hygiene',
    command: 'verify.cjs diff',
    status: branchOk && hygieneOk ? 'PASS' : 'FAIL',
    detail:
      branchOk && hygieneOk
        ? `${ctx.parsedStatus.liveFiles.length} live changed file(s), 0 hygiene issues`
        : ctx.branchCheck.reason ||
          `${ctx.cleanliness.issues.length + ctx.whitespaceIssues.length} hygiene error(s)`,
  });

  // Gate: Backend Targeted Tests
  if (opts.testPattern && !opts.skipBackend) {
    const res = runMaven(repoRoot, ['test', `-Dtest=${opts.testPattern}`]);
    gates.push({
      name: 'Backend Targeted Tests',
      command: `.\\mvnw.cmd test -Dtest=${opts.testPattern}`,
      status: res.ok ? 'PASS' : 'FAIL',
      detail: extractMavenSummary(res.output, res.durationSec),
    });
    if (!res.ok) failures.push({ name: 'Backend Targeted Tests', output: res.output });
  }

  // Gate: Backend Full Verify (Unit + Testcontainers + JaCoCo >= 75% + Package)
  if (opts.verify && !opts.skipBackend) {
    const res = runMaven(repoRoot, ['verify']);
    gates.push({
      name: 'Backend Full Verify',
      command: '.\\mvnw.cmd verify',
      status: res.ok ? 'PASS' : 'FAIL',
      detail: extractMavenSummary(res.output, res.durationSec),
    });
    if (!res.ok) failures.push({ name: 'Backend Full Verify', output: res.output });
  } else if (
    !opts.testPattern &&
    !opts.skipBackend &&
    (ctx.scopes.touchesBackend || ctx.scopes.touchesFlyway)
  ) {
    gates.push({
      name: 'Backend Full Verify',
      command: 'pass --verify or --test=<Class>',
      status: 'REQUIRED',
      detail: 'Backend/Flyway changed — pass --verify (or --test=<Class>) to run Maven checks',
    });
  } else if (!opts.testPattern && !opts.verify) {
    gates.push({
      name: 'Backend Verification',
      command: 'N/A',
      status: opts.skipBackend ? 'SKIPPED' : 'N/A',
      detail: opts.skipBackend ? 'Skipped via --skip-backend' : 'Backend untouched',
    });
  }

  // Gate: Frontend Next.js (auto-activates when frontend/package.json exists)
  const frontendDir = path.join(repoRoot, 'frontend');
  const hasFrontendPkg = fs.existsSync(path.join(frontendDir, 'package.json'));
  if (hasFrontendPkg && (ctx.scopes.touchesFrontend || opts.frontend)) {
    const pkg = JSON.parse(fs.readFileSync(path.join(frontendDir, 'package.json'), 'utf8'));
    const scripts = pkg.scripts || {};
    for (const [label, scriptName] of [
      ['Frontend Lint', 'lint'],
      ['Frontend Typecheck', 'typecheck'],
      ['Frontend Tests', 'test'],
      ['Frontend Build', 'build'],
    ]) {
      if (!scripts[scriptName]) continue;
      const res = runNpm(frontendDir, ['run', scriptName]);
      gates.push({
        name: label,
        command: `frontend: npm run ${scriptName}`,
        status: res.ok ? 'PASS' : 'FAIL',
        detail: `${res.durationSec}s`,
      });
      if (!res.ok) failures.push({ name: label, output: res.output });
    }
  } else {
    gates.push({
      name: 'Frontend Checks',
      command: 'N/A',
      status: 'N/A',
      detail: hasFrontendPkg ? 'Frontend untouched' : 'frontend/package.json not initialized yet',
    });
  }

  // Gate: Playwright E2E (Always --headed per user rule!)
  if (opts.e2eSpecs.length > 0 && hasFrontendPkg) {
    const e2eArgs =
      opts.e2eSpecs.length === 1 && opts.e2eSpecs[0] === 'all'
        ? ['exec', '--', 'playwright', 'test', '--headed']
        : ['exec', '--', 'playwright', 'test', '--headed', ...opts.e2eSpecs];
    const e2eRes = runNpm(frontendDir, e2eArgs);
    gates.push({
      name: 'Playwright E2E (Headed)',
      command: `frontend: npx playwright test --headed ${opts.e2eSpecs.join(' ')}`.trim(),
      status: e2eRes.ok ? 'PASS' : 'FAIL',
      detail: `${e2eRes.durationSec}s`,
    });
    if (!e2eRes.ok) failures.push({ name: 'Playwright E2E (Headed)', output: e2eRes.output });
  }

  console.log('\n## Quality Gates Summary');
  console.log('| Gate | Command | Status | Detail |');
  console.log('|---|---|---|---|');
  for (const g of gates) {
    console.log(
      `| ${escapeCell(g.name)} | \`${escapeCell(g.command)}\` | **${g.status}** | ${escapeCell(g.detail)} |`,
    );
  }

  for (const f of failures) {
    console.log(`\n### Failure Tail — ${f.name}\n\`\`\`text\n${tailLines(f.output)}\n\`\`\``);
  }

  const allPassed =
    branchOk &&
    hygieneOk &&
    failures.length === 0 &&
    !gates.some((g) => g.status === 'FAIL' || g.status === 'REQUIRED');

  console.log(`\n**VERDICT**: \`${allPassed ? 'ALL GATES GREEN' : 'CHECK FAILED / INCOMPLETE'}\``);
  process.exit(allPassed ? 0 : 1);
}

if (require.main === module) {
  runCli().catch((err) => {
    console.error(`[verify.cjs] Fatal error: ${err instanceof Error ? err.stack : String(err)}`);
    process.exit(1);
  });
}

module.exports = {
  CANONICAL_STATUSES,
  validateBranchName,
  parseGitStatusPorcelain,
  classifyScopes,
  scanCleanliness,
  parseUnifiedZeroAddedLines,
  parseStateMarkdown,
  inferTicketState,
  renderBoardMarkdown,
  buildBoardFromDisk,
  buildWinCmdCommand,
  extractMavenSummary,
};
