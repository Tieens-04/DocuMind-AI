'use client';

import React, { useEffect, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { createAdminUser, updateAdminUser } from '@/lib/api/admin';
import {
  getUtf8ByteLength,
  MAX_PASSWORD_UTF8_BYTES,
  MIN_PASSWORD_LENGTH,
  type AdminDepartmentResponse,
  type AdminUserResponse,
  type UpdateUserRequest,
} from '@/lib/types/admin';
import { ROLE_LABELS, type Role } from '@/lib/types/auth';
import { formatApiErrorMessage } from '@/lib/types/problem';

interface UserFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  mode: 'create' | 'edit';
  initialUser: AdminUserResponse | null;
  currentUserId: string;
  departments: AdminDepartmentResponse[];
  onSaved: () => void;
}

const ROLES: readonly Role[] = ['STANDARD_EMPLOYEE', 'DEPARTMENT_MANAGER', 'SYSTEM_ADMIN'];

export function UserFormDialog({
  open,
  onOpenChange,
  mode,
  initialUser,
  currentUserId,
  departments,
  onSaved,
}: UserFormDialogProps) {
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('STANDARD_EMPLOYEE');
  const [departmentId, setDepartmentId] = useState('');
  const [isActive, setIsActive] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const isSelf = mode === 'edit' && initialUser?.id === currentUserId;

  useEffect(() => {
    if (!open) return;
    setError(null);
    if (mode === 'edit' && initialUser) {
      setEmail(initialUser.email);
      setFullName(initialUser.fullName);
      setPassword('');
      setRole(initialUser.role);
      setDepartmentId(initialUser.departmentId ?? '');
      setIsActive(initialUser.isActive);
    } else {
      setEmail('');
      setFullName('');
      setPassword('');
      setRole('STANDARD_EMPLOYEE');
      setDepartmentId('');
      setIsActive(true);
    }
  }, [initialUser, mode, open]);

  useEffect(() => {
    if (open && mode === 'create' && !departmentId && departments[0]?.id) {
      setDepartmentId(departments[0].id);
    }
  }, [departments, departmentId, mode, open]);

  const willRevokeSessions =
    mode === 'edit' &&
    initialUser !== null &&
    (email.trim().toLowerCase() !== initialUser.email.toLowerCase() ||
      role !== initialUser.role ||
      departmentId !== (initialUser.departmentId ?? '') ||
      (!isSelf && isActive === false && initialUser.isActive));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setError(null);

    const trimmedEmail = email.trim();
    const trimmedName = fullName.trim();

    if (!trimmedEmail || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
      setError('Vui lòng nhập địa chỉ email hợp lệ.');
      return;
    }
    if (!trimmedName || trimmedName.length > 150) {
      setError('Họ tên không được để trống và tối đa 150 ký tự.');
      return;
    }
    if (!departmentId) {
      setError('Vui lòng chọn phòng ban trực thuộc.');
      return;
    }

    if (mode === 'create') {
      if (password.length < MIN_PASSWORD_LENGTH) {
        setError(`Mật khẩu phải có ít nhất ${MIN_PASSWORD_LENGTH} ký tự.`);
        return;
      }
      if (getUtf8ByteLength(password) > MAX_PASSWORD_UTF8_BYTES) {
        setError(`Mật khẩu không được vượt quá ${MAX_PASSWORD_UTF8_BYTES} UTF-8 bytes.`);
        return;
      }
    }

    setSubmitting(true);
    try {
      if (mode === 'create') {
        await createAdminUser({
          email: trimmedEmail,
          fullName: trimmedName,
          password,
          role,
          departmentId,
          isActive,
        });
        toast.success(`Đã tạo tài khoản ${trimmedEmail}.`);
      } else if (initialUser) {
        const payload: UpdateUserRequest = {};
        if (trimmedEmail.toLowerCase() !== initialUser.email.toLowerCase()) {
          payload.email = trimmedEmail;
        }
        if (trimmedName !== initialUser.fullName) {
          payload.fullName = trimmedName;
        }
        if (!isSelf && role !== initialUser.role) {
          payload.role = role;
        }
        if (departmentId !== (initialUser.departmentId ?? '')) {
          payload.departmentId = departmentId;
        }
        if (!isSelf && isActive !== initialUser.isActive) {
          payload.isActive = isActive;
        }

        if (Object.keys(payload).length === 0) {
          setError('Bạn chưa thay đổi thông tin nào.');
          setSubmitting(false);
          return;
        }

        await updateAdminUser(initialUser.id, payload);
        toast.success(`Đã cập nhật tài khoản ${trimmedEmail}.`);
      }
      onSaved();
      onOpenChange(false);
    } catch (err) {
      setError(formatApiErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!submitting) onOpenChange(next);
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            {mode === 'create' ? 'Tạo tài khoản nhân sự mới' : 'Cập nhật tài khoản nhân sự'}
          </DialogTitle>
          <DialogDescription>
            {mode === 'create'
              ? 'Cấp tài khoản mới gắn với phòng ban và vai trò RBAC.'
              : 'Điều chỉnh thông tin, phòng ban, vai trò hoặc trạng thái hoạt động.'}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <label className="block space-y-1 text-sm font-medium text-slate-700">
            <span>Email công việc</span>
            <Input
              type="email"
              required
              placeholder="nhanvien@documind.ai"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </label>

          <label className="block space-y-1 text-sm font-medium text-slate-700">
            <span>Họ và tên</span>
            <Input
              type="text"
              required
              placeholder="Nguyễn Văn A"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
            />
          </label>

          {mode === 'create' ? (
            <label className="block space-y-1 text-sm font-medium text-slate-700">
              <span>Mật khẩu khởi tạo</span>
              <Input
                type="password"
                required
                placeholder="8 - 72 bytes UTF-8"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <span className="block text-xs font-normal text-muted-foreground">
                Tối thiểu 8 ký tự, tối đa 72 UTF-8 bytes ({getUtf8ByteLength(password)}/72 bytes).
              </span>
            </label>
          ) : null}

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="block space-y-1 text-sm font-medium text-slate-700">
              <span>Vai trò RBAC</span>
              <select
                aria-label="Vai trò RBAC"
                disabled={isSelf}
                className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm disabled:cursor-not-allowed disabled:opacity-60"
                value={role}
                onChange={(e) => setRole(e.target.value as Role)}
              >
                {ROLES.map((item) => (
                  <option key={item} value={item}>
                    {ROLE_LABELS[item]}
                  </option>
                ))}
              </select>
              {isSelf ? (
                <span className="block text-xs font-normal text-amber-700">
                  Không thể tự hạ quyền tài khoản của chính mình.
                </span>
              ) : null}
            </label>

            <label className="block space-y-1 text-sm font-medium text-slate-700">
              <span>Phòng ban</span>
              <select
                aria-label="Phòng ban"
                required
                className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={departmentId}
                onChange={(e) => setDepartmentId(e.target.value)}
              >
                <option value="" disabled>
                  -- Chọn phòng ban --
                </option>
                {departments.map((dept) => (
                  <option key={dept.id} value={dept.id}>
                    {dept.code} — {dept.name}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-slate-300"
              checked={isActive}
              disabled={isSelf}
              onChange={(e) => setIsActive(e.target.checked)}
            />
            <span>Kích hoạt tài khoản ngay</span>
          </label>

          {willRevokeSessions ? (
            <div className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-700" aria-hidden="true" />
              <span>
                Thay đổi email, vai trò, phòng ban hoặc khóa tài khoản sẽ thu hồi toàn bộ phiên
                đăng nhập (JWT) của người dùng ngay lập tức.
              </span>
            </div>
          ) : null}

          {error ? (
            <div
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-xs text-destructive"
            >
              {error}
            </div>
          ) : null}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={submitting}
              onClick={() => onOpenChange(false)}
            >
              Hủy
            </Button>
            <Button type="submit" disabled={submitting}>
              {submitting ? 'Đang lưu...' : mode === 'create' ? 'Tạo tài khoản' : 'Lưu thay đổi'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
