'use client';

import React from 'react';
import { Eye, FilterX } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  AUDIT_ACTION_LABELS,
  AUDIT_ACTION_TYPES,
  type AdminAuditLogItemResponse,
  type AdminDepartmentResponse,
  type AdminUserResponse,
  type AuditActionType,
  type PageResponse,
} from '@/lib/types/admin';

export interface AuditLogFilterState {
  actionType: '' | AuditActionType;
  departmentId: string;
  cacheHit: '' | 'true' | 'false';
  statusCode: string;
  userId: string;
}

interface AuditLogTableProps {
  data: PageResponse<AdminAuditLogItemResponse> | null;
  loading: boolean;
  filters: AuditLogFilterState;
  departments: AdminDepartmentResponse[];
  users: AdminUserResponse[];
  onFilterChange: (next: AuditLogFilterState) => void;
  onResetFilters: () => void;
  onPageChange: (nextPage: number) => void;
  onSelectLog: (item: AdminAuditLogItemResponse) => void;
}

const HTTP_STATUS_OPTIONS = [200, 401, 403, 429, 500];

function statusBadgeClass(code: number): string {
  if (code >= 200 && code < 300) {
    return 'bg-emerald-100 text-emerald-800 border-emerald-200';
  }
  if (code === 429) {
    return 'bg-amber-100 text-amber-800 border-amber-200';
  }
  return 'bg-rose-100 text-rose-800 border-rose-200';
}

export function AuditLogTable({
  data,
  loading,
  filters,
  departments,
  users,
  onFilterChange,
  onResetFilters,
  onPageChange,
  onSelectLog,
}: AuditLogTableProps) {
  const items = data?.items ?? [];
  const currentPage = data?.page ?? 0;
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const totalElements = data?.totalElements ?? 0;
  const hasSelectedUserInOptions =
    !filters.userId || users.some((u) => u.id === filters.userId);
  const fallbackUserLabel =
    items.find((row) => row.userId === filters.userId)?.userEmail ?? filters.userId;
  const hasActiveFilters = Boolean(
    filters.actionType ||
      filters.departmentId ||
      filters.cacheHit ||
      filters.statusCode ||
      filters.userId,
  );

  return (
    <Card className="border-slate-200 shadow-sm">
      <CardHeader className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <CardTitle className="text-base">Nhật ký kiểm toán &amp; Token (Audit Logs)</CardTitle>
            <CardDescription>
              Hiển thị {totalElements.toLocaleString('vi-VN')} sự kiện trong cửa sổ thời gian đã chọn
            </CardDescription>
          </div>
          {hasActiveFilters ? (
            <Button type="button" variant="outline" size="sm" onClick={onResetFilters}>
              <FilterX className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
              Xóa bộ lọc
            </Button>
          ) : null}
        </div>

        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-5">
          <label className="flex flex-col gap-1 text-xs font-medium text-slate-600">
            Loại hành động
            <select
              aria-label="Lọc theo loại hành động"
              className="h-9 rounded-md border border-input bg-background px-2.5 text-sm text-foreground"
              value={filters.actionType}
              onChange={(e) =>
                onFilterChange({
                  ...filters,
                  actionType: e.target.value as '' | AuditActionType,
                })
              }
            >
              <option value="">Tất cả hành động</option>
              {AUDIT_ACTION_TYPES.map((type) => (
                <option key={type} value={type}>
                  {AUDIT_ACTION_LABELS[type]} ({type})
                </option>
              ))}
            </select>
          </label>

          <label className="flex flex-col gap-1 text-xs font-medium text-slate-600">
            Phòng ban
            <select
              aria-label="Lọc theo phòng ban"
              className="h-9 rounded-md border border-input bg-background px-2.5 text-sm text-foreground"
              value={filters.departmentId}
              onChange={(e) => onFilterChange({ ...filters, departmentId: e.target.value })}
            >
              <option value="">Tất cả phòng ban</option>
              {departments.map((dept) => (
                <option key={dept.id} value={dept.id}>
                  {dept.code} — {dept.name}
                </option>
              ))}
            </select>
          </label>

          <label className="flex flex-col gap-1 text-xs font-medium text-slate-600">
            Trạng thái Cache
            <select
              aria-label="Lọc theo Cache Hit"
              className="h-9 rounded-md border border-input bg-background px-2.5 text-sm text-foreground"
              value={filters.cacheHit}
              onChange={(e) =>
                onFilterChange({
                  ...filters,
                  cacheHit: e.target.value as '' | 'true' | 'false',
                })
              }
            >
              <option value="">Tất cả Cache</option>
              <option value="true">Cache Hit</option>
              <option value="false">Cache Miss</option>
            </select>
          </label>

          <label className="flex flex-col gap-1 text-xs font-medium text-slate-600">
            Mã HTTP
            <select
              aria-label="Lọc theo mã HTTP"
              className="h-9 rounded-md border border-input bg-background px-2.5 text-sm text-foreground"
              value={filters.statusCode}
              onChange={(e) => onFilterChange({ ...filters, statusCode: e.target.value })}
            >
              <option value="">Tất cả mã HTTP</option>
              {HTTP_STATUS_OPTIONS.map((code) => (
                <option key={code} value={String(code)}>
                  HTTP {code}
                </option>
              ))}
            </select>
          </label>

          <label className="flex flex-col gap-1 text-xs font-medium text-slate-600">
            Người dùng
            <select
              aria-label="Lọc theo người dùng"
              className="h-9 rounded-md border border-input bg-background px-2.5 text-sm text-foreground"
              value={filters.userId}
              onChange={(e) => onFilterChange({ ...filters, userId: e.target.value })}
            >
              <option value="">Tất cả người dùng</option>
              {!hasSelectedUserInOptions ? (
                <option value={filters.userId}>{fallbackUserLabel}</option>
              ) : null}
              {users.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.email}
                </option>
              ))}
            </select>
          </label>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        <div
          tabIndex={0}
          role="region"
          aria-label="Bảng nhật ký kiểm toán Audit Logs"
          className="max-h-[520px] overflow-auto rounded-md border border-slate-200 focus:outline-none focus:ring-2 focus:ring-ring"
        >
          <Table>
            <TableHeader className="sticky top-0 z-10 bg-slate-50 shadow-sm">
              <TableRow>
                <TableHead scope="col" className="whitespace-nowrap">
                  Thời gian
                </TableHead>
                <TableHead scope="col">Người dùng</TableHead>
                <TableHead scope="col">Phòng ban</TableHead>
                <TableHead scope="col">Hành động</TableHead>
                <TableHead scope="col">Truy vấn</TableHead>
                <TableHead scope="col">Cache</TableHead>
                <TableHead scope="col" className="text-right">
                  Tokens
                </TableHead>
                <TableHead scope="col" className="text-right">
                  Độ trễ
                </TableHead>
                <TableHead scope="col">HTTP</TableHead>
                <TableHead scope="col" className="text-right">
                  Chi tiết
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={10} className="h-24 text-center text-muted-foreground">
                    Đang tải nhật ký kiểm toán...
                  </TableCell>
                </TableRow>
              ) : items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={10} className="h-24 text-center text-muted-foreground">
                    Không tìm thấy bản ghi Audit Log nào khớp bộ lọc.
                  </TableCell>
                </TableRow>
              ) : (
                items.map((item) => (
                  <TableRow
                    key={item.id}
                    className="cursor-pointer"
                    onClick={() => onSelectLog(item)}
                  >
                    <TableCell className="whitespace-nowrap text-xs text-slate-600">
                      {new Date(item.createdAt).toLocaleString('vi-VN')}
                    </TableCell>
                    <TableCell className="max-w-[180px]">
                      {item.userId && item.userEmail ? (
                        <button
                          type="button"
                          className="truncate text-left text-xs font-medium text-sky-700 hover:underline"
                          title={`Lọc nhanh theo ${item.userEmail}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            onFilterChange({ ...filters, userId: item.userId! });
                          }}
                        >
                          {item.userEmail}
                        </button>
                      ) : (
                        <span className="text-xs text-muted-foreground">
                          {item.userEmail ?? 'Hệ thống'}
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-xs">
                      {item.departmentCode ?? 'Hệ thống'}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      <Badge variant="secondary">{item.actionType}</Badge>
                    </TableCell>
                    <TableCell className="max-w-[240px]">
                      <span
                        className="block truncate text-xs text-slate-700"
                        title={item.queryText ?? ''}
                      >
                        {item.queryText?.trim() ? item.queryText : '—'}
                      </span>
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      {item.cacheHit ? (
                        <Badge className="bg-emerald-100 text-emerald-800 hover:bg-emerald-100">
                          {item.cacheTier}
                        </Badge>
                      ) : (
                        <span className="text-xs text-muted-foreground">
                          {item.cacheTier === 'NONE' ? 'Miss' : item.cacheTier}
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-right text-xs font-medium">
                      {item.totalTokens.toLocaleString('vi-VN')}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-right text-xs">
                      {item.latencyMs.toLocaleString('vi-VN')} ms
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      <Badge variant="outline" className={statusBadgeClass(item.statusCode)}>
                        {item.statusCode}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        aria-label={`Xem chi tiết bản ghi ${item.id}`}
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelectLog(item);
                        }}
                      >
                        <Eye className="h-4 w-4" aria-hidden="true" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
          <span>
            Trang {currentPage + 1} / {totalPages} ({totalElements.toLocaleString('vi-VN')} bản ghi)
          </span>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={loading || currentPage <= 0}
              onClick={() => onPageChange(currentPage - 1)}
            >
              Trang trước
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={loading || currentPage + 1 >= totalPages}
              onClick={() => onPageChange(currentPage + 1)}
            >
              Trang sau
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
