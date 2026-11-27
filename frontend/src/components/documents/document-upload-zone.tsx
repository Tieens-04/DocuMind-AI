'use client';

import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  FileUp,
  RotateCcw,
  Trash2,
  UploadCloud,
  X,
} from 'lucide-react';
import { AccessLevelBadge } from '@/components/documents/document-status-badge';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { formatDocumentErrorMessage, uploadDocumentWithProgress } from '@/lib/api/documents';
import { hasStoredSession } from '@/lib/auth/token-manager';
import type { AdminDepartmentOption } from '@/lib/types/chat';
import {
  ACCESS_LEVEL_LABELS,
  ALLOWED_DOCUMENT_EXTENSIONS,
  formatFileSize,
  validateDocumentFile,
  type AccessLevel,
  type DocumentResponse,
  type UploadQueueItem,
} from '@/lib/types/documents';
import { ApiError } from '@/lib/types/problem';
import { cn } from '@/lib/utils';

interface DocumentUploadZoneProps {
  isAdmin: boolean;
  defaultDepartmentId: string | null;
  departments: AdminDepartmentOption[];
  onDocumentUploaded: (doc: DocumentResponse) => void;
}

function createQueueId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `upload-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function DocumentUploadZone({
  isAdmin,
  defaultDepartmentId,
  departments,
  onDocumentUploaded,
}: DocumentUploadZoneProps) {
  const accessLevelSelectId = useId();
  const departmentSelectId = useId();
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const dragDepthRef = useRef(0);
  const mountedRef = useRef(true);
  const isProcessingRef = useRef(false);
  const activeAbortRef = useRef<Map<string, AbortController>>(new Map());
  const queueRef = useRef<UploadQueueItem[]>([]);
  const rateLimitRef = useRef(0);
  const onDocumentUploadedRef = useRef(onDocumentUploaded);
  const initializedDefaultDeptRef = useRef(false);

  const [collapsed, setCollapsed] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const [selectedAccessLevel, setSelectedAccessLevel] = useState<AccessLevel>('DEPARTMENT');
  const [selectedDepartmentId, setSelectedDepartmentId] = useState<string>(
    defaultDepartmentId ?? '',
  );
  const [selectionErrors, setSelectionErrors] = useState<string[]>([]);
  const [queue, setQueue] = useState<UploadQueueItem[]>([]);
  const [rateLimitSeconds, setRateLimitSeconds] = useState(0);
  const [liveAnnouncement, setLiveAnnouncement] = useState('');

  useEffect(() => {
    onDocumentUploadedRef.current = onDocumentUploaded;
  }, [onDocumentUploaded]);

  useEffect(() => {
    if (isAdmin && defaultDepartmentId && !initializedDefaultDeptRef.current) {
      initializedDefaultDeptRef.current = true;
      setSelectedDepartmentId((prev) => prev || defaultDepartmentId);
    }
  }, [defaultDepartmentId, isAdmin]);

  const updateQueue = useCallback(
    (updater: (prev: UploadQueueItem[]) => UploadQueueItem[]) => {
      const next = updater(queueRef.current);
      queueRef.current = next;
      setQueue(next);
    },
    [],
  );

  const effectiveDepartmentId = isAdmin ? selectedDepartmentId.trim() : undefined;
  const adminMissingDepartment = isAdmin && !effectiveDepartmentId;

  const processQueue = useCallback(async () => {
    if (!mountedRef.current || isProcessingRef.current || rateLimitRef.current > 0) {
      return;
    }

    const nextItem = queueRef.current.find((item) => item.status === 'queued');
    if (!nextItem) {
      return;
    }

    isProcessingRef.current = true;
    const controller = new AbortController();
    activeAbortRef.current.set(nextItem.id, controller);

    updateQueue((prev) =>
      prev.map((item) =>
        item.id === nextItem.id
          ? { ...item, status: 'uploading', progress: null, errorMessage: null }
          : item,
      ),
    );

    try {
      const uploaded = await uploadDocumentWithProgress({
        file: nextItem.file,
        accessLevel: nextItem.accessLevel,
        departmentId: nextItem.departmentId,
        signal: controller.signal,
        onProgress: ({ percent }) => {
          if (!mountedRef.current) {
            return;
          }
          updateQueue((prev) =>
            prev.map((item) =>
              item.id === nextItem.id && item.status === 'uploading'
                ? { ...item, progress: percent }
                : item,
            ),
          );
        },
      });

      if (!mountedRef.current) {
        return;
      }

      updateQueue((prev) =>
        prev.map((item) =>
          item.id === nextItem.id
            ? {
                ...item,
                status: 'uploaded',
                progress: 100,
                errorMessage: null,
              }
            : item,
        ),
      );
      setLiveAnnouncement(`Đã tải lên thành công tài liệu ${uploaded.originalName}.`);
      onDocumentUploadedRef.current(uploaded);
    } catch (error) {
      if (!mountedRef.current) {
        return;
      }

      if (controller.signal.aborted) {
        updateQueue((prev) =>
          prev.map((item) =>
            item.id === nextItem.id
              ? { ...item, status: 'cancelled', errorMessage: 'Đã hủy tải lên.' }
              : item,
          ),
        );
      } else if (error instanceof ApiError && error.status === 429) {
        const waitSeconds = Math.max(1, error.retryAfterSeconds ?? 60);
        const message = formatDocumentErrorMessage(error);
        rateLimitRef.current = waitSeconds;
        setRateLimitSeconds(waitSeconds);
        updateQueue((prev) =>
          prev.map((item) =>
            item.id === nextItem.id
              ? { ...item, status: 'paused_rate_limit', errorMessage: message }
              : item,
          ),
        );
        setLiveAnnouncement(
          `Đạt giới hạn tải lên. Hàng đợi tạm dừng ${waitSeconds} giây.`,
        );
      } else {
        const message = formatDocumentErrorMessage(error);
        updateQueue((prev) =>
          prev.map((item) =>
            item.id === nextItem.id ? { ...item, status: 'error', errorMessage: message } : item,
          ),
        );
        setLiveAnnouncement(`Tải lên thất bại tệp ${nextItem.file.name}: ${message}`);
      }
    } finally {
      activeAbortRef.current.delete(nextItem.id);
      isProcessingRef.current = false;
      if (mountedRef.current && rateLimitRef.current <= 0 && hasStoredSession()) {
        void processQueue();
      }
    }
  }, [updateQueue]);

  useEffect(() => {
    mountedRef.current = true;
    const controllers = activeAbortRef.current;
    return () => {
      mountedRef.current = false;
      for (const controller of controllers.values()) {
        controller.abort();
      }
      controllers.clear();
    };
  }, []);

  const isRateLimited = rateLimitSeconds > 0;

  useEffect(() => {
    if (!isRateLimited) {
      return;
    }

    const timer = window.setInterval(() => {
      setRateLimitSeconds((prev) => (prev <= 1 ? 0 : prev - 1));
    }, 1000);

    return () => {
      window.clearInterval(timer);
    };
  }, [isRateLimited]);

  useEffect(() => {
    if (rateLimitSeconds !== 0) {
      return;
    }
    rateLimitRef.current = 0;
    const hasPaused = queueRef.current.some((item) => item.status === 'paused_rate_limit');
    if (hasPaused) {
      updateQueue((prev) =>
        prev.map((item) =>
          item.status === 'paused_rate_limit'
            ? { ...item, status: 'queued', progress: null, errorMessage: null }
            : item,
        ),
      );
    }
    if (
      (hasPaused || queueRef.current.some((item) => item.status === 'queued')) &&
      hasStoredSession()
    ) {
      void processQueue();
    }
  }, [processQueue, rateLimitSeconds, updateQueue]);

  const enqueueFiles = useCallback(
    (fileList: FileList | File[]) => {
      const files = Array.from(fileList);
      if (files.length === 0) {
        return;
      }

      if (adminMissingDepartment) {
        setSelectionErrors(['Vui lòng chọn phòng ban sở hữu trước khi tải tài liệu lên.']);
        return;
      }

      const rejected: string[] = [];
      const accepted: UploadQueueItem[] = [];

      for (const file of files) {
        const validationError = validateDocumentFile(file);
        if (validationError) {
          rejected.push(`${file.name}: ${validationError}`);
          continue;
        }
        accepted.push({
          id: createQueueId(),
          file,
          accessLevel: selectedAccessLevel,
          departmentId: effectiveDepartmentId,
          status: 'queued',
          progress: null,
          errorMessage: null,
        });
      }

      setSelectionErrors(rejected);
      if (accepted.length > 0) {
        updateQueue((prev) => [...prev, ...accepted]);
        void processQueue();
      }
    },
    [
      adminMissingDepartment,
      effectiveDepartmentId,
      processQueue,
      selectedAccessLevel,
      updateQueue,
    ],
  );

  const handleCancelItem = (item: UploadQueueItem) => {
    if (item.status === 'uploading') {
      activeAbortRef.current.get(item.id)?.abort();
      return;
    }
    if (item.status === 'queued' || item.status === 'paused_rate_limit') {
      updateQueue((prev) =>
        prev.map((entry) =>
          entry.id === item.id
            ? { ...entry, status: 'cancelled', errorMessage: 'Đã hủy tải lên.' }
            : entry,
        ),
      );
    }
  };

  const handleRetryItem = (itemId: string) => {
    updateQueue((prev) =>
      prev.map((entry) =>
        entry.id === itemId
          ? { ...entry, status: 'queued', progress: null, errorMessage: null }
          : entry,
      ),
    );
    void processQueue();
  };

  const handleRemoveItem = (itemId: string) => {
    updateQueue((prev) => prev.filter((entry) => entry.id !== itemId));
  };

  const handleClearCompleted = () => {
    updateQueue((prev) =>
      prev.filter(
        (entry) =>
          entry.status === 'queued' ||
          entry.status === 'uploading' ||
          entry.status === 'paused_rate_limit',
      ),
    );
  };

  const hasFinishedItems = queue.some(
    (entry) =>
      entry.status === 'uploaded' || entry.status === 'cancelled' || entry.status === 'error',
  );

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <CardTitle className="flex items-center gap-2 text-lg">
              <UploadCloud className="h-5 w-5 text-primary" aria-hidden="true" />
              <span>Tải lên tài liệu</span>
            </CardTitle>
            <CardDescription>
              Hỗ trợ kéo thả nhiều tệp (.pdf, .docx, .txt, .md ≤ 50 MB) và tải tuần tự theo giới
              hạn hệ thống.
            </CardDescription>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => setCollapsed((prev) => !prev)}
            aria-expanded={!collapsed}
          >
            {collapsed ? (
              <>
                <ChevronDown className="h-4 w-4" aria-hidden="true" />
                <span>Mở rộng</span>
              </>
            ) : (
              <>
                <ChevronUp className="h-4 w-4" aria-hidden="true" />
                <span>Thu gọn</span>
              </>
            )}
          </Button>
        </div>
      </CardHeader>

      {!collapsed && (
        <CardContent className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <div className="space-y-1.5">
              <label
                htmlFor={accessLevelSelectId}
                className="text-xs font-medium text-slate-700"
              >
                Mức quyền truy cập mặc định
              </label>
              <select
                id={accessLevelSelectId}
                value={selectedAccessLevel}
                onChange={(event) => setSelectedAccessLevel(event.target.value as AccessLevel)}
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <option value="DEPARTMENT">{ACCESS_LEVEL_LABELS.DEPARTMENT} (Mặc định)</option>
                <option value="PUBLIC">{ACCESS_LEVEL_LABELS.PUBLIC} (Toàn công ty)</option>
                <option value="CONFIDENTIAL">
                  {ACCESS_LEVEL_LABELS.CONFIDENTIAL} (Manager/Admin)
                </option>
              </select>
            </div>

            {isAdmin && (
              <div className="space-y-1.5">
                <label
                  htmlFor={departmentSelectId}
                  className="text-xs font-medium text-slate-700"
                >
                  Phòng ban sở hữu <span className="text-destructive">*</span>
                </label>
                <select
                  id={departmentSelectId}
                  value={selectedDepartmentId}
                  onChange={(event) => {
                    setSelectedDepartmentId(event.target.value);
                    setSelectionErrors([]);
                  }}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <option value="">Chọn phòng ban...</option>
                  {departments.map((dept) => (
                    <option key={dept.id} value={dept.id}>
                      {dept.code} - {dept.name}
                    </option>
                  ))}
                </select>
              </div>
            )}
          </div>

          <div
            onDragEnter={(event) => {
              event.preventDefault();
              dragDepthRef.current += 1;
              setIsDragging(true);
            }}
            onDragOver={(event) => {
              event.preventDefault();
              event.dataTransfer.dropEffect = adminMissingDepartment ? 'none' : 'copy';
            }}
            onDragLeave={(event) => {
              event.preventDefault();
              dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
              if (dragDepthRef.current === 0) {
                setIsDragging(false);
              }
            }}
            onDrop={(event) => {
              event.preventDefault();
              dragDepthRef.current = 0;
              setIsDragging(false);
              enqueueFiles(event.dataTransfer.files);
            }}
            className={cn(
              'flex flex-col items-center justify-center rounded-lg border-2 border-dashed px-6 py-8 text-center transition-colors',
              isDragging
                ? 'border-primary bg-primary/5'
                : 'border-slate-300 bg-slate-50/70 hover:border-slate-400',
              adminMissingDepartment && 'opacity-75',
            )}
          >
            <FileUp className="mb-2 h-8 w-8 text-primary" aria-hidden="true" />
            <p className="text-sm font-medium text-slate-900">
              Kéo và thả tài liệu vào đây hoặc chọn từ máy tính
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              Chấp nhận .pdf, .docx, .txt, .md · Dung lượng tối đa 50 MB mỗi tệp
            </p>
            {adminMissingDepartment && (
              <p className="mt-2 text-xs font-medium text-amber-700">
                Vui lòng chọn phòng ban sở hữu ở trên trước khi tải lên.
              </p>
            )}
            <div className="mt-4">
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept={ALLOWED_DOCUMENT_EXTENSIONS.join(',')}
                tabIndex={-1}
                disabled={adminMissingDepartment}
                className="sr-only"
                aria-label="Chọn tệp tải lên"
                onChange={(event) => {
                  if (event.target.files) {
                    enqueueFiles(event.target.files);
                  }
                  event.target.value = '';
                }}
              />
              <Button
                type="button"
                variant="outline"
                disabled={adminMissingDepartment}
                onClick={() => fileInputRef.current?.click()}
              >
                Chọn tệp từ máy tính
              </Button>
            </div>
          </div>

          {rateLimitSeconds > 0 && (
            <div
              role="alert"
              className="flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900"
            >
              <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
              <span>
                Đạt giới hạn tải lên. Hàng đợi tự động tiếp tục sau {rateLimitSeconds}s...
              </span>
            </div>
          )}

          {selectionErrors.length > 0 && (
            <div
              role="alert"
              className="space-y-1 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive"
            >
              {selectionErrors.map((err, index) => (
                <p key={`${err}-${index}`}>{err}</p>
              ))}
            </div>
          )}

          {queue.length > 0 && (
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  Hàng đợi tải lên ({queue.length})
                </p>
                {hasFinishedItems && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={handleClearCompleted}
                    className="h-7 px-2 text-xs"
                  >
                    Dọn các tệp đã xong
                  </Button>
                )}
              </div>

              <ul className="divide-y rounded-md border bg-background">
                {queue.map((item) => (
                  <li key={item.id} className="space-y-2 px-3 py-2.5">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="truncate text-sm font-medium text-slate-900">
                            {item.file.name}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {formatFileSize(item.file.size)}
                          </span>
                          <AccessLevelBadge accessLevel={item.accessLevel} />
                        </div>
                      </div>

                      <div className="flex items-center gap-1.5">
                        {item.status === 'queued' && (
                          <Badge variant="secondary">Đang chờ trong hàng đợi</Badge>
                        )}
                        {item.status === 'uploading' && (
                          <span className="text-xs font-medium text-primary">
                            {item.progress !== null ? `${item.progress}%` : 'Đang gửi...'}
                          </span>
                        )}
                        {item.status === 'paused_rate_limit' && (
                          <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-800">
                            Tạm dừng ({rateLimitSeconds}s)
                          </Badge>
                        )}
                        {item.status === 'uploaded' && (
                          <Badge
                            variant="outline"
                            className="gap-1 border-emerald-200 bg-emerald-50 text-emerald-800"
                          >
                            <CheckCircle2 className="h-3 w-3" aria-hidden="true" />
                            Đã tiếp nhận
                          </Badge>
                        )}
                        {(item.status === 'queued' ||
                          item.status === 'uploading' ||
                          item.status === 'paused_rate_limit') && (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => handleCancelItem(item)}
                            className="h-7 px-2 text-xs text-muted-foreground hover:text-destructive"
                            aria-label={`Hủy tải lên ${item.file.name}`}
                          >
                            <X className="h-3.5 w-3.5" aria-hidden="true" />
                            Hủy
                          </Button>
                        )}
                        {(item.status === 'error' || item.status === 'cancelled') && (
                          <>
                            <Button
                              type="button"
                              variant="ghost"
                              size="sm"
                              disabled={rateLimitSeconds > 0}
                              onClick={() => handleRetryItem(item.id)}
                              className="h-7 px-2 text-xs"
                              aria-label={`Thử lại ${item.file.name}`}
                            >
                              <RotateCcw className="h-3.5 w-3.5" aria-hidden="true" />
                              Thử lại
                            </Button>
                            <Button
                              type="button"
                              variant="ghost"
                              size="sm"
                              onClick={() => handleRemoveItem(item.id)}
                              className="h-7 px-2 text-xs text-muted-foreground"
                              aria-label={`Xóa ${item.file.name} khỏi hàng đợi`}
                            >
                              <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                            </Button>
                          </>
                        )}
                        {item.status === 'uploaded' && (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => handleRemoveItem(item.id)}
                            className="h-7 px-2 text-xs text-muted-foreground"
                            aria-label={`Ẩn ${item.file.name} khỏi hàng đợi`}
                          >
                            <X className="h-3.5 w-3.5" aria-hidden="true" />
                          </Button>
                        )}
                      </div>
                    </div>

                    {item.status === 'uploading' && (
                      <progress
                        max={100}
                        value={item.progress ?? undefined}
                        aria-label={`Tiến trình tải lên ${item.file.name}`}
                        className="h-2 w-full accent-primary"
                      />
                    )}

                    {item.errorMessage && (
                      <p className="text-xs text-destructive">{item.errorMessage}</p>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

        </CardContent>
      )}

      <div role="status" aria-live="polite" className="sr-only">
        {liveAnnouncement}
      </div>
    </Card>
  );
}
