import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AdminAnalyticsPage from '@/app/(workspace)/admin/analytics/page';
import { formatDepartmentTooltipValue } from '@/components/admin/finops-charts';
import * as adminApi from '@/lib/api/admin';
import { ApiError } from '@/lib/types/problem';

vi.mock('@/lib/api/admin', () => ({
  getAdminAnalyticsSummary: vi.fn(),
  getAdminTokenUsageByDepartment: vi.fn(),
  getAdminDailyTrends: vi.fn(),
  listAdminAuditLogs: vi.fn(),
  listAdminDepartmentsPage: vi.fn(),
  listAdminUsers: vi.fn(),
}));

const mockedAdminApi = vi.mocked(adminApi);

const SUMMARY_FIXTURE = {
  from: '2026-10-28T00:00:00Z',
  to: '2026-11-27T00:00:00Z',
  successfulQueries: 12,
  cacheHits: 5,
  cacheHitRatioPercent: 41.67,
  promptTokens: 3200,
  completionTokens: 1300,
  totalTokens: 4500,
  averageLatencyMs: 52.67,
};

const DEPT_USAGE_FIXTURE = {
  from: '2026-10-28T00:00:00Z',
  to: '2026-11-27T00:00:00Z',
  items: [
    {
      departmentId: 'd-hr',
      departmentCode: 'HR',
      departmentName: 'Nhân sự',
      successfulQueries: 8,
      cacheHits: 4,
      cacheHitRatioPercent: 50,
      promptTokens: 2000,
      completionTokens: 800,
      totalTokens: 2800,
    },
    {
      departmentId: null,
      departmentCode: null,
      departmentName: null,
      successfulQueries: 4,
      cacheHits: 1,
      cacheHitRatioPercent: 25,
      promptTokens: 1200,
      completionTokens: 500,
      totalTokens: 1700,
    },
  ],
};

const TRENDS_FIXTURE = {
  from: '2026-10-28T00:00:00Z',
  to: '2026-11-27T00:00:00Z',
  items: [
    {
      date: '2026-11-26',
      successfulQueries: 7,
      cacheHits: 3,
      cacheHitRatioPercent: 42.86,
      promptTokens: 1800,
      completionTokens: 700,
      totalTokens: 2500,
    },
  ],
};

const AUDIT_LOGS_FIXTURE = {
  items: [
    {
      id: 'audit-1',
      userId: 'u-1',
      userEmail: 'viewer@documind.ai',
      departmentId: 'd-hr',
      departmentCode: 'HR',
      departmentName: 'Nhân sự',
      sessionId: 'sess-999',
      actionType: 'CHAT_QUERY' as const,
      queryText: 'Quy định nghỉ phép năm cho nhân viên chính thức là bao nhiêu ngày?',
      abstained: false,
      cacheHit: true,
      cacheTier: 'L1' as const,
      promptTokens: 120,
      completionTokens: 45,
      totalTokens: 165,
      latencyMs: 18,
      provider: 'ollama',
      statusCode: 200,
      ipAddress: '10.0.0.15',
      createdAt: '2026-11-27T10:15:00Z',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 25,
  totalPages: 2,
};

describe('AdminAnalyticsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedAdminApi.getAdminAnalyticsSummary.mockResolvedValue(SUMMARY_FIXTURE);
    mockedAdminApi.getAdminTokenUsageByDepartment.mockResolvedValue(DEPT_USAGE_FIXTURE);
    mockedAdminApi.getAdminDailyTrends.mockResolvedValue(TRENDS_FIXTURE);
    mockedAdminApi.listAdminAuditLogs.mockResolvedValue(AUDIT_LOGS_FIXTURE);
    mockedAdminApi.listAdminDepartmentsPage.mockResolvedValue({
      items: [
        {
          id: 'd-hr',
          code: 'HR',
          name: 'Nhân sự',
          description: null,
          cacheVersion: 1,
          createdAt: '2026-11-01T00:00:00Z',
          updatedAt: '2026-11-01T00:00:00Z',
        },
      ],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
    });
    mockedAdminApi.listAdminUsers.mockResolvedValue({
      items: [
        {
          id: 'u-1',
          email: 'viewer@documind.ai',
          fullName: 'Viewer One',
          role: 'STANDARD_EMPLOYEE',
          departmentId: 'd-hr',
          departmentCode: 'HR',
          departmentName: 'Nhân sự',
          isActive: true,
          createdAt: '2026-11-01T00:00:00Z',
          updatedAt: '2026-11-01T00:00:00Z',
        },
      ],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
    });
  });

  it('renders all 4 KPI cards matching audit_logs, both charts including null-department label, and audit table', async () => {
    render(<AdminAnalyticsPage />);

    expect(await screen.findByTestId('kpi-total-queries')).toHaveTextContent('12');
    expect(screen.getByTestId('kpi-total-tokens')).toHaveTextContent('4.500');
    expect(screen.getByTestId('kpi-cache-hit-ratio')).toHaveTextContent('41.67%');
    expect(screen.getByTestId('kpi-avg-latency')).toHaveTextContent('52.67 ms');

    expect(screen.getByTestId('chart-token-by-department')).toHaveTextContent(
      'Hệ thống / Chưa gán phòng ban: 1700 tokens',
    );
    expect(screen.getByTestId('chart-daily-trends')).toHaveTextContent(
      '2026-11-26: 2500 tokens, 42.86% cache hit',
    );
    expect(screen.getByRole('button', { name: 'viewer@documind.ai' })).toBeInTheDocument();
  });

  it('switches time presets, validates invalid custom ranges, filters audit logs, paginates, and opens detail sheet', async () => {
    const user = userEvent.setup();
    render(<AdminAnalyticsPage />);

    await screen.findByRole('button', { name: 'viewer@documind.ai' });

    await user.click(screen.getByRole('button', { name: '7 ngày' }));
    await waitFor(() => {
      expect(mockedAdminApi.getAdminAnalyticsSummary).toHaveBeenCalledTimes(2);
    });

    await user.click(screen.getByRole('button', { name: 'Tùy chỉnh' }));
    const fromInput = screen.getByLabelText('Từ ngày');
    const toInput = screen.getByLabelText('Đến ngày');
    await user.clear(fromInput);
    await user.type(fromInput, '2026-11-25');
    await user.clear(toInput);
    await user.type(toInput, '2026-11-20');
    await user.click(screen.getByRole('button', { name: 'Áp dụng' }));

    expect(
      screen.getByText('Ngày bắt đầu phải nhỏ hơn hoặc bằng ngày kết thúc.'),
    ).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc theo loại hành động'), 'RATE_LIMITED');
    await user.selectOptions(screen.getByLabelText('Lọc theo mã HTTP'), '429');
    await waitFor(() => {
      expect(mockedAdminApi.listAdminAuditLogs).toHaveBeenLastCalledWith(
        expect.objectContaining({
          actionType: 'RATE_LIMITED',
          statusCode: 429,
          page: 0,
        }),
        expect.any(AbortSignal),
      );
    });

    await user.click(screen.getByRole('button', { name: 'Trang sau' }));
    await waitFor(() => {
      expect(mockedAdminApi.listAdminAuditLogs).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 1 }),
        expect.any(AbortSignal),
      );
    });

    await user.click(screen.getByRole('button', { name: 'Xem chi tiết bản ghi audit-1' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText('Quy định nghỉ phép năm cho nhân viên chính thức là bao nhiêu ngày?'),
    ).toBeInTheDocument();
    expect(within(dialog).getByText('10.0.0.15')).toBeInTheDocument();
    expect(within(dialog).getByText('sess-999')).toBeInTheDocument();
  });

  it('shows error banner and allows retrying when analytics overview fails', async () => {
    const user = userEvent.setup();
    mockedAdminApi.getAdminAnalyticsSummary.mockRejectedValueOnce(
      new ApiError({ status: 503, detail: 'Analytics unavailable' }),
    );
    render(<AdminAnalyticsPage />);

    expect(await screen.findByText('Analytics unavailable')).toBeInTheDocument();
    mockedAdminApi.getAdminAnalyticsSummary.mockResolvedValueOnce(SUMMARY_FIXTURE);
    await user.click(screen.getAllByRole('button', { name: 'Thử lại' })[0]);

    expect(await screen.findByTestId('kpi-total-queries')).toHaveTextContent('12');
  });

  it('formats stacked bar tooltip series names accurately and advances preset "to" on refresh', async () => {
    expect(formatDepartmentTooltipValue(2000, 'Prompt Tokens')).toEqual(['2.000', 'Prompt Tokens']);
    expect(formatDepartmentTooltipValue(800, 'Completion Tokens')).toEqual([
      '800',
      'Completion Tokens',
    ]);

    const user = userEvent.setup();
    render(<AdminAnalyticsPage />);
    await screen.findByTestId('kpi-total-queries');

    const initialTo = mockedAdminApi.getAdminAnalyticsSummary.mock.calls[0]?.[0]?.to;
    await new Promise((resolve) => setTimeout(resolve, 15));
    await user.click(screen.getByRole('button', { name: 'Làm mới' }));

    await waitFor(() => {
      expect(mockedAdminApi.getAdminAnalyticsSummary).toHaveBeenCalledTimes(2);
    });
    const refreshedTo = mockedAdminApi.getAdminAnalyticsSummary.mock.calls[1]?.[0]?.to;
    expect(refreshedTo).toBeDefined();
    expect(Date.parse(refreshedTo!)).toBeGreaterThan(Date.parse(initialTo!));
  });

  it('rejects >366d custom range, applies valid custom range, renders empty 0.00 state, and preserves out-of-list quick-filtered userId', async () => {
    const user = userEvent.setup();
    mockedAdminApi.listAdminUsers.mockResolvedValueOnce({
      items: [],
      page: 0,
      size: 100,
      totalElements: 0,
      totalPages: 0,
    });
    mockedAdminApi.getAdminAnalyticsSummary.mockResolvedValue({
      ...SUMMARY_FIXTURE,
      successfulQueries: 0,
      cacheHits: 0,
      cacheHitRatioPercent: 0,
      promptTokens: 0,
      completionTokens: 0,
      totalTokens: 0,
      averageLatencyMs: 0,
    });
    mockedAdminApi.getAdminTokenUsageByDepartment.mockResolvedValue({
      from: '2026-10-28T00:00:00Z',
      to: '2026-11-27T00:00:00Z',
      items: [],
    });
    mockedAdminApi.getAdminDailyTrends.mockResolvedValue({
      from: '2026-10-28T00:00:00Z',
      to: '2026-11-27T00:00:00Z',
      items: [],
    });

    render(<AdminAnalyticsPage />);
    expect(await screen.findByTestId('kpi-cache-hit-ratio')).toHaveTextContent('0.00%');
    expect(screen.getByTestId('kpi-avg-latency')).toHaveTextContent('0.00 ms');
    expect(
      screen.getByText('Chưa có dữ liệu tiêu thụ Token theo phòng ban trong khoảng thời gian này.'),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'viewer@documind.ai' }));
    const userSelect = screen.getByLabelText('Lọc theo người dùng') as HTMLSelectElement;
    await waitFor(() => expect(userSelect.value).toBe('u-1'));
    expect(within(userSelect).getByRole('option', { name: 'viewer@documind.ai' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Tùy chỉnh' }));
    const fromInput = screen.getByLabelText('Từ ngày');
    const toInput = screen.getByLabelText('Đến ngày');
    await user.clear(fromInput);
    await user.type(fromInput, '2025-01-01');
    await user.clear(toInput);
    await user.type(toInput, '2026-11-27');
    await user.click(screen.getByRole('button', { name: 'Áp dụng' }));
    expect(screen.getByText('Khoảng thời gian thống kê tối đa 366 ngày.')).toBeInTheDocument();

    await user.clear(fromInput);
    await user.type(fromInput, '2026-11-01');
    await user.clear(toInput);
    await user.type(toInput, '2026-11-10');
    await user.click(screen.getByRole('button', { name: 'Áp dụng' }));
    await waitFor(() => {
      expect(mockedAdminApi.getAdminAnalyticsSummary).toHaveBeenLastCalledWith(
        {
          from: '2026-11-01T00:00:00Z',
          to: '2026-11-11T00:00:00.000Z',
        },
        expect.any(AbortSignal),
      );
    });
  });
});
