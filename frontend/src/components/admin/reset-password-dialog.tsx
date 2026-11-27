'use client';

import React, { useEffect, useState } from 'react';
import { KeyRound } from 'lucide-react';
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
import { resetAdminUserPassword } from '@/lib/api/admin';
import {
  getUtf8ByteLength,
  MAX_PASSWORD_UTF8_BYTES,
  MIN_PASSWORD_LENGTH,
  type AdminUserResponse,
} from '@/lib/types/admin';
import { formatApiErrorMessage } from '@/lib/types/problem';

interface ResetPasswordDialogProps {
  user: AdminUserResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function ResetPasswordDialog({ user, open, onOpenChange }: ResetPasswordDialogProps) {
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setNewPassword('');
    setConfirmPassword('');
    setError(null);
  }, [open, user?.id]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!user || submitting) return;
    setError(null);

    if (newPassword.length < MIN_PASSWORD_LENGTH) {
      setError(`Mật khẩu mới phải có ít nhất ${MIN_PASSWORD_LENGTH} ký tự.`);
      return;
    }
    if (getUtf8ByteLength(newPassword) > MAX_PASSWORD_UTF8_BYTES) {
      setError(`Mật khẩu mới không được vượt quá ${MAX_PASSWORD_UTF8_BYTES} UTF-8 bytes.`);
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('Mật khẩu xác nhận không khớp.');
      return;
    }

    setSubmitting(true);
    try {
      await resetAdminUserPassword(user.id, { newPassword });
      toast.success(`Đã đặt lại mật khẩu và thu hồi phiên đăng nhập cho ${user.email}.`);
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
          <div className="flex items-center gap-2 text-primary">
            <KeyRound className="h-4 w-4" aria-hidden="true" />
            <DialogTitle>Đặt lại mật khẩu</DialogTitle>
          </div>
          <DialogDescription>
            Tài khoản: <strong className="text-foreground">{user?.email}</strong>. Toàn bộ Access
            Token và Refresh Token hiện tại của người dùng sẽ bị thu hồi ngay lập tức.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <label className="block space-y-1 text-sm font-medium text-slate-700">
            <span>Mật khẩu mới</span>
            <Input
              type="password"
              required
              placeholder="Tối thiểu 8 ký tự, tối đa 72 UTF-8 bytes"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
            <span className="block text-xs font-normal text-muted-foreground">
              Dung lượng hiện tại: {getUtf8ByteLength(newPassword)}/72 UTF-8 bytes
            </span>
          </label>

          <label className="block space-y-1 text-sm font-medium text-slate-700">
            <span>Xác nhận mật khẩu mới</span>
            <Input
              type="password"
              required
              placeholder="Nhập lại mật khẩu mới"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
            />
          </label>

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
              {submitting ? 'Đang cập nhật...' : 'Xác nhận đặt lại'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
