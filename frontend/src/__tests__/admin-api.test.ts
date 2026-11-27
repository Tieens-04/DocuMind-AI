import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createAdminDepartment,
  createAdminUser,
  deleteAdminDepartment,
  getAdminAnalyticsSummary,
  getAdminDailyTrends,
  getAdminTokenUsageByDepartment,
  listAdminAuditLogs,
  listAdminDepartmentsPage,
  listAdminUsers,
  resetAdminUserPassword,
  updateAdminDepartment,
  updateAdminUser,
} from '@/lib/api/admin';
import {
  resetTokenManagerForTests,
  setSessionTokens,
} from '@/lib/auth/token-manager';
import { ApiError, formatApiErrorMessage } from '@/lib/types/problem';

describe('admin API client and problem error mapping', () => {
  const fetchSpy = vi.fn<typeof fetch>();

  beforeEach(() => {
    resetTokenManagerForTests();
    vi.stubGlobal('fetch', fetchSpy);
    setSessionTokens({
      accessToken: 'admin-token-123',
      refreshToken: 'refresh-token-123',
      expiresIn: 900,
      refreshExpiresIn: 604800,
      user: {
        id: 'admin-1',
        email: 'admin@documind.ai',
        fullName: 'Root Admin',
        role: 'SYSTEM_ADMIN',
        departmentId: 'dept-1',
        departmentCode: 'IT',
        departmentName: 'IT',
        isActive: true,
        createdAt: '2026-11-26T00:00:00Z',
      },
    });
  });

  afterEach(() => {
    resetTokenManagerForTests();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  function mockJsonResponse(body: unknown, status = 200) {
    fetchSpy.mockResolvedValueOnce(
      new Response(body === undefined ? null : JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }

  it('serializes query parameters cleanly for analytics summary, breakdowns, trends, and audit logs', async () => {
    mockJsonResponse({
      from: '2026-11-01T00:00:00Z',
      to: '2026-11-27T00:00:00Z',
      successfulQueries: 10,
      cacheHits: 4,
      cacheHitRatioPercent: 40,
      promptTokens: 500,
      completionTokens: 200,
      totalTokens: 700,
      averageLatencyMs: 88.5,
    });
    const summary = await getAdminAnalyticsSummary({
      from: '2026-11-01T00:00:00Z',
      to: '2026-11-27T00:00:00Z',
    });
    expect(summary.averageLatencyMs).toBe(88.5);
    expect(String(fetchSpy.mock.calls[0]?.[0])).toContain(
      '/api/v1/admin/analytics/summary?from=2026-11-01T00%3A00%3A00Z&to=2026-11-27T00%3A00%3A00Z',
    );

    mockJsonResponse({ from: '2026-11-01T00:00:00Z', to: '2026-11-27T00:00:00Z', items: [] });
    await getAdminTokenUsageByDepartment({ from: '2026-11-01T00:00:00Z' });
    expect(String(fetchSpy.mock.calls[1]?.[0])).toContain(
      '/api/v1/admin/analytics/token-by-department?from=2026-11-01T00%3A00%3A00Z',
    );

    mockJsonResponse({ from: '2026-11-01T00:00:00Z', to: '2026-11-27T00:00:00Z', items: [] });
    await getAdminDailyTrends({});
    expect(String(fetchSpy.mock.calls[2]?.[0])).toMatch(/\/api\/v1\/admin\/analytics\/daily-trends$/);

    mockJsonResponse({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 });
    await listAdminAuditLogs({
      actionType: 'CHAT_QUERY',
      cacheHit: true,
      statusCode: 200,
      page: 1,
      size: 20,
    });
    const auditUrl = String(fetchSpy.mock.calls[3]?.[0]);
    expect(auditUrl).toContain('actionType=CHAT_QUERY');
    expect(auditUrl).toContain('cacheHit=true');
    expect(auditUrl).toContain('statusCode=200');
    expect(auditUrl).toContain('page=1');
    expect(auditUrl).toContain('size=20');
  });

  it('sends expected payloads for user and department CRUD including clearing description with empty string', async () => {
    mockJsonResponse({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    await listAdminUsers({ q: '  alice ', role: 'DEPARTMENT_MANAGER', isActive: false });
    const usersUrl = String(fetchSpy.mock.calls[0]?.[0]);
    expect(usersUrl).toContain('q=alice');
    expect(usersUrl).toContain('role=DEPARTMENT_MANAGER');
    expect(usersUrl).toContain('isActive=false');

    mockJsonResponse({ id: 'u1' }, 201);
    await createAdminUser({
      email: 'alice@documind.ai',
      fullName: 'Alice',
      password: 'Password123!',
      role: 'STANDARD_EMPLOYEE',
      departmentId: 'd1',
      isActive: true,
    });
    expect(fetchSpy.mock.calls[1]?.[1]?.method).toBe('POST');

    mockJsonResponse({ id: 'u1' });
    await updateAdminUser('u1', { role: 'DEPARTMENT_MANAGER' });
    expect(fetchSpy.mock.calls[2]?.[1]?.method).toBe('PATCH');

    fetchSpy.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await resetAdminUserPassword('u1', { newPassword: 'NewPassword123!' });
    expect(fetchSpy.mock.calls[3]?.[1]?.method).toBe('PUT');
    expect(String(fetchSpy.mock.calls[3]?.[0])).toContain('/api/v1/admin/users/u1/password');

    mockJsonResponse({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    await listAdminDepartmentsPage({ q: 'HR' });
    expect(String(fetchSpy.mock.calls[4]?.[0])).toContain('/api/v1/admin/departments?q=HR');

    mockJsonResponse({ id: 'd1', code: 'HR' }, 201);
    await createAdminDepartment({ code: 'HR', name: 'Human Resources', description: null });
    expect(fetchSpy.mock.calls[5]?.[1]?.method).toBe('POST');

    mockJsonResponse({ id: 'd1', code: 'HR', description: null });
    await updateAdminDepartment('d1', { description: '' });
    expect(fetchSpy.mock.calls[6]?.[1]?.method).toBe('PATCH');
    expect(fetchSpy.mock.calls[6]?.[1]?.body).toBe('{"description":""}');

    fetchSpy.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await deleteAdminDepartment('d1');
    expect(fetchSpy.mock.calls[7]?.[1]?.method).toBe('DELETE');
  });

  it('maps all 5 AdminConflictException error codes and INVALID_ADMIN_REQUEST to Vietnamese messages', () => {
    const cases: Array<[string, string]> = [
      ['DUPLICATE_EMAIL', 'Email này đã được sử dụng cho một tài khoản khác.'],
      ['DUPLICATE_DEPARTMENT_CODE', 'Mã phòng ban này đã tồn tại trong hệ thống.'],
      ['DEPARTMENT_IN_USE', 'Không thể xóa phòng ban đang có nhân sự hoặc tài liệu trực thuộc.'],
      [
        'ADMIN_SELF_MODIFICATION_FORBIDDEN',
        'Quản trị viên không thể tự vô hiệu hóa hoặc tự hạ quyền tài khoản của chính mình.',
      ],
      [
        'LAST_ACTIVE_ADMIN',
        'Không thể khóa hoặc hạ quyền tài khoản SYSTEM_ADMIN đang hoạt động cuối cùng.',
      ],
    ];

    for (const [errorCode, expected] of cases) {
      expect(formatApiErrorMessage(new ApiError({ status: 409, errorCode, detail: 'raw' }))).toBe(
        expected,
      );
    }

    expect(
      formatApiErrorMessage(
        new ApiError({ status: 400, errorCode: 'INVALID_ADMIN_REQUEST', detail: 'Invalid range' }),
      ),
    ).toBe('Invalid range');
  });
});
