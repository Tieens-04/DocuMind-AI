import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AdminAnalyticsPage from '@/app/(workspace)/admin/analytics/page';
import AdminUsersPage from '@/app/(workspace)/admin/users/page';
import { RoleGuard } from '@/components/layout/role-guard';
import { RoleNav } from '@/components/layout/role-nav';
import * as adminApi from '@/lib/api/admin';
import type { Role, UserProfileResponse } from '@/lib/types/auth';

let mockPathname = '/admin/analytics';

vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
}));

vi.mock('@/providers/auth-provider', () => ({
  useAuth: () => ({
    user: buildUser('SYSTEM_ADMIN'),
    status: 'authenticated',
    bootstrapError: null,
    login: vi.fn(),
    logout: vi.fn(),
    retryBootstrap: vi.fn(),
  }),
}));

vi.mock('@/lib/api/admin', () => ({
  getAdminAnalyticsSummary: vi.fn(),
  getAdminTokenUsageByDepartment: vi.fn(),
  getAdminDailyTrends: vi.fn(),
  listAdminAuditLogs: vi.fn(),
  listAdminDepartmentsPage: vi.fn(),
  listAdminUsers: vi.fn(),
}));

const mockedAdminApi = vi.mocked(adminApi);

function buildUser(role: Role): UserProfileResponse {
  return {
    id: `user-${role.toLowerCase()}`,
    email: `${role.toLowerCase()}@documind.ai`,
    fullName: role,
    role,
    departmentId: 'dept-1',
    departmentCode: 'HR',
    departmentName: 'Nhân sự',
    isActive: true,
    createdAt: '2026-11-01T00:00:00Z',
  };
}

describe('Admin routes RBAC flow across all 3 roles (Admin, Manager, Viewer)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedAdminApi.getAdminAnalyticsSummary.mockResolvedValue({
      from: '2026-10-28T00:00:00Z',
      to: '2026-11-27T00:00:00Z',
      successfulQueries: 2,
      cacheHits: 1,
      cacheHitRatioPercent: 50,
      promptTokens: 100,
      completionTokens: 40,
      totalTokens: 140,
      averageLatencyMs: 25,
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
    mockedAdminApi.listAdminAuditLogs.mockResolvedValue({
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    mockedAdminApi.listAdminDepartmentsPage.mockResolvedValue({
      items: [],
      page: 0,
      size: 100,
      totalElements: 0,
      totalPages: 0,
    });
    mockedAdminApi.listAdminUsers.mockResolvedValue({
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
  });

  it('allows SYSTEM_ADMIN to access both /admin/analytics and /admin/users with full nav links', async () => {
    mockPathname = '/admin/analytics';
    render(
      <>
        <RoleNav role="SYSTEM_ADMIN" />
        <RoleGuard user={buildUser('SYSTEM_ADMIN')}>
          <AdminAnalyticsPage />
        </RoleGuard>
      </>,
    );

    expect(screen.getByRole('link', { name: /FinOps & Audit/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Nhân sự & Phòng ban/i })).toBeInTheDocument();
    expect(await screen.findByTestId('kpi-avg-latency')).toHaveTextContent('25.00 ms');

    mockPathname = '/admin/users';
    render(
      <RoleGuard user={buildUser('SYSTEM_ADMIN')}>
        <AdminUsersPage />
      </RoleGuard>,
    );
    expect(await screen.findByRole('tab', { name: /Tài khoản Nhân sự/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Phòng ban/i })).toBeInTheDocument();
  });

  it.each<['DEPARTMENT_MANAGER' | 'STANDARD_EMPLOYEE', string]>([
    ['DEPARTMENT_MANAGER', '/admin/analytics'],
    ['DEPARTMENT_MANAGER', '/admin/users'],
    ['STANDARD_EMPLOYEE', '/admin/analytics'],
    ['STANDARD_EMPLOYEE', '/admin/users'],
  ])(
    'blocks %s on %s with 403 ForbiddenView and fires 0 admin API requests',
    (role, path) => {
      mockPathname = path;
      render(
        <>
          <RoleNav role={role} />
          <RoleGuard user={buildUser(role)}>
            {path === '/admin/analytics' ? <AdminAnalyticsPage /> : <AdminUsersPage />}
          </RoleGuard>
        </>,
      );

      expect(screen.getByText('403 Forbidden')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Quay về Trợ lý Chat/i })).toHaveAttribute(
        'href',
        '/chat',
      );
      expect(screen.queryByRole('link', { name: /FinOps & Audit/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /Nhân sự & Phòng ban/i })).not.toBeInTheDocument();

      expect(mockedAdminApi.getAdminAnalyticsSummary).not.toHaveBeenCalled();
      expect(mockedAdminApi.getAdminTokenUsageByDepartment).not.toHaveBeenCalled();
      expect(mockedAdminApi.getAdminDailyTrends).not.toHaveBeenCalled();
      expect(mockedAdminApi.listAdminAuditLogs).not.toHaveBeenCalled();
      expect(mockedAdminApi.listAdminUsers).not.toHaveBeenCalled();
      expect(mockedAdminApi.listAdminDepartmentsPage).not.toHaveBeenCalled();
    },
  );
});
