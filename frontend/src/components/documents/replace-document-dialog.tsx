'use client';

import React, { useEffect, useId, useRef, useState } from 'react';
import { Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { formatDocumentErrorMessage, replaceDocumentWithProgress } from '@/lib/api/documents';
import {
  ACCESS_LEVEL_LABELS,
  ALLOWED_DOCUMENT_EXTENSIONS,
  formatFileSize,
  validateDocumentFile,
  type AccessLevel,
  type DocumentResponse,
} from '@/lib/types/documents';

interface ReplaceDocumentDialogProps {
  document: DocumentResponse | null;
  onClose: () => void;
  onReplaced: (updated: DocumentResponse) => void;
}

export function ReplaceDocumentDialog({
  document,
  onClose,
  onReplaced,
}: ReplaceDocumentDialogProps) {
  const fileInputId = useId();
  const accessSelectId = useId();
  const abortRef = useRef<AbortController | null>(null);

  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [accessLevel, setAccessLevel] = useState<AccessLevel>('DEPARTMENT');
  const [progress, setProgress] = useState<number | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    if (document) {
      setSelectedFile(null);
      setAccessLevel(document.accessLevel);
      setProgress(null);
      setIsSubmitting(false);
      setErrorMessage(null);
    }
    return () => {
      abortRef.current?.abort();
      abortRef.current = null;
    };
  }, [document]);

  const handleClose = () => {
    abortRef.current?.abort();
    abortRef.current = null;
    onClose();
  };

  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null;
    if (!file) {
      setSelectedFile(null);
      return;
    }
    const validationError = validateDocumentFile(file);
    if (validationError) {
      event.target.value = '';
      setSelectedFile(null);
      setErrorMessage(validationError);
      return;
    }
    setErrorMessage(null);
    setSelectedFile(file);
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (isSubmitting || abortRef.current) {
      return;
    }
    if (!document || !selectedFile) {
      setErrorMessage('Vui lòng chọn tệp phiên bản mới trước khi tải lên.');
      return;
    }

    const controller = new AbortController();
    abortRef.current = controller;
    setIsSubmitting(true);
    setProgress(null);
    setErrorMessage(null);

    try {
      const updated = await replaceDocumentWithProgress({
        documentId: document.id,
        file: selectedFile,
        accessLevel,
        signal: controller.signal,
        onProgress: ({ percent }) => {
          if (abortRef.current === controller) {
            setProgress(percent);
          }
        },
      });
      if (abortRef.current !== controller) {
        return;
      }
      abortRef.current = null;
      setIsSubmitting(false);
      onReplaced(updated);
      onClose();
    } catch (error) {
      if (abortRef.current !== controller) {
        return;
      }
      if (controller.signal.aborted) {
        setErrorMessage('Đã hủy cập nhật phiên bản.');
      } else {
        setErrorMessage(formatDocumentErrorMessage(error));
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
        setIsSubmitting(false);
      }
    }
  };

  return (
    <Dialog open={document !== null} onOpenChange={(open) => !open && handleClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Upload className="h-5 w-5 text-primary" aria-hidden="true" />
            <span>Cập nhật phiên bản mới</span>
          </DialogTitle>
          <DialogDescription>
            {document
              ? `Tải tệp mới cho "${document.originalName}" (v${document.currentVersion} → v${document.currentVersion + 1}). Hệ thống sẽ tự động lập chỉ mục lại.`
              : ''}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor={fileInputId} className="text-sm font-medium text-slate-700">
              Chọn tệp mới (.pdf, .docx, .txt, .md ≤ 50 MB)
            </label>
            <input
              id={fileInputId}
              type="file"
              accept={ALLOWED_DOCUMENT_EXTENSIONS.join(',')}
              disabled={isSubmitting}
              onChange={handleFileChange}
              className="block w-full rounded-md border border-input bg-background px-3 py-2 text-sm file:mr-3 file:rounded file:border-0 file:bg-primary/10 file:px-2.5 file:py-1 file:text-xs file:font-medium file:text-primary"
            />
            {selectedFile && (
              <p className="text-xs text-muted-foreground">
                Đã chọn: {selectedFile.name} ({formatFileSize(selectedFile.size)})
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

          {isSubmitting && (
            <div className="space-y-1.5">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>Đang tải phiên bản mới...</span>
                <span>{progress !== null ? `${progress}%` : 'Đang gửi...'}</span>
              </div>
              <progress
                max={100}
                value={progress ?? undefined}
                aria-label="Tiến trình cập nhật phiên bản mới"
                className="h-2 w-full accent-primary"
              />
            </div>
          )}

          {errorMessage && (
            <p role="alert" className="text-xs text-destructive">
              {errorMessage}
            </p>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={handleClose}>
              {isSubmitting ? 'Hủy tải lên' : 'Đóng'}
            </Button>
            <Button type="submit" disabled={isSubmitting || !selectedFile}>
              {isSubmitting ? 'Đang tải lên...' : 'Cập nhật phiên bản'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
