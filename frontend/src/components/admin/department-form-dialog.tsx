'use client';

import React, { useEffect, useState } from 'react';
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
import { createAdminDepartment, updateAdminDepartment } from '@/lib/api/admin';
import {
  DEPARTMENT_CODE_REGEX,
  type AdminDepartmentResponse,
  type UpdateDepartmentRequest,
} from '@/lib/types/admin';
import { formatApiErrorMessage } from '@/lib/types/problem';

interface DepartmentFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  mode: 'create' | 'edit';
  initialDepartment: AdminDepartmentResponse | null;
  onSaved: () => void;
}

export function DepartmentFormDialog({
  open,
  onOpenChange,
  mode,
  initialDepartment,
  onSaved,
}: DepartmentFormDialogProps) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setError(null);
    if (mode === 'edit' && initialDepartment) {
      setCode(initialDepartment.code);
      setName(initialDepartment.name);
      setDescription(initialDepartment.description ?? '');
    } else {
      setCode('');
      setName('');
      setDescription('');
    }
  }, [initialDepartment, mode, open]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setError(null);

    const normalizedCode = code.trim().toUpperCase();
    const trimmedName = name.trim();
    const trimmedDescription = description.trim();

    if (!DEPARTMENT_CODE_REGEX.test(normalizedCode)) {
      setError('Mã phòng ban dài 2–32 ký tự và chỉ gồm chữ in hoa, số, dấu "_" hoặc "-".');
      return;
    }
    if (!trimmedName || trimmedName.length > 120) {
      setError('Tên phòng ban không được để trống và tối đa 120 ký tự.');
      return;
    }
    if (trimmedDescription.length > 2000) {
      setError('Mô tả phòng ban tối đa 2000 ký tự.');
      return;
    }

    setSubmitting(true);
    try {
      if (mode === 'create') {
        await createAdminDepartment({
          code: normalizedCode,
          name: trimmedName,
          description: trimmedDescription || null,
        });
        toast.success(`Đã tạo phòng ban ${normalizedCode}.`);
      } else if (initialDepartment) {
        const payload: UpdateDepartmentRequest = {};
        if (normalizedCode !== initialDepartment.code) {
          payload.code = normalizedCode;
        }
        if (trimmedName !== initialDepartment.name) {
          payload.name = trimmedName;
        }
        const prevDesc = initialDepartment.description ?? '';
        if (trimmedDescription !== prevDesc) {
          // Backend treats empty string "" as explicit clear-to-null, while null/omitted keeps existing value.
          payload.description = trimmedDescription;
        }

        if (Object.keys(payload).length === 0) {
          setError('Bạn chưa thay đổi thông tin nào.');
          setSubmitting(false);
          return;
        }

        await updateAdminDepartment(initialDepartment.id, payload);
        toast.success(`Đã cập nhật phòng ban ${normalizedCode}.`);
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
            {mode === 'create' ? 'Tạo phòng ban mới' : 'Chỉnh sửa phòng ban'}
          </DialogTitle>
          <DialogDescription>
            Mã phòng ban là định danh duy nhất dùng để phân vùng quyền tài liệu và bộ nhớ Cache.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <label className="block space-y-1 text-sm font-medium text-slate-700">
            <span>Mã phòng ban</span>
            <Input
              type="text"
              required
              placeholder="VD: HR, FIN_01, TECH-AI"
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
            />
          </label>

          <label className="block space-y-1 text-sm font-medium text-slate-700">
            <span>Tên phòng ban</span>
            <Input
              type="text"
              required
              placeholder="Nhân sự"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </label>

          <label className="block space-y-1 text-sm font-medium text-slate-700">
            <span>Mô tả</span>
            <textarea
              rows={3}
              aria-label="Mô tả phòng ban"
              placeholder="Mô tả chức năng phòng ban (có thể để trống)"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
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
              {submitting ? 'Đang lưu...' : mode === 'create' ? 'Tạo phòng ban' : 'Lưu thay đổi'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
