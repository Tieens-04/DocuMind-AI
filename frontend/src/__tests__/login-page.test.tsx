import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LoginPage from '@/app/login/page';
import { ApiError } from '@/lib/types/problem';

const mockReplace = vi.fn();
const mockLogin = vi.fn();
let mockSearchParams = new URLSearchParams();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: mockReplace, push: vi.fn() }),
  useSearchParams: () => mockSearchParams,
}));

vi.mock('@/providers/auth-provider', () => ({
  useAuth: () => ({
    user: null,
    status: 'unauthenticated',
    bootstrapError: null,
    login: mockLogin,
    logout: vi.fn(),
    retryBootstrap: vi.fn(),
  }),
}));

describe('LoginPage', () => {
  beforeEach(() => {
    mockReplace.mockReset();
    mockLogin.mockReset();
    mockSearchParams = new URLSearchParams();
    vi.stubEnv('NEXT_PUBLIC_DEMO_ACCOUNTS', 'true');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('renders semantic h1 heading, accessible sign-in attributes, and dev quick-fill accounts when enabled', () => {
    render(<LoginPage />);

    expect(screen.getByRole('heading', { level: 1, name: 'DocuMind AI' })).toBeInTheDocument();

    const emailInput = screen.getByLabelText(/Email doanh nghiệp/i);
    const passwordInput = screen.getByLabelText(/^Mật khẩu$/i);

    expect(emailInput).toHaveAttribute('autocomplete', 'username');
    expect(emailInput).toHaveAttribute('spellcheck', 'false');
    expect(emailInput).toHaveAttribute('aria-invalid', 'false');
    expect(passwordInput).toHaveAttribute('autocomplete', 'current-password');
    expect(passwordInput).toHaveAttribute('aria-invalid', 'false');

    expect(screen.getByText('admin@documind.ai')).toBeInTheDocument();
    expect(screen.getByText('manager.hr@documind.ai')).toBeInTheDocument();
    expect(screen.getByText('employee.sales@documind.ai')).toBeInTheDocument();
  });

  it('hides dev quick-fill accounts when NEXT_PUBLIC_DEMO_ACCOUNTS is not true', () => {
    vi.stubEnv('NEXT_PUBLIC_DEMO_ACCOUNTS', 'false');
    render(<LoginPage />);

    expect(screen.queryByText('admin@documind.ai')).not.toBeInTheDocument();
    expect(screen.queryByText('manager.hr@documind.ai')).not.toBeInTheDocument();
    expect(screen.queryByText('employee.sales@documind.ai')).not.toBeInTheDocument();
  });

  it.each([
    {
      buttonPattern: /System Admin/i,
      email: 'admin@documind.ai',
      role: 'SYSTEM_ADMIN' as const,
      fullName: 'System Administrator',
      departmentCode: 'IT',
      nextQuery: 'next=/admin/analytics',
      expectedRedirect: '/admin/analytics',
    },
    {
      buttonPattern: /HR Manager/i,
      email: 'manager.hr@documind.ai',
      role: 'DEPARTMENT_MANAGER' as const,
      fullName: 'HR Department Manager',
      departmentCode: 'HR',
      nextQuery: 'next=/documents',
      expectedRedirect: '/documents',
    },
    {
      buttonPattern: /Sales Employee/i,
      email: 'employee.sales@documind.ai',
      role: 'STANDARD_EMPLOYEE' as const,
      fullName: 'Sales Standard Employee',
      departmentCode: 'SALES',
      nextQuery: 'next=%2F%09%2Fevil.example',
      expectedRedirect: '/chat',
    },
  ])(
    'fills demo account ($email), submits login, and redirects safely',
    async ({
      buttonPattern,
      email,
      role,
      fullName,
      departmentCode,
      nextQuery,
      expectedRedirect,
    }) => {
      const user = userEvent.setup();
      mockSearchParams = new URLSearchParams(nextQuery);
      mockLogin.mockResolvedValueOnce({
        id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        email,
        fullName,
        role,
        departmentId: '22222222-2222-2222-2222-222222222222',
        departmentCode,
        departmentName: departmentCode,
        isActive: true,
        createdAt: '2026-11-26T00:00:00Z',
      });

      render(<LoginPage />);

      await user.click(screen.getByRole('button', { name: buttonPattern }));
      await user.click(screen.getByRole('button', { name: /Đăng nhập/i }));

      await waitFor(() => {
        expect(mockLogin).toHaveBeenCalledWith({
          email,
          password: 'DocuMind@2026!',
        });
        expect(mockReplace).toHaveBeenCalledWith(expectedRedirect);
      });
    },
  );

  it('displays ProblemDetail error alerts for INVALID_CREDENTIALS and ACCOUNT_DISABLED', async () => {
    const user = userEvent.setup();
    mockLogin
      .mockRejectedValueOnce(
        new ApiError({
          status: 401,
          errorCode: 'INVALID_CREDENTIALS',
          detail: 'Invalid email or password',
        }),
      )
      .mockRejectedValueOnce(
        new ApiError({
          status: 403,
          errorCode: 'ACCOUNT_DISABLED',
          detail: 'User account is disabled',
        }),
      );

    render(<LoginPage />);

    await user.type(screen.getByLabelText(/Email doanh nghiệp/i), 'employee.sales@documind.ai');
    await user.type(screen.getByLabelText(/^Mật khẩu$/i), 'wrong');
    await user.click(screen.getByRole('button', { name: /Đăng nhập/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Email hoặc mật khẩu không chính xác.',
    );

    await user.click(screen.getByRole('button', { name: /Đăng nhập/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Tài khoản của bạn đã bị vô hiệu hóa',
    );
  });
});
