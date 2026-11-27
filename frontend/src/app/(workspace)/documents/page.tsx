'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { DeleteDocumentDialog } from '@/components/documents/delete-document-dialog';
import { DocumentTable } from '@/components/documents/document-table';
import { DocumentUploadZone } from '@/components/documents/document-upload-zone';
import { EditDocumentDialog } from '@/components/documents/edit-document-dialog';
import { ReplaceDocumentDialog } from '@/components/documents/replace-document-dialog';
import { Badge } from '@/components/ui/badge';
import {
  formatDocumentErrorMessage,
  getDocumentPreviewUrl,
  getDocumentStatus,
  listAdminDepartments,
  listDocuments,
} from '@/lib/api/documents';
import { hasStoredSession } from '@/lib/auth/token-manager';
import { ROLE_LABELS } from '@/lib/types/auth';
import type { AdminDepartmentOption } from '@/lib/types/chat';
import {
  DEFAULT_DOCUMENT_PAGE_SIZE,
  DOCUMENT_POLL_INTERVAL_MS,
  normalizeVietnameseSearch,
  type AccessLevel,
  type DocumentResponse,
  type DocumentStatus,
  type DocumentStatusResponse,
} from '@/lib/types/documents';
import { ApiError } from '@/lib/types/problem';
import { useAuth } from '@/providers/auth-provider';

function sanitizePreviewUrl(rawUrl: string): string | null {
  const trimmed = rawUrl.trim();
  if (!trimmed || /[\u0000-\u001F\u007F]/.test(trimmed) || trimmed.includes('\\')) {
    return null;
  }
  if (trimmed.startsWith('/')) {
    if (trimmed.startsWith('//')) {
      return null;
    }
    try {
      const parsed = new URL(trimmed, 'http://documind.local');
      if (parsed.origin !== 'http://documind.local' || parsed.pathname.startsWith('//')) {
        return null;
      }
      return `${parsed.pathname}${parsed.search}${parsed.hash}`;
    } catch {
      return null;
    }
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
      return parsed.toString();
    }
    return null;
  } catch {
    return null;
  }
}

export default function DocumentsPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === 'SYSTEM_ADMIN';

  const [documents, setDocuments] = useState<DocumentResponse[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<DocumentStatus | ''>('');
  const [accessLevelFilter, setAccessLevelFilter] = useState<AccessLevel | ''>('');
  const [departmentFilter, setDepartmentFilter] = useState('');
  const [departments, setDepartments] = useState<AdminDepartmentOption[]>([]);

  const [previewingId, setPreviewingId] = useState<string | null>(null);
  const [replaceTarget, setReplaceTarget] = useState<DocumentResponse | null>(null);
  const [editTarget, setEditTarget] = useState<DocumentResponse | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<DocumentResponse | null>(null);
  const [statusAnnouncement, setStatusAnnouncement] = useState('');
  const [isPageVisible, setIsPageVisible] = useState(() =>
    typeof document === 'undefined' ? true : document.visibilityState !== 'hidden',
  );

  const listAbortRef = useRef<AbortController | null>(null);
  const previewAbortRef = useRef<AbortController | null>(null);
  const listSeqRef = useRef(0);
  const mutationSeqRef = useRef(0);
  const documentsRef = useRef<DocumentResponse[]>([]);

  const updateDocuments = useCallback(
    (updater: (prev: DocumentResponse[]) => DocumentResponse[]): DocumentResponse[] => {
      const next = updater(documentsRef.current);
      documentsRef.current = next;
      setDocuments(next);
      return next;
    },
    [],
  );

  useEffect(() => {
    return () => {
      previewAbortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!isAdmin) {
      setDepartments([]);
      setDepartmentFilter('');
      return;
    }

    const controller = new AbortController();
    void listAdminDepartments(controller.signal)
      .then((res) => {
        if (!controller.signal.aborted) {
          setDepartments(res.items);
        }
      })
      .catch(() => {
        // Admin can still filter/manage documents if department lookup fails
      });

    return () => {
      controller.abort();
    };
  }, [isAdmin]);

  useEffect(() => {
    if (typeof document === 'undefined') {
      return;
    }
    const handleVisibilityChange = () => {
      setIsPageVisible(document.visibilityState !== 'hidden');
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, []);

  const loadDocuments = useCallback(async () => {
    listAbortRef.current?.abort();
    const controller = new AbortController();
    listAbortRef.current = controller;
    const seq = ++listSeqRef.current;
    mutationSeqRef.current += 1;

    setIsLoading(true);
    setLoadError(null);
    try {
      const result = await listDocuments(
        {
          page,
          size: DEFAULT_DOCUMENT_PAGE_SIZE,
          status: statusFilter || undefined,
          accessLevel: accessLevelFilter || undefined,
          departmentId: isAdmin && departmentFilter ? departmentFilter : undefined,
        },
        controller.signal,
      );
      if (seq !== listSeqRef.current || controller.signal.aborted) {
        return;
      }
      updateDocuments(() => result.items);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } catch (error) {
      if (controller.signal.aborted || seq !== listSeqRef.current) {
        return;
      }
      if (!hasStoredSession()) {
        return;
      }
      setLoadError(formatDocumentErrorMessage(error));
    } finally {
      if (seq === listSeqRef.current) {
        setIsLoading(false);
      }
    }
  }, [accessLevelFilter, departmentFilter, isAdmin, page, statusFilter, updateDocuments]);

  useEffect(() => {
    void loadDocuments();
    return () => {
      listAbortRef.current?.abort();
    };
  }, [loadDocuments]);

  const inFlightKey = useMemo(
    () =>
      documents
        .filter((doc) => doc.status === 'PENDING' || doc.status === 'PROCESSING')
        .map((doc) => `${doc.id}:${doc.currentVersion}`)
        .join(','),
    [documents],
  );

  const inFlightCount = useMemo(
    () => (inFlightKey ? inFlightKey.split(',').length : 0),
    [inFlightKey],
  );

  useEffect(() => {
    if (!inFlightKey || !isPageVisible) {
      return;
    }

    const trackedIds = inFlightKey.split(',').map((entry) => entry.split(':')[0]);
    let cancelled = false;
    let timerId: number | null = null;
    const pollController = new AbortController();

    const notifyTransitions = (prevList: DocumentResponse[], nextList: DocumentResponse[]) => {
      const prevMap = new Map(prevList.map((item) => [item.id, item]));
      for (const nextItem of nextList) {
        const prevItem = prevMap.get(nextItem.id);
        if (!prevItem) {
          continue;
        }
        const wasPending = prevItem.status === 'PENDING' || prevItem.status === 'PROCESSING';
        if (!wasPending) {
          continue;
        }
        if (nextItem.status === 'COMPLETED') {
          const msg = `Tài liệu "${nextItem.originalName}" đã xử lý xong (${nextItem.chunkCount} đoạn).`;
          setStatusAnnouncement(msg);
          toast.success(msg);
        } else if (nextItem.status === 'FAILED') {
          const detail = nextItem.errorMessage ? `: ${nextItem.errorMessage}` : '';
          const msg = `Xử lý tài liệu "${nextItem.originalName}" thất bại${detail}`;
          setStatusAnnouncement(msg);
          toast.error(msg);
        }
      }
    };

    const runPoll = async () => {
      if (cancelled || !hasStoredSession()) {
        return;
      }

      try {
        if (trackedIds.length <= 5) {
          const settled = await Promise.allSettled(
            trackedIds.map((id) => getDocumentStatus(id, pollController.signal)),
          );
          if (cancelled || pollController.signal.aborted) {
            return;
          }

          const statusMap = new Map<string, DocumentStatusResponse>();
          const evictIds = new Set<string>();
          settled.forEach((entry, index) => {
            if (entry.status === 'fulfilled') {
              statusMap.set(entry.value.id, entry.value);
            } else if (
              entry.reason instanceof ApiError &&
              (entry.reason.status === 404 || entry.reason.status === 403)
            ) {
              evictIds.add(trackedIds[index]);
            }
          });

          if (statusMap.size > 0 || evictIds.size > 0) {
            const prevList = documentsRef.current;
            const nextList = updateDocuments((prev) =>
              prev
                .filter((item) => !evictIds.has(item.id))
                .map((item) => {
                  const latest = statusMap.get(item.id);
                  if (!latest || latest.currentVersion < item.currentVersion) {
                    return item;
                  }
                  return {
                    ...item,
                    status: latest.status,
                    chunkCount: latest.chunkCount,
                    currentVersion: latest.currentVersion,
                    errorMessage: latest.errorMessage,
                    updatedAt: latest.updatedAt,
                  };
                }),
            );
            notifyTransitions(prevList, nextList);
            if (evictIds.size > 0) {
              const evictedCount = prevList.length - nextList.length;
              if (evictedCount > 0) {
                setTotalElements((count) => Math.max(0, count - evictedCount));
              }
            }
          }
        } else {
          const seqBefore = mutationSeqRef.current;
          const res = await listDocuments(
            {
              page,
              size: DEFAULT_DOCUMENT_PAGE_SIZE,
              status: statusFilter || undefined,
              accessLevel: accessLevelFilter || undefined,
              departmentId: isAdmin && departmentFilter ? departmentFilter : undefined,
            },
            pollController.signal,
          );
          if (cancelled || pollController.signal.aborted) {
            return;
          }
          if (mutationSeqRef.current === seqBefore) {
            const prevList = documentsRef.current;
            updateDocuments(() => res.items);
            notifyTransitions(prevList, res.items);
            setTotalPages(res.totalPages);
            setTotalElements(res.totalElements);
          }
        }
      } catch {
        // Ignore transient poll errors; next scheduled tick will retry while in-flight docs remain
      }

      if (!cancelled && !pollController.signal.aborted && hasStoredSession()) {
        const stillPending = documentsRef.current.some(
          (doc) => doc.status === 'PENDING' || doc.status === 'PROCESSING',
        );
        if (stillPending) {
          timerId = window.setTimeout(() => {
            void runPoll();
          }, DOCUMENT_POLL_INTERVAL_MS);
        }
      }
    };

    timerId = window.setTimeout(() => {
      void runPoll();
    }, DOCUMENT_POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      pollController.abort();
      if (timerId !== null) {
        window.clearTimeout(timerId);
      }
    };
  }, [
    accessLevelFilter,
    departmentFilter,
    inFlightKey,
    isAdmin,
    isPageVisible,
    page,
    statusFilter,
    updateDocuments,
  ]);

  const matchesActiveServerFilters = useCallback(
    (doc: DocumentResponse): boolean => {
      if (statusFilter && doc.status !== statusFilter) {
        return false;
      }
      if (accessLevelFilter && doc.accessLevel !== accessLevelFilter) {
        return false;
      }
      if (isAdmin && departmentFilter && doc.departmentId !== departmentFilter) {
        return false;
      }
      return true;
    },
    [accessLevelFilter, departmentFilter, isAdmin, statusFilter],
  );

  const handleDocumentUploaded = useCallback(
    (uploaded: DocumentResponse) => {
      mutationSeqRef.current += 1;
      toast.success(`Đã tiếp nhận "${uploaded.originalName}" để nạp vào kho tri thức.`);
      if (matchesActiveServerFilters(uploaded) && page === 0) {
        updateDocuments((prev) => [uploaded, ...prev.filter((item) => item.id !== uploaded.id)]);
        setTotalElements((prev) => prev + 1);
      }
    },
    [matchesActiveServerFilters, page, updateDocuments],
  );

  const handleDocumentReplaced = useCallback(
    (updated: DocumentResponse) => {
      mutationSeqRef.current += 1;
      toast.success(`Đã tải phiên bản v${updated.currentVersion} cho "${updated.originalName}".`);
      updateDocuments((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
    },
    [updateDocuments],
  );

  const handleDocumentUpdated = useCallback(
    (updated: DocumentResponse) => {
      mutationSeqRef.current += 1;
      toast.success(`Đã cập nhật thông tin "${updated.originalName}".`);
      let removed = false;
      updateDocuments((prev) => {
        if (!matchesActiveServerFilters(updated)) {
          removed = prev.some((item) => item.id === updated.id);
          return prev.filter((item) => item.id !== updated.id);
        }
        return prev.map((item) => (item.id === updated.id ? updated : item));
      });
      if (removed) {
        setTotalElements((prev) => Math.max(0, prev - 1));
      }
    },
    [matchesActiveServerFilters, updateDocuments],
  );

  const handleDocumentDeleted = useCallback(
    (deletedId: string) => {
      mutationSeqRef.current += 1;
      toast.success('Đã xóa tài liệu khỏi kho tri thức.');
      let shouldStepBack = false;
      updateDocuments((prev) => {
        const next = prev.filter((item) => item.id !== deletedId);
        shouldStepBack = prev.length === 1 && next.length === 0 && page > 0;
        return next;
      });
      setTotalElements((prev) => Math.max(0, prev - 1));
      if (shouldStepBack) {
        setPage((p) => Math.max(0, p - 1));
      }
    },
    [page, updateDocuments],
  );

  const handlePreview = useCallback(async (doc: DocumentResponse) => {
    previewAbortRef.current?.abort();
    const controller = new AbortController();
    previewAbortRef.current = controller;
    setPreviewingId(doc.id);
    try {
      const res = await getDocumentPreviewUrl(doc.id, undefined, controller.signal);
      if (controller.signal.aborted) {
        return;
      }
      const safeUrl = sanitizePreviewUrl(res.previewUrl);
      if (!safeUrl) {
        toast.error('Liên kết xem trước không hợp lệ.');
        return;
      }
      const opened = window.open(safeUrl, '_blank', 'noopener,noreferrer');
      if (!opened) {
        toast.error('Trình duyệt đã chặn cửa sổ xem trước. Vui lòng cho phép pop-up và thử lại.');
      }
    } catch (error) {
      if (controller.signal.aborted || !hasStoredSession()) {
        return;
      }
      toast.error(formatDocumentErrorMessage(error));
    } finally {
      if (previewAbortRef.current === controller) {
        previewAbortRef.current = null;
      }
      setPreviewingId((prev) => (prev === doc.id ? null : prev));
    }
  }, []);

  const visibleDocuments = useMemo(() => {
    const normalizedQuery = normalizeVietnameseSearch(searchQuery);
    if (!normalizedQuery) {
      return documents;
    }
    return documents.filter((doc) =>
      normalizeVietnameseSearch(doc.originalName).includes(normalizedQuery),
    );
  }, [documents, searchQuery]);

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Kho Tài liệu</h1>
          <p className="text-sm text-muted-foreground">
            Tải lên tài liệu phòng ban, theo dõi tiến trình lập chỉ mục vector và quản lý phiên bản
          </p>
        </div>
        {user && <Badge variant="secondary">{ROLE_LABELS[user.role]}</Badge>}
      </div>

      <DocumentUploadZone
        isAdmin={isAdmin}
        defaultDepartmentId={user?.departmentId ?? null}
        departments={departments}
        onDocumentUploaded={handleDocumentUploaded}
      />

      <DocumentTable
        documents={visibleDocuments}
        totalElements={totalElements}
        page={page}
        totalPages={totalPages}
        isLoading={isLoading}
        loadError={loadError}
        pollingCount={inFlightCount}
        searchQuery={searchQuery}
        onSearchQueryChange={setSearchQuery}
        statusFilter={statusFilter}
        onStatusFilterChange={(val) => {
          setStatusFilter(val);
          setPage(0);
        }}
        accessLevelFilter={accessLevelFilter}
        onAccessLevelFilterChange={(val) => {
          setAccessLevelFilter(val);
          setPage(0);
        }}
        departmentFilter={departmentFilter}
        onDepartmentFilterChange={(val) => {
          setDepartmentFilter(val);
          setPage(0);
        }}
        departments={departments}
        currentUser={user}
        previewingId={previewingId}
        onPageChange={setPage}
        onRefresh={() => void loadDocuments()}
        onPreview={(doc) => void handlePreview(doc)}
        onReplace={setReplaceTarget}
        onEdit={setEditTarget}
        onDelete={setDeleteTarget}
      />

      <ReplaceDocumentDialog
        document={replaceTarget}
        onClose={() => setReplaceTarget(null)}
        onReplaced={handleDocumentReplaced}
      />

      <EditDocumentDialog
        document={editTarget}
        isAdmin={isAdmin}
        departments={departments}
        onClose={() => setEditTarget(null)}
        onUpdated={handleDocumentUpdated}
      />

      <DeleteDocumentDialog
        document={deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onDeleted={handleDocumentDeleted}
      />

      <div role="status" aria-live="polite" className="sr-only">
        {statusAnnouncement}
      </div>
    </div>
  );
}
