'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { Pencil, Plus, Search, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { DepartmentFormDialog } from '@/components/admin/department-form-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { deleteAdminDepartment, listAdminDepartmentsPage } from '@/lib/api/admin';
import type { AdminDepartmentResponse, PageResponse } from '@/lib/types/admin';
import { formatApiErrorMessage } from '@/lib/types/problem';

interface DepartmentManagementTabProps {
  onDepartmentsChanged: () => void;
}

export function DepartmentManagementTab({ onDepartmentsChanged }: DepartmentManagementTabProps) {
  const [searchInput, setSearchInput] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [page, setPage] = useState(0);
  const [refreshTick, setRefreshTick] = useState(0);

  const [data, setData] = useState<PageResponse<AdminDepartmentResponse> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [formOpen, setFormOpen] = useState(false);
  const [formMode, setFormMode] = useState<'create' | 'edit'>('create');
  const [editingDept, setEditingDept] = useState<AdminDepartmentResponse | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<AdminDepartmentResponse | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    const trimmed = searchInput.trim();
    if (trimmed === appliedQuery) return;
    const timer = setTimeout(() => {
      setAppliedQuery(trimmed);
      setPage(0);
    }, 250);
    return () => clearTimeout(timer);
  }, [appliedQuery, searchInput]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);

    listAdminDepartmentsPage({ q: appliedQuery || undefined, page, size: 20 }, controller.signal)
      .then((res) => {
        if (controller.signal.aborted) return;
        if (res.items.length === 0 && res.page > 0 && res.totalPages > 0) {
          setPage(res.totalPages - 1);
          return;
        }
        setData(res);
        setLoading(false);
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setError(formatApiErrorMessage(err));
        setLoading(false);
      });

    return () => controller.abort();
  }, [appliedQuery, page, refreshTick]);

  const handleSaved = useCallback(() => {
    setRefreshTick((t) => t + 1);
    onDepartmentsChanged();
  }, [onDepartmentsChanged]);

  const handleConfirmDelete = async () => {
    if (!deleteTarget || deleting) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      await deleteAdminDepartment(deleteTarget.id);
      toast.success(`Đã xóa phòng ban ${deleteTarget.code}.`);
      setDeleteTarget(null);
      handleSaved();
    } catch (err) {
      const msg = formatApiErrorMessage(err);
      setDeleteError(msg);
      toast.error(msg);
    } finally {
      setDeleting(false);
    }
  };

  const items = data?.items ?? [];
  const currentPage = data?.page ?? 0;
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const totalElements = data?.totalElements ?? 0;

  return (
    <Card className="border-slate-200 shadow-sm">
      <CardHeader className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <CardTitle className="text-base">Danh sách Phòng ban</CardTitle>
            <CardDescription>
              Quản lý đơn vị trực thuộc và theo dõi Cache Version của từng phòng ban
            </CardDescription>
          </div>
          <Button
            type="button"
            onClick={() => {
              setFormMode('create');
              setEditingDept(null);
              setFormOpen(true);
            }}
          >
            <Plus className="mr-1.5 h-4 w-4" aria-hidden="true" />
            Tạo phòng ban
          </Button>
        </div>

        <div className="relative max-w-sm">
          <Search
            className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            aria-label="Tìm kiếm phòng ban theo mã hoặc tên"
            placeholder="Tìm mã hoặc tên phòng ban..."
            className="h-9 pl-8"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {error ? (
          <div
            role="alert"
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive"
          >
            <span>{error}</span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setRefreshTick((t) => t + 1)}
            >
              Thử lại
            </Button>
          </div>
        ) : null}

        <div
          tabIndex={0}
          role="region"
          aria-label="Bảng danh sách phòng ban"
          className="max-h-[520px] overflow-auto rounded-md border border-slate-200 focus:outline-none focus:ring-2 focus:ring-ring"
        >
          <Table>
            <TableHeader className="sticky top-0 z-10 bg-slate-50 shadow-sm">
              <TableRow>
                <TableHead scope="col">Mã</TableHead>
                <TableHead scope="col">Tên phòng ban</TableHead>
                <TableHead scope="col">Mô tả</TableHead>
                <TableHead scope="col">Cache Version</TableHead>
                <TableHead scope="col" className="whitespace-nowrap">
                  Ngày tạo
                </TableHead>
                <TableHead scope="col" className="text-right">
                  Thao tác
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="h-24 text-center text-muted-foreground">
                    Đang tải danh sách phòng ban...
                  </TableCell>
                </TableRow>
              ) : items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="h-24 text-center text-muted-foreground">
                    Chưa có phòng ban nào khớp tìm kiếm.
                  </TableCell>
                </TableRow>
              ) : (
                items.map((dept) => (
                  <TableRow key={dept.id}>
                    <TableCell className="whitespace-nowrap font-mono text-xs font-semibold text-slate-900">
                      {dept.code}
                    </TableCell>
                    <TableCell className="font-medium text-slate-900">{dept.name}</TableCell>
                    <TableCell className="max-w-[280px]">
                      <span className="block truncate text-xs text-muted-foreground">
                        {dept.description?.trim() ? dept.description : '—'}
                      </span>
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      <Badge variant="outline">v{dept.cacheVersion}</Badge>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-xs text-slate-600">
                      {new Date(dept.createdAt).toLocaleDateString('vi-VN')}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-right">
                      <div className="inline-flex items-center justify-end gap-1">
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          aria-label={`Chỉnh sửa phòng ban ${dept.code}`}
                          onClick={() => {
                            setFormMode('edit');
                            setEditingDept(dept);
                            setFormOpen(true);
                          }}
                        >
                          <Pencil className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                          Sửa
                        </Button>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          aria-label={`Xóa phòng ban ${dept.code}`}
                          onClick={() => {
                            setDeleteError(null);
                            setDeleteTarget(dept);
                          }}
                        >
                          <Trash2 className="mr-1 h-3.5 w-3.5 text-destructive" aria-hidden="true" />
                          Xóa
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
          <span>
            Trang {currentPage + 1} / {totalPages} ({totalElements.toLocaleString('vi-VN')} phòng
            ban)
          </span>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={loading || currentPage <= 0}
              onClick={() => setPage((p) => p - 1)}
            >
              Trang trước
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={loading || currentPage + 1 >= totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              Trang sau
            </Button>
          </div>
        </div>
      </CardContent>

      <DepartmentFormDialog
        open={formOpen}
        onOpenChange={setFormOpen}
        mode={formMode}
        initialDepartment={editingDept}
        onSaved={handleSaved}
      />

      <Dialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open && !deleting) {
            setDeleteTarget(null);
            setDeleteError(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Xác nhận xóa phòng ban</DialogTitle>
            <DialogDescription>
              Chỉ có thể xóa phòng ban <strong>{deleteTarget?.code}</strong> khi không còn nhân sự
              hay tài liệu nào trực thuộc.
            </DialogDescription>
          </DialogHeader>

          {deleteError ? (
            <div
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-xs text-destructive"
            >
              {deleteError}
            </div>
          ) : null}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={deleting}
              onClick={() => {
                setDeleteTarget(null);
                setDeleteError(null);
              }}
            >
              Hủy
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={deleting}
              onClick={() => void handleConfirmDelete()}
            >
              {deleting ? 'Đang xóa...' : 'Xác nhận xóa'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}
