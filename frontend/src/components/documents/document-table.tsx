'use client';

import React, { useId, useMemo } from 'react';
import Link from 'next/link';
import {
  ChevronLeft,
  ChevronRight,
  Eye,
  FileText,
  Loader2,
  MessageSquare,
  Pencil,
  RefreshCw,
  Search,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import {
  AccessLevelBadge,
  DocumentStatusBadge,
} from '@/components/documents/document-status-badge';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import type { UserProfileResponse } from '@/lib/types/auth';
import type { AdminDepartmentOption } from '@/lib/types/chat';
import {
  ACCESS_LEVEL_LABELS,
  DOCUMENT_STATUS_LABELS,
  canMutateDocument,
  formatFileSize,
  type AccessLevel,
  type DocumentResponse,
  type DocumentStatus,
} from '@/lib/types/documents';

interface DocumentTableProps {
  documents: DocumentResponse[];
  totalElements: number;
  page: number;
  totalPages: number;
  isLoading: boolean;
  loadError: string | null;
  pollingCount: number;
  searchQuery: string;
  onSearchQueryChange: (value: string) => void;
  statusFilter: DocumentStatus | '';
  onStatusFilterChange: (value: DocumentStatus | '') => void;
  accessLevelFilter: AccessLevel | '';
  onAccessLevelFilterChange: (value: AccessLevel | '') => void;
  departmentFilter: string;
  onDepartmentFilterChange: (value: string) => void;
  departments: AdminDepartmentOption[];
  currentUser: UserProfileResponse | null;
  previewingId: string | null;
  onPageChange: (nextPage: number) => void;
  onRefresh: () => void;
  onPreview: (doc: DocumentResponse) => void;
  onReplace: (doc: DocumentResponse) => void;
  onEdit: (doc: DocumentResponse) => void;
  onDelete: (doc: DocumentResponse) => void;
}

function formatTimestamp(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function DocumentTable({
  documents,
  totalElements,
  page,
  totalPages,
  isLoading,
  loadError,
  pollingCount,
  searchQuery,
  onSearchQueryChange,
  statusFilter,
  onStatusFilterChange,
  accessLevelFilter,
  onAccessLevelFilterChange,
  departmentFilter,
  onDepartmentFilterChange,
  departments,
  currentUser,
  previewingId,
  onPageChange,
  onRefresh,
  onPreview,
  onReplace,
  onEdit,
  onDelete,
}: DocumentTableProps) {
  const searchInputId = useId();
  const statusSelectId = useId();
  const accessSelectId = useId();
  const deptSelectId = useId();
  const isAdmin = currentUser?.role === 'SYSTEM_ADMIN';

  const departmentLabelMap = useMemo(
    () => new Map(departments.map((dept) => [dept.id, `${dept.code} - ${dept.name}`])),
    [departments],
  );

  const hasActiveFilters = Boolean(
    searchQuery.trim() || statusFilter || accessLevelFilter || departmentFilter,
  );
  const colCount = isAdmin ? 6 : 5;

  return (
    <Card>
      <CardHeader className="space-y-4 pb-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <CardTitle className="flex items-center gap-2 text-lg">
              <FileText className="h-5 w-5 text-primary" aria-hidden="true" />
              <span>Danh sách tài liệu ({totalElements})</span>
            </CardTitle>
            {pollingCount > 0 && (
              <Badge variant="outline" className="gap-1 border-sky-200 bg-sky-50 text-sky-800">
                <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
                Đang theo dõi {pollingCount} tài liệu...
              </Badge>
            )}
          </div>

          <Button type="button" variant="outline" size="sm" onClick={onRefresh} disabled={isLoading}>
            <RefreshCw className={isLoading ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} aria-hidden="true" />
            Làm mới
          </Button>
        </div>

        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <div className="relative">
            <label htmlFor={searchInputId} className="sr-only">
              Tìm theo tên tài liệu
            </label>
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              id={searchInputId}
              type="search"
              value={searchQuery}
              onChange={(event) => onSearchQueryChange(event.target.value)}
              placeholder="Tìm theo tên tài liệu..."
              className="pl-9 pr-8"
            />
            {searchQuery && (
              <button
                type="button"
                onClick={() => onSearchQueryChange('')}
                aria-label="Xóa từ khóa tìm kiếm"
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                <X className="h-4 w-4" aria-hidden="true" />
              </button>
            )}
          </div>

          <div>
            <label htmlFor={statusSelectId} className="sr-only">
              Lọc theo trạng thái
            </label>
            <select
              id={statusSelectId}
              aria-label="Lọc theo trạng thái"
              value={statusFilter}
              onChange={(event) =>
                onStatusFilterChange(event.target.value as DocumentStatus | '')
              }
              className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <option value="">Tất cả trạng thái</option>
              <option value="PENDING">{DOCUMENT_STATUS_LABELS.PENDING}</option>
              <option value="PROCESSING">{DOCUMENT_STATUS_LABELS.PROCESSING}</option>
              <option value="COMPLETED">{DOCUMENT_STATUS_LABELS.COMPLETED}</option>
              <option value="FAILED">{DOCUMENT_STATUS_LABELS.FAILED}</option>
            </select>
          </div>

          <div>
            <label htmlFor={accessSelectId} className="sr-only">
              Lọc theo mức quyền
            </label>
            <select
              id={accessSelectId}
              aria-label="Lọc theo mức quyền"
              value={accessLevelFilter}
              onChange={(event) =>
                onAccessLevelFilterChange(event.target.value as AccessLevel | '')
              }
              className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <option value="">Tất cả mức quyền</option>
              <option value="DEPARTMENT">{ACCESS_LEVEL_LABELS.DEPARTMENT}</option>
              <option value="PUBLIC">{ACCESS_LEVEL_LABELS.PUBLIC}</option>
              <option value="CONFIDENTIAL">{ACCESS_LEVEL_LABELS.CONFIDENTIAL}</option>
            </select>
          </div>

          {isAdmin && (
            <div>
              <label htmlFor={deptSelectId} className="sr-only">
                Lọc theo phòng ban
              </label>
              <select
                id={deptSelectId}
                aria-label="Lọc theo phòng ban"
                value={departmentFilter}
                onChange={(event) => onDepartmentFilterChange(event.target.value)}
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <option value="">Tất cả phòng ban</option>
                {departments.map((dept) => (
                  <option key={dept.id} value={dept.id}>
                    {dept.code} - {dept.name}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {loadError && (
          <div
            role="alert"
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive"
          >
            <span>{loadError}</span>
            <Button type="button" variant="outline" size="sm" onClick={onRefresh}>
              Thử lại
            </Button>
          </div>
        )}

        <div
          tabIndex={0}
          role="region"
          aria-label="Danh sách tài liệu"
          className="overflow-x-auto rounded-md border focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Tài liệu</TableHead>
                {isAdmin && <TableHead scope="col">Phòng ban</TableHead>}
                <TableHead scope="col">Mức quyền</TableHead>
                <TableHead scope="col">Trạng thái</TableHead>
                <TableHead scope="col">Cập nhật</TableHead>
                <TableHead scope="col" className="text-right">
                  Thao tác
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading && documents.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={colCount} className="py-10 text-center text-muted-foreground">
                    <span className="inline-flex items-center gap-2">
                      <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                      Đang tải danh sách tài liệu...
                    </span>
                  </TableCell>
                </TableRow>
              ) : documents.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={colCount} className="py-10 text-center text-muted-foreground">
                    {hasActiveFilters
                      ? 'Không tìm thấy tài liệu nào khớp điều kiện lọc.'
                      : 'Kho tài liệu hiện chưa có tệp nào. Kéo thả tệp ở trên để bắt đầu.'}
                  </TableCell>
                </TableRow>
              ) : (
                documents.map((doc) => {
                  const mutable = canMutateDocument(doc, currentUser);
                  const isPreviewing = previewingId === doc.id;
                  return (
                    <TableRow key={doc.id}>
                      <th scope="row" className="p-4 text-left align-middle font-normal">
                        <div className="space-y-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-medium text-slate-900">{doc.originalName}</span>
                            <Badge variant="outline" className="font-mono text-[11px]">
                              v{doc.currentVersion}
                            </Badge>
                          </div>
                          <div className="text-xs text-muted-foreground">
                            .{doc.fileExtension} · {formatFileSize(doc.fileSizeBytes)}
                          </div>
                          {doc.status === 'FAILED' && doc.errorMessage && (
                            <p className="rounded border border-destructive/30 bg-destructive/5 px-2 py-1 text-xs text-destructive">
                              {doc.errorMessage}
                            </p>
                          )}
                        </div>
                      </th>

                      {isAdmin && (
                        <TableCell className="text-xs text-slate-700">
                          {departmentLabelMap.get(doc.departmentId) ?? doc.departmentId}
                        </TableCell>
                      )}

                      <TableCell>
                        <AccessLevelBadge accessLevel={doc.accessLevel} />
                      </TableCell>

                      <TableCell>
                        <DocumentStatusBadge status={doc.status} chunkCount={doc.chunkCount} />
                      </TableCell>

                      <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                        {formatTimestamp(doc.updatedAt)}
                      </TableCell>

                      <TableCell className="text-right">
                        <div className="inline-flex flex-wrap items-center justify-end gap-1">
                          {doc.status === 'COMPLETED' && (
                            <Button variant="secondary" size="sm" asChild className="h-8 gap-1 px-2.5 text-xs">
                              <Link href="/chat">
                                <MessageSquare className="h-3.5 w-3.5" aria-hidden="true" />
                                Hỏi AI ngay
                              </Link>
                            </Button>
                          )}

                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            disabled={isPreviewing}
                            onClick={() => onPreview(doc)}
                            aria-label={`Xem trước ${doc.originalName}`}
                            title="Xem trước tài liệu"
                            className="h-8 px-2"
                          >
                            {isPreviewing ? (
                              <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                            ) : (
                              <Eye className="h-4 w-4" aria-hidden="true" />
                            )}
                          </Button>

                          {mutable ? (
                            <>
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                onClick={() => onReplace(doc)}
                                aria-label={`Cập nhật phiên bản mới cho ${doc.originalName}`}
                                title="Cập nhật phiên bản mới"
                                className="h-8 px-2"
                              >
                                <Upload className="h-4 w-4" aria-hidden="true" />
                              </Button>
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                onClick={() => onEdit(doc)}
                                aria-label={`Sửa thông tin ${doc.originalName}`}
                                title="Sửa tên / mức quyền"
                                className="h-8 px-2"
                              >
                                <Pencil className="h-4 w-4" aria-hidden="true" />
                              </Button>
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                onClick={() => onDelete(doc)}
                                aria-label={`Xóa ${doc.originalName}`}
                                title="Xóa tài liệu"
                                className="h-8 px-2 text-destructive hover:text-destructive"
                              >
                                <Trash2 className="h-4 w-4" aria-hidden="true" />
                              </Button>
                            </>
                          ) : (
                            <Badge variant="outline" className="text-[11px] text-muted-foreground">
                              Chỉ đọc
                            </Badge>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
        </div>

        {totalPages > 1 && (
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>
              Trang {page + 1} / {totalPages}
            </span>
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page <= 0 || isLoading}
                onClick={() => onPageChange(page - 1)}
              >
                <ChevronLeft className="h-4 w-4" aria-hidden="true" />
                Trước
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page >= totalPages - 1 || isLoading}
                onClick={() => onPageChange(page + 1)}
              >
                Sau
                <ChevronRight className="h-4 w-4" aria-hidden="true" />
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
