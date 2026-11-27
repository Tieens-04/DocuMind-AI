'use client';

import React, { useEffect, useState } from 'react';
import { Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { deleteDocument, formatDocumentErrorMessage } from '@/lib/api/documents';
import type { DocumentResponse } from '@/lib/types/documents';

interface DeleteDocumentDialogProps {
  document: DocumentResponse | null;
  onClose: () => void;
  onDeleted: (documentId: string) => void;
}

export function DeleteDocumentDialog({
  document,
  onClose,
  onDeleted,
}: DeleteDocumentDialogProps) {
  const [isDeleting, setIsDeleting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (document) {
      setIsDeleting(false);
      setErrorMessage(null);
    }
  }, [document]);

  const handleConfirmDelete = async () => {
    if (isDeleting || !document) {
      return;
    }
    setIsDeleting(true);
    setErrorMessage(null);
    try {
      await deleteDocument(document.id);
      onDeleted(document.id);
      onClose();
    } catch (error) {
      setErrorMessage(formatDocumentErrorMessage(error));
    } finally {
      setIsDeleting(false);
    }
  };

  return (
    <Dialog open={document !== null} onOpenChange={(open) => !open && !isDeleting && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-destructive">
            <Trash2 className="h-5 w-5" aria-hidden="true" />
            <span>Xác nhận xóa tài liệu</span>
          </DialogTitle>
          <DialogDescription>
            {document
              ? `Bạn có chắc muốn xóa vĩnh viễn "${document.originalName}" (v${document.currentVersion})? Toàn bộ dữ liệu vector và tệp gốc trên lưu trữ sẽ bị xóa và không thể khôi phục.`
              : ''}
          </DialogDescription>
        </DialogHeader>

        {errorMessage && (
          <p role="alert" className="text-xs text-destructive">
            {errorMessage}
          </p>
        )}

        <DialogFooter>
          <Button type="button" variant="outline" disabled={isDeleting} onClick={onClose}>
            Hủy
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={isDeleting}
            onClick={handleConfirmDelete}
          >
            {isDeleting ? 'Đang xóa...' : 'Xóa vĩnh viễn'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
