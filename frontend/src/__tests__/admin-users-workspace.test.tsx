import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AdminUsersPage from '@/app/(workspace)/admin/users/page';
import * as adminApi from '@/lib/api/admin';
import { ApiError } from '@/lib/types/problem';

vi.mock('@/providers/auth-provider', () => ({
  useAuth: () => ({
    user: {
      id: 'admin-self',
      email: 'admin@documind.ai',
      fullName: 'Root Admin',
      role: 'SYSTEM_ADMIN',
      departmentId: 'dept-it',
      departmentCode: 'IT',
      departmentName: 'Công nghệ thông tin',
      isActive: true,
      createdAt: '2026-11-01T00:00:00Z',
    },
    status: 'authenticated',
    bootstrapError: null,
    login: vi.fn(),
    logout: vi.fn(),
    retryBootstrap: vi.fn(),
  }),
}));

vi.mock('@/lib/api/admin', () => ({
  listAdminDepartmentsPage: vi.fn(),
  listAdminUsers: vi.fn(),
  createAdminUser: vi.fn(),
  updateAdminUser: vi.fn(),
  resetAdminUserPassword: vi.fn(),
  createAdminDepartment: vi.fn(),
  updateAdminDepartment: vi.fn(),
  deleteAdminDepartment: vi.fn(),
}));

const mockedApi = vi.mocked(adminApi);

const DEPARTMENTS_FIXTURE = [
  {
    id: 'dept-it',
    code: 'IT',
    name: 'Công nghệ thông tin',
    description: 'Kỹ thuật nền tảng',
    cacheVersion: 3,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
  {
    id: 'dept-hr',
    code: 'HR',
    name: 'Nhân sự',
    description: 'Phòng nhân sự',
    cacheVersion: 1,
    createdAt: '2026-11-02T00:00:00Z',
    updatedAt: '2026-11-02T00:00:00Z',
  },
];

const USERS_FIXTURE = [
  {
    id: 'admin-self',
    email: 'admin@documind.ai',
    fullName: 'Root Admin',
    role: 'SYSTEM_ADMIN' as const,
    departmentId: 'dept-it',
    departmentCode: 'IT',
    departmentName: 'Công nghệ thông tin',
    isActive: true,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
  {
    id: 'user-2',
    email: 'staff@documind.ai',
    fullName: 'Nhân viên B',
    role: 'STANDARD_EMPLOYEE' as const,
    departmentId: 'dept-hr',
    departmentCode: 'HR',
    departmentName: 'Nhân sự',
    isActive: true,
    createdAt: '2026-11-05T00:00:00Z',
    updatedAt: '2026-11-05T00:00:00Z',
  },
];

describe('AdminUsersPage (Users & Departments 2-Tab Governance)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApi.listAdminDepartmentsPage.mockResolvedValue({
      items: DEPARTMENTS_FIXTURE,
      page: 0,
      size: 100,
      totalElements: 2,
      totalPages: 1,
    });
    mockedApi.listAdminUsers.mockResolvedValue({
      items: USERS_FIXTURE,
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });
  });

  it('enforces self-protection, UTF-8 byte password limit, JWT revocation warning, and 409 error mapping', async () => {
    const user = userEvent.setup();
    render(<AdminUsersPage />);

    await screen.findByText('staff@documind.ai');

    expect(
      screen.getByRole('button', { name: 'Khóa admin@documind.ai' }),
    ).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Chỉnh sửa admin@documind.ai' }));
    let dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText('Vai trò RBAC')).toBeDisabled();
    await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));

    await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
    dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByPlaceholderText('nhanvien@documind.ai'), 'new@documind.ai');
    await user.type(within(dialog).getByPlaceholderText('Nguyễn Văn A'), 'Nguyễn Mới');
    const longUtf8Password = 'á'.repeat(40); // 40 chars but 80 UTF-8 bytes
    await user.type(within(dialog).getByPlaceholderText('8 - 72 bytes UTF-8'), longUtf8Password);
    await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));

    expect(
      within(dialog).getByText('Mật khẩu không được vượt quá 72 UTF-8 bytes.'),
    ).toBeInTheDocument();

    const pwInput = within(dialog).getByPlaceholderText('8 - 72 bytes UTF-8');
    await user.clear(pwInput);
    await user.type(pwInput, 'ValidPass123!');
    mockedApi.createAdminUser.mockRejectedValueOnce(
      new ApiError({ status: 409, errorCode: 'DUPLICATE_EMAIL', detail: 'Duplicate email' }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));
    expect(
      await within(dialog).findByText('Email này đã được sử dụng cho một tài khoản khác.'),
    ).toBeInTheDocument();

    mockedApi.createAdminUser.mockResolvedValueOnce({
      ...USERS_FIXTURE[1],
      id: 'user-3',
      email: 'new@documind.ai',
    });
    await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));
    await waitFor(() => expect(mockedApi.createAdminUser).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole('button', { name: 'Chỉnh sửa staff@documind.ai' }));
    dialog = await screen.findByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText('Vai trò RBAC'), 'DEPARTMENT_MANAGER');
    expect(
      within(dialog).getByText(/thu hồi toàn bộ phiên đăng nhập \(JWT\)/i),
    ).toBeInTheDocument();
    mockedApi.updateAdminUser.mockResolvedValueOnce({
      ...USERS_FIXTURE[1],
      role: 'DEPARTMENT_MANAGER',
    });
    await user.click(within(dialog).getByRole('button', { name: 'Lưu thay đổi' }));
    await waitFor(() =>
      expect(mockedApi.updateAdminUser).toHaveBeenCalledWith('user-2', {
        role: 'DEPARTMENT_MANAGER',
      }),
    );

    await user.click(screen.getByRole('button', { name: 'Khóa staff@documind.ai' }));
    dialog = await screen.findByRole('dialog');
    mockedApi.updateAdminUser.mockResolvedValueOnce({
      ...USERS_FIXTURE[1],
      isActive: false,
    });
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận khóa' }));
    await waitFor(() =>
      expect(mockedApi.updateAdminUser).toHaveBeenCalledWith('user-2', { isActive: false }),
    );

    await user.click(screen.getByRole('button', { name: 'Đặt lại mật khẩu staff@documind.ai' }));
    dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Mật khẩu mới/i), 'ResetPass123!');
    await user.type(within(dialog).getByLabelText(/^Xác nhận mật khẩu mới/i), 'ResetPass123!');
    mockedApi.resetAdminUserPassword.mockResolvedValueOnce(undefined);
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đặt lại' }));
    await waitFor(() =>
      expect(mockedApi.resetAdminUserPassword).toHaveBeenCalledWith('user-2', {
        newPassword: 'ResetPass123!',
      }),
    );
  });

  it('switches to Departments tab, clears description via empty string on PATCH, and surfaces 409 DEPARTMENT_IN_USE', async () => {
    const user = userEvent.setup();
    render(<AdminUsersPage />);

    await screen.findByText('staff@documind.ai');
    await user.click(screen.getByRole('tab', { name: /Phòng ban/i }));

    expect(await screen.findByText('Công nghệ thông tin')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Chỉnh sửa phòng ban HR' }));
    let dialog = await screen.findByRole('dialog');
    const descArea = within(dialog).getByLabelText('Mô tả phòng ban');
    await user.clear(descArea);
    mockedApi.updateAdminDepartment.mockResolvedValueOnce({
      ...DEPARTMENTS_FIXTURE[1],
      description: null,
    });
    await user.click(within(dialog).getByRole('button', { name: 'Lưu thay đổi' }));
    await waitFor(() =>
      expect(mockedApi.updateAdminDepartment).toHaveBeenCalledWith('dept-hr', {
        description: '',
      }),
    );

    await user.click(screen.getByRole('button', { name: 'Xóa phòng ban IT' }));
    dialog = await screen.findByRole('dialog');
    mockedApi.deleteAdminDepartment.mockRejectedValueOnce(
      new ApiError({
        status: 409,
        errorCode: 'DEPARTMENT_IN_USE',
        detail: 'Cannot delete department in use',
      }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận xóa' }));
    expect(
      await within(dialog).findByText(
        'Không thể xóa phòng ban đang có nhân sự hoặc tài liệu trực thuộc.',
      ),
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));

    await user.click(screen.getByRole('button', { name: 'Tạo phòng ban' }));
    dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByPlaceholderText('VD: HR, FIN_01, TECH-AI'), 'A');
    await user.type(within(dialog).getByPlaceholderText('Nhân sự'), 'Phòng mới');
    await user.click(within(dialog).getByRole('button', { name: 'Tạo phòng ban' }));
    expect(
      within(dialog).getByText(
        'Mã phòng ban dài 2–32 ký tự và chỉ gồm chữ in hoa, số, dấu "_" hoặc "-".',
      ),
    ).toBeInTheDocument();

    const codeInput = within(dialog).getByPlaceholderText('VD: HR, FIN_01, TECH-AI');
    await user.clear(codeInput);
    await user.type(codeInput, 'HR');
    mockedApi.createAdminDepartment.mockRejectedValueOnce(
      new ApiError({
        status: 409,
        errorCode: 'DUPLICATE_DEPARTMENT_CODE',
        detail: 'Duplicate department code',
      }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Tạo phòng ban' }));
    expect(
      await within(dialog).findByText('Mã phòng ban này đã tồn tại trong hệ thống.'),
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));

    await user.click(screen.getByRole('button', { name: 'Xóa phòng ban HR' }));
    dialog = await screen.findByRole('dialog');
    mockedApi.deleteAdminDepartment.mockResolvedValueOnce(undefined);
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận xóa' }));
    await waitFor(() => expect(mockedApi.deleteAdminDepartment).toHaveBeenCalledWith('dept-hr'));
  });

  it('keeps UserFormDialog input when departments resolve late, avoids false JWT warning on inactive user, and handles department load retry + 409 LAST_ACTIVE_ADMIN', async () => {
    const user = userEvent.setup();
    let resolveDepts!: (value: {
      items: typeof DEPARTMENTS_FIXTURE;
      page: number;
      size: number;
      totalElements: number;
      totalPages: number;
    }) => void;
    mockedApi.listAdminDepartmentsPage
      .mockRejectedValueOnce(new ApiError({ status: 503, detail: 'Dept fetch failed' }))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveDepts = resolve;
          }),
      );
    mockedApi.listAdminUsers.mockResolvedValue({
      items: [
        ...USERS_FIXTURE,
        {
          ...USERS_FIXTURE[1],
          id: 'user-locked',
          email: 'locked@documind.ai',
          fullName: 'Locked User',
          isActive: false,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 3,
      totalPages: 1,
    });

    render(<AdminUsersPage />);
    expect(
      await screen.findByText('Không thể tải danh sách phòng ban: Dept fetch failed'),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
    let dialog = await screen.findByRole('dialog');
    const emailInput = within(dialog).getByPlaceholderText('nhanvien@documind.ai');
    await user.type(emailInput, 'keep@documind.ai');

    resolveDepts({
      items: DEPARTMENTS_FIXTURE,
      page: 0,
      size: 100,
      totalElements: 2,
      totalPages: 1,
    });
    await waitFor(() =>
      expect((within(dialog).getByLabelText('Phòng ban') as HTMLSelectElement).value).toBe(
        'dept-it',
      ),
    );
    expect(emailInput).toHaveValue('keep@documind.ai');
    await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));

    await user.click(screen.getByRole('button', { name: 'Chỉnh sửa locked@documind.ai' }));
    dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).queryByText(/thu hồi toàn bộ phiên đăng nhập \(JWT\)/i),
    ).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));

    await user.click(screen.getByRole('button', { name: 'Khóa staff@documind.ai' }));
    dialog = await screen.findByRole('dialog');
    mockedApi.updateAdminUser.mockRejectedValueOnce(
      new ApiError({
        status: 409,
        errorCode: 'LAST_ACTIVE_ADMIN',
        detail: 'Last active admin',
      }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận khóa' }));
    expect(
      await within(dialog).findByText(
        'Không thể khóa hoặc hạ quyền tài khoản SYSTEM_ADMIN đang hoạt động cuối cùng.',
      ),
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));

    await user.click(screen.getByRole('button', { name: 'Đặt lại mật khẩu staff@documind.ai' }));
    dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Mật khẩu mới/i), 'á'.repeat(40));
    await user.type(within(dialog).getByLabelText(/^Xác nhận mật khẩu mới/i), 'á'.repeat(40));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đặt lại' }));
    expect(
      within(dialog).getByText('Mật khẩu mới không được vượt quá 72 UTF-8 bytes.'),
    ).toBeInTheDocument();

    const newPw = within(dialog).getByLabelText(/^Mật khẩu mới/i);
    const confirmPw = within(dialog).getByLabelText(/^Xác nhận mật khẩu mới/i);
    await user.clear(newPw);
    await user.clear(confirmPw);
    await user.type(newPw, 'ValidPass123!');
    await user.type(confirmPw, 'DifferentPass123!');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đặt lại' }));
    expect(within(dialog).getByText('Mật khẩu xác nhận không khớp.')).toBeInTheDocument();
  });
});
