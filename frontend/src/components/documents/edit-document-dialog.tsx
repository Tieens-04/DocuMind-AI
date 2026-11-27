'use client';

import React, { useEffect, useId, useState } from 'react';
import { Pencil } from 'lucide-react';
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
import { formatDocumentErrorMessage, updateDocumentMetadata } from '@/lib/api/documents';
import type { AdminDepartmentOption } from '@/lib/types/chat';
import {
  ACCESS_LEVEL_LABELS,
  type AccessLevel,
  type DocumentResponse,
  type UpdateDocumentMetadataRequest,
} from '@/lib/types/documents';

interface EditDocumentDialogProps {
  document: DocumentResponse | null;
  isAdmin: boolean;
  departments: AdminDepartmentOption[];
  onClose: () => void;
  onUpdated: (updated: DocumentResponse) => void;
}

export function EditDocumentDialog({
  document,
  isAdmin,
  departments,
  onClose,
  onUpdated,
}: EditDocumentDialogProps) {
  const nameInputId = useId();
  const accessSelectId = useId();
  const departmentSelectId = useId();

  const [originalName, setOriginalName] = useState('');
  const [accessLevel, setAccessLevel] = useState<AccessLevel>('DEPARTMENT');
  const [departmentId, setDepartmentId] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (document) {
      setOriginalName(document.originalName);
      setAccessLevel(document.accessLevel);
      setDepartmentId(document.departmentId);
      setIsSubmitting(false);
      setErrorMessage(null);
    }
  }, [document]);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (isSubmitting || !document) {
      return;
    }

    const trimmedName = originalName.trim();
    if (!trimmedName) {
      setErrorMessage('Tên tài liệu không được để trống.');
      return;
    }
    if (trimmedName.length > 255) {
      setErrorMessage('Tên tài liệu không được vượt quá 255 ký tự.');
      return;
    }
    if (
      trimmedName.includes('/') ||
      trimmedName.includes('\\') ||
      trimmedName.includes('..') ||
      /[\u0000-\u001F\u007F]/.test(trimmedName)
    ) {
      setErrorMessage('Tên tài liệu chứa ký tự đường dẫn hoặc điều khiển không hợp lệ.');
      return;
    }
    const expectedExt = `.${document.fileExtension.toLowerCase()}`;
    if (
      !trimmedName.toLowerCase().endsWith(expectedExt) ||
      trimmedName.length <= expectedExt.length
    ) {
      setErrorMessage(`Tên tài liệu phải giữ nguyên phần mở rộng ${expectedExt}.`);
      return;
    }

    const payload: UpdateDocumentMetadataRequest = {};
    if (trimmedName !== document.originalName) {
      payload.originalName = trimmedName;
    }
    if (accessLevel !== document.accessLevel) {
      payload.accessLevel = accessLevel;
    }
    if (isAdmin && departmentId && departmentId !== document.departmentId) {
      payload.departmentId = departmentId;
    }

    if (
      payload.originalName === undefined &&
      payload.accessLevel === undefined &&
      payload.departmentId === undefined
    ) {
      onClose();
      return;
    }

    setIsSubmitting(true);
    setErrorMessage(null);
    try {
      const updated = await updateDocumentMetadata(document.id, payload);
      onUpdated(updated);
      onClose();
    } catch (error) {
      setErrorMessage(formatDocumentErrorMessage(error));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Dialog open={document !== null} onOpenChange={(open) => !open && !isSubmitting && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Pencil className="h-4 w-4 text-primary" aria-hidden="true" />
            <span>Chỉnh sửa thông tin tài liệu</span>
          </DialogTitle>
          <DialogDescription>
            Cập nhật tên hiển thị hoặc phân quyền truy cập và đồng bộ tức thì vào bộ lọc vector.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor={nameInputId} className="text-sm font-medium text-slate-700">
              Tên tài liệu
            </label>
            <Input
              id={nameInputId}
              value={originalName}
              maxLength={255}
              disabled={isSubmitting}
              onChange={(event) => setOriginalName(event.target.value)}
            />
            {document && (
              <p className="text-xs text-muted-foreground">
                Giữ nguyên phần mở rộng .{document.fileExtension.toLowerCase()}
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <label htmlFor={accessSelectId} className="text-sm font-medium text-slate-700">
              Mức quyền truy cập
            </label>
            <select
              id={accessSelectId}
              value={accessLevel}
              disabled={isSubmitting}
              onChange={(event) => setAccessLevel(event.target.value as AccessLevel)}
              className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <option value="DEPARTMENT">{ACCESS_LEVEL_LABELS.DEPARTMENT}</option>
              <option value="PUBLIC">{ACCESS_LEVEL_LABELS.PUBLIC}</option>
              <option value="CONFIDENTIAL">{ACCESS_LEVEL_LABELS.CONFIDENTIAL}</option>
            </select>
          </div>

          {isAdmin && (
            <div className="space-y-1.5">
              <label htmlFor={departmentSelectId} className="text-sm font-medium text-slate-700">
                Phòng ban sở hữu
              </label>
              <select
                id={departmentSelectId}
                value={departmentId}
                disabled={isSubmitting || departments.length === 0}
                onChange={(event) => setDepartmentId(event.target.value)}
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {document && !departments.some((dept) => dept.id === document.departmentId) && (
                  <option value={document.departmentId}>{document.departmentId}</option>
                )}
                {departments.map((dept) => (
                  <option key={dept.id} value={dept.id}>
                    {dept.code} - {dept.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          {errorMessage && (
            <p role="alert" className="text-xs text-destructive">
              {errorMessage}
            </p>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" disabled={isSubmitting} onClick={onClose}>
              Hủy
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Đang lưu...' : 'Lưu thay đổi'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
