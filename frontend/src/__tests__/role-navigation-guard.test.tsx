import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RoleGuard } from '@/components/layout/role-guard';
import { RoleNav } from '@/components/layout/role-nav';
import type { Role, UserProfileResponse } from '@/lib/types/auth';

let mockPathname = '/chat';

vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
}));

function buildUser(role: Role): UserProfileResponse {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    email: `${role.toLowerCase()}@documind.ai`,
    fullName: role,
    role,
    departmentId: '22222222-2222-2222-2222-222222222222',
    departmentCode: 'HR',
    departmentName: 'HR',
    isActive: true,
    createdAt: '2026-11-26T00:00:00Z',
  };
}

describe('RoleNav and RoleGuard', () => {
  it('shows only /chat for STANDARD_EMPLOYEE and marks current page with aria-current', () => {
    mockPathname = '/chat';
    render(<RoleNav role="STANDARD_EMPLOYEE" />);

    const chatLink = screen.getByRole('link', { name: /Trợ lý Chat/i });
    expect(chatLink).toHaveAttribute('aria-current', 'page');
    expect(screen.queryByRole('link', { name: /Kho Tài liệu/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /FinOps & Audit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Nhân sự & Phòng ban/i })).not.toBeInTheDocument();
  });

  it('shows /chat and /documents for DEPARTMENT_MANAGER without admin routes', () => {
    mockPathname = '/documents';
    render(<RoleNav role="DEPARTMENT_MANAGER" />);

    expect(screen.getByRole('link', { name: /Trợ lý Chat/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Kho Tài liệu/i })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.queryByRole('link', { name: /FinOps & Audit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Nhân sự & Phòng ban/i })).not.toBeInTheDocument();
  });

  it('shows all 4 navigation links for SYSTEM_ADMIN', () => {
    mockPathname = '/admin/analytics';
    render(<RoleNav role="SYSTEM_ADMIN" />);

    expect(screen.getByRole('link', { name: /Trợ lý Chat/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Kho Tài liệu/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /FinOps & Audit/i })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('link', { name: /Nhân sự & Phòng ban/i })).toBeInTheDocument();
  });

  it('renders 403 Forbidden screen with /chat CTA when STANDARD_EMPLOYEE visits /documents', () => {
    mockPathname = '/documents';
    render(
      <RoleGuard user={buildUser('STANDARD_EMPLOYEE')}>
        <div>Protected Documents Content</div>
      </RoleGuard>,
    );

    expect(screen.queryByText('Protected Documents Content')).not.toBeInTheDocument();
    expect(screen.getByText('403 Forbidden')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 1, name: /Không đủ quyền truy cập/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Quay về Trợ lý Chat/i })).toHaveAttribute(
      'href',
      '/chat',
    );
  });

  it('renders 403 Forbidden screen when DEPARTMENT_MANAGER visits /admin/users', () => {
    mockPathname = '/admin/users';
    render(
      <RoleGuard user={buildUser('DEPARTMENT_MANAGER')}>
        <div>Protected Admin Users Content</div>
      </RoleGuard>,
    );

    expect(screen.queryByText('Protected Admin Users Content')).not.toBeInTheDocument();
    expect(screen.getByText('403 Forbidden')).toBeInTheDocument();
  });
});
