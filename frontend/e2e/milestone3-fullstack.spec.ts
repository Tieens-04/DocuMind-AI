import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import { SAFE_REFUSAL_MESSAGE } from '../src/lib/types/chat';

const API_BASE_URL = (process.env.PLAYWRIGHT_API_URL || 'http://localhost:8081').replace(/\/+$/, '');
const DEV_PASSWORD = process.env.PLAYWRIGHT_DEV_PASSWORD || 'DocuMind@2026!';

const RUN_TOKEN = Date.now().toString().slice(-6);
const HR_FILENAME = `e2e-hr-policy-${RUN_TOKEN}.pdf`;
const PUBLIC_FILENAME = `e2e-public-handbook-${RUN_TOKEN}.pdf`;

const HR_QUESTION = `In DocuMind HR Policy ${RUN_TOKEN}, what is the annual leave entitlement and remote work allowance under code HR-ALPHA-${RUN_TOKEN}?`;
const PUBLIC_QUESTION = `In DocuMind Company Handbook ${RUN_TOKEN}, what is the IT helpdesk emergency hotline extension and Friday quiet hours start time under policy PUB-BETA-${RUN_TOKEN}?`;

function escapePdfText(text: string): string {
  return text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
}

function buildTwoPagePdfBuffer(page1Lines: string[], page2Lines: string[]): Buffer {
  const buildStream = (lines: string[]) => {
    const ops = ['BT', '/F1 11 Tf', '50 750 Td', '16 TL'];
    lines.forEach((line, index) => {
      if (index > 0) ops.push('T*');
      ops.push(`(${escapePdfText(line)}) Tj`);
    });
    ops.push('ET');
    return ops.join('\n');
  };

  const stream1 = buildStream(page1Lines);
  const stream2 = buildStream(page2Lines);

  const objects: string[] = [
    '1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n',
    '2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n',
    '3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 7 0 R >> >> >>\nendobj\n',
    '4 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>\nendobj\n',
    `5 0 obj\n<< /Length ${Buffer.byteLength(stream1, 'utf8')} >>\nstream\n${stream1}\nendstream\nendobj\n`,
    `6 0 obj\n<< /Length ${Buffer.byteLength(stream2, 'utf8')} >>\nstream\n${stream2}\nendstream\nendobj\n`,
    '7 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n',
  ];

  let pdf = '%PDF-1.4\n';
  const offsets: number[] = [0];
  for (const obj of objects) {
    offsets.push(Buffer.byteLength(pdf, 'utf8'));
    pdf += obj;
  }

  const xrefOffset = Buffer.byteLength(pdf, 'utf8');
  pdf += `xref\n0 ${objects.length + 1}\n`;
  pdf += '0000000000 65535 f \n';
  for (let i = 1; i <= objects.length; i += 1) {
    pdf += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;
  return Buffer.from(pdf, 'utf8');
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email doanh nghiệp').fill(email);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  await expect(page).toHaveURL(/\/chat(?:\?|$)/, { timeout: 20_000 });
  await expect(page.locator('#chat-question-input')).toBeVisible();
}

async function logoutViaUi(page: Page): Promise<void> {
  const logoutBtn = page.getByRole('button', { name: 'Đăng xuất' }).first();
  await expect(logoutBtn).toBeVisible();
  await logoutBtn.click();
  await expect(page).toHaveURL(/\/login(?:\?|$)/, { timeout: 15_000 });
}

async function uploadPdfAndWaitForCompleted(
  page: Page,
  filename: string,
  buffer: Buffer,
  createdDocumentIds: string[],
): Promise<void> {
  const fileInput = page.getByLabel('Chọn tệp tải lên');
  await expect(fileInput).toBeAttached();

  const uploadResponsePromise = page.waitForResponse(
    (res) => res.url().includes('/api/v1/documents') && res.request().method() === 'POST',
  );

  await fileInput.setInputFiles({
    name: filename,
    mimeType: 'application/pdf',
    buffer,
  });

  const uploadRes = await uploadResponsePromise;
  expect(uploadRes.status()).toBe(202);
  const uploadedDoc = (await uploadRes.json()) as { id: string };
  createdDocumentIds.push(uploadedDoc.id);

  const docRow = page.locator('tr', { hasText: filename });
  await expect(docRow).toBeVisible({ timeout: 15_000 });
  await expect(docRow.getByText('Hoàn tất')).toBeVisible({ timeout: 60_000 });
}

async function submitChatQuestionAndWait(page: Page, question: string): Promise<Locator> {
  const messageItems = page.locator('main ol.max-w-4xl > li');
  const prevCount = await messageItems.count();
  const input = page.locator('#chat-question-input');
  const sendBtn = page.getByRole('button', { name: 'Gửi', exact: true });
  await input.fill(question);
  await sendBtn.click();
  await expect(messageItems).toHaveCount(prevCount + 2, { timeout: 20_000 });
  await expect(sendBtn).toBeVisible({ timeout: 90_000 });
  await expect(page.getByRole('progressbar')).toHaveCount(0, { timeout: 90_000 });

  const lastAssistant = messageItems.last();
  await expect(lastAssistant.getByRole('alert')).toHaveCount(0);
  return lastAssistant;
}

async function deleteDocumentsAsAdmin(
  request: APIRequestContext,
  documentIds: string[],
): Promise<void> {
  if (documentIds.length === 0) return;

  const loginRes = await request.post(`${API_BASE_URL}/api/v1/auth/login`, {
    data: { email: 'admin@documind.ai', password: DEV_PASSWORD },
  });
  if (!loginRes.ok()) {
    throw new Error(`E2E cleanup admin login failed: HTTP ${loginRes.status()}`);
  }

  const authJson = (await loginRes.json()) as { accessToken?: string };
  if (!authJson.accessToken) {
    throw new Error('E2E cleanup admin login response missing accessToken');
  }

  const failures: string[] = [];
  for (const id of documentIds) {
    try {
      const res = await request.delete(`${API_BASE_URL}/api/v1/documents/${id}`, {
        headers: { Authorization: `Bearer ${authJson.accessToken}` },
      });
      if (!res.ok() && res.status() !== 404) {
        failures.push(`${id}: HTTP ${res.status()}`);
      }
    } catch (error) {
      failures.push(`${id}: ${String(error)}`);
    }
  }

  if (failures.length > 0) {
    throw new Error(`E2E cleanup failed to delete documents: ${failures.join('; ')}`);
  }
}

test.describe.serial('Milestone 3 Full-Stack E2E across Manager, Viewer, and Admin', () => {
  const createdDocumentIds: string[] = [];

  test.afterAll(async ({ request }) => {
    await deleteDocumentsAsAdmin(request, createdDocumentIds);
  });

  test('DEPARTMENT_MANAGER (HR): upload PDF, chat with citation preview, and enforce admin 403', async ({
    page,
    request,
  }) => {
    await loginViaUi(page, 'manager.hr@documind.ai', DEV_PASSWORD);

    await page.getByRole('link', { name: 'Kho tài liệu' }).click();
    await expect(page).toHaveURL(/\/documents$/);

    const hrPdfBuffer = buildTwoPagePdfBuffer(
      [
        `DocuMind HR Policy ${RUN_TOKEN} (Confidential Internal HR Guidelines).`,
        `Under policy code HR-ALPHA-${RUN_TOKEN}, the annual leave entitlement is 18 paid days`,
        `per calendar year and the remote work allowance is 450 USD per quarter for HR staff.`,
      ],
      [
        `DocuMind HR Onboarding ${RUN_TOKEN}.`,
        `New HR hires complete compliance training within 7 business days at Room 402B.`,
      ],
    );

    await uploadPdfAndWaitForCompleted(page, HR_FILENAME, hrPdfBuffer, createdDocumentIds);

    await page.getByRole('link', { name: 'Trợ lý Chat' }).click();
    await expect(page).toHaveURL(/\/chat$/);

    const hrAnswer = await submitChatQuestionAndWait(page, HR_QUESTION);
    await expect(hrAnswer).toContainText(/18/, { timeout: 15_000 });
    await expect(hrAnswer).toContainText(/450/, { timeout: 15_000 });

    const citationBtn = hrAnswer.getByRole('button', {
      name: new RegExp(`Nguồn 1:.*${HR_FILENAME}`),
    });
    await expect(citationBtn).toBeVisible();
    await citationBtn.click();

    const previewLink = page.getByRole('link', { name: /Mở tài liệu gốc/i });
    await expect(previewLink).toBeVisible({ timeout: 15_000 });
    const previewHref = await previewLink.getAttribute('href');
    expect(previewHref).toBeTruthy();

    const expectedApiOrigin = new URL(API_BASE_URL).origin;
    const escapedOrigin = expectedApiOrigin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    expect(previewHref).toMatch(new RegExp(`^${escapedOrigin}/api/v1/documents/`));
    expect(previewHref).not.toContain('minio:9000');
    expect(previewHref).toMatch(/#page=\d+/);

    const previewFetch = await request.get(previewHref!);
    expect(previewFetch.status()).toBe(200);
    expect(previewFetch.headers()['content-type'] ?? '').toContain('application/pdf');

    await page.getByRole('button', { name: 'Đóng ngăn trích dẫn nguồn' }).click();

    await page.goto('/admin/analytics');
    await expect(page.getByText('403 Forbidden')).toBeVisible();
    await page.goto('/admin/users');
    await expect(page.getByText('403 Forbidden')).toBeVisible();

    await logoutViaUi(page);
  });

  test('STANDARD_EMPLOYEE (SALES Viewer): hides HR docs, enforces safe refusal, and blocks admin routes', async ({
    page,
    request,
  }) => {
    await loginViaUi(page, 'employee.sales@documind.ai', DEV_PASSWORD);

    await expect(page.getByRole('link', { name: 'Kho tài liệu' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'FinOps & Audit' })).toHaveCount(0);

    await page.goto('/documents');
    await expect(page.getByText('403 Forbidden')).toBeVisible();

    const viewerLoginRes = await request.post(`${API_BASE_URL}/api/v1/auth/login`, {
      data: { email: 'employee.sales@documind.ai', password: DEV_PASSWORD },
    });
    expect(viewerLoginRes.ok()).toBe(true);
    const viewerAuth = (await viewerLoginRes.json()) as { accessToken: string };
    const docsRes = await request.get(`${API_BASE_URL}/api/v1/documents?size=100`, {
      headers: { Authorization: `Bearer ${viewerAuth.accessToken}` },
    });
    expect(docsRes.ok()).toBe(true);
    const docsBody = JSON.stringify(await docsRes.json());
    expect(docsBody).not.toContain(HR_FILENAME);

    await page.goto('/chat');
    await expect(page.locator('#chat-question-input')).toBeVisible();

    const refusalAnswer = await submitChatQuestionAndWait(page, HR_QUESTION);
    await expect(refusalAnswer).toContainText(SAFE_REFUSAL_MESSAGE, { timeout: 20_000 });
    await expect(refusalAnswer.getByRole('button', { name: /Nguồn \d+:/ })).toHaveCount(0);

    await page.goto('/admin/analytics');
    await expect(page.getByText('403 Forbidden')).toBeVisible();

    await logoutViaUi(page);
  });

  test('SYSTEM_ADMIN (IT): PUBLIC upload, Redis/Semantic Cache Hit, FinOps Analytics, and Users/Departments', async ({
    page,
  }) => {
    await loginViaUi(page, 'admin@documind.ai', DEV_PASSWORD);

    await page.getByRole('link', { name: 'Kho tài liệu' }).click();
    await expect(page).toHaveURL(/\/documents$/);

    const accessSelect = page.getByLabel('Mức quyền truy cập mặc định');
    await accessSelect.selectOption('PUBLIC');

    const deptSelect = page.getByLabel(/Phòng ban sở hữu/);
    await expect(deptSelect).toBeVisible();
    const currentDeptValue = await deptSelect.inputValue();
    if (!currentDeptValue) {
      await deptSelect.selectOption({ index: 1 });
    }

    const publicPdfBuffer = buildTwoPagePdfBuffer(
      [
        `DocuMind Company Handbook ${RUN_TOKEN} (All Employees).`,
        `Under policy PUB-BETA-${RUN_TOKEN}, the corporate IT helpdesk emergency hotline`,
        `is extension 8899 and office quiet hours start at 18:30 every Friday.`,
      ],
      [
        `DocuMind Travel Guideline ${RUN_TOKEN}.`,
        `Standard domestic meal reimbursement cap is 55 USD per day.`,
      ],
    );

    await uploadPdfAndWaitForCompleted(page, PUBLIC_FILENAME, publicPdfBuffer, createdDocumentIds);

    await page.getByRole('link', { name: 'Trợ lý Chat' }).click();
    await expect(page).toHaveURL(/\/chat$/);

    const firstAnswer = await submitChatQuestionAndWait(page, PUBLIC_QUESTION);
    await expect(firstAnswer).toContainText(/8899/, { timeout: 15_000 });

    await page.goto('/chat');
    await expect(page.locator('#chat-question-input')).toBeVisible();
    const cachedAnswer = await submitChatQuestionAndWait(page, PUBLIC_QUESTION);
    await expect(cachedAnswer).toContainText(/8899/, { timeout: 15_000 });
    await expect(cachedAnswer.getByText(/(?:Redis L1|Semantic L2)/)).toBeVisible({
      timeout: 15_000,
    });

    await page.getByRole('link', { name: 'FinOps & Audit' }).click();
    await expect(page).toHaveURL(/\/admin\/analytics$/);
    await expect(page.locator('main [role="alert"]')).toHaveCount(0);
    await expect(page.getByTestId('kpi-total-queries')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('kpi-total-queries').locator('.text-2xl')).not.toHaveText('0', {
      timeout: 15_000,
    });
    await expect(page.getByTestId('kpi-avg-latency')).toBeVisible({ timeout: 15_000 });
    await expect(
      page.locator('table tbody tr').filter({ hasText: 'admin@documind.ai' }).first(),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByRole('link', { name: 'Nhân sự & Phòng ban' }).click();
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page.locator('main [role="alert"]')).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'Tài khoản Nhân sự' })).toBeVisible();
    await expect(page.locator('table tbody').getByText('manager.hr@documind.ai')).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.locator('table tbody').getByText('admin@documind.ai')).toBeVisible({
      timeout: 15_000,
    });
    await page.getByRole('tab', { name: 'Phòng ban' }).click();
    await expect(page.locator('table tbody').getByText('HR', { exact: true })).toBeVisible({
      timeout: 15_000,
    });

    await logoutViaUi(page);
  });
});
