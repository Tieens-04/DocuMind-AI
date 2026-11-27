'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { KeyRound, Lock, Pencil, Plus, Search, Unlock } from 'lucide-react';
import { toast } from 'sonner';
import { ResetPasswordDialog } from '@/components/admin/reset-password-dialog';
import { UserFormDialog } from '@/components/admin/user-form-dialog';
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
import { listAdminUsers, updateAdminUser } from '@/lib/api/admin';
import type { AdminDepartmentResponse, AdminUserResponse, PageResponse } from '@/lib/types/admin';
import { ROLE_LABELS, type Role } from '@/lib/types/auth';
import { formatApiErrorMessage } from '@/lib/types/problem';

interface UserManagementTabProps {
  currentUserId: string;
  departments: AdminDepartmentResponse[];
}

export function UserManagementTab({ currentUserId, departments }: UserManagementTabProps) {
  const [searchInput, setSearchInput] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [departmentFilter, setDepartmentFilter] = useState('');
  const [roleFilter, setRoleFilter] = useState<'' | Role>('');
  const [activeFilter, setActiveFilter] = useState<'' | 'true' | 'false'>('');
  const [page, setPage] = useState(0);
  const [refreshTick, setRefreshTick] = useState(0);

  const [data, setData] = useState<PageResponse<AdminUserResponse> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [formOpen, setFormOpen] = useState(false);
  const [formMode, setFormMode] = useState<'create' | 'edit'>('create');
  const [editingUser, setEditingUser] = useState<AdminUserResponse | null>(null);
  const [resetUser, setResetUser] = useState<AdminUserResponse | null>(null);
  const [toggleTarget, setToggleTarget] = useState<AdminUserResponse | null>(null);
  const [toggleError, setToggleError] = useState<string | null>(null);
  const [toggling, setToggling] = useState(false);

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

    listAdminUsers(
      {
        departmentId: departmentFilter || undefined,
        role: roleFilter || undefined,
        isActive: activeFilter === '' ? undefined : activeFilter === 'true',
        q: appliedQuery || undefined,
        page,
        size: 20,
      },
      controller.signal,
    )
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
  }, [activeFilter, appliedQuery, departmentFilter, page, refreshTick, roleFilter]);

  const reload = useCallback(() => {
    setRefreshTick((t) => t + 1);
  }, []);

  const handleConfirmToggleActive = async () => {
    if (!toggleTarget || toggling) return;
    const nextActive = !toggleTarget.isActive;
    setToggling(true);
    setToggleError(null);
    try {
      await updateAdminUser(toggleTarget.id, { isActive: nextActive });
      toast.success(
        nextActive
          ? `Đã mở khóa tài khoản ${toggleTarget.email}.`
          : `Đã khóa tài khoản ${toggleTarget.email} và thu hồi toàn bộ phiên đăng nhập.`,
      );
      setToggleTarget(null);
      reload();
    } catch (err) {
      const msg = formatApiErrorMessage(err);
      setToggleError(msg);
      toast.error(msg);
    } finally {
      setToggling(false);
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
            <CardTitle className="text-base">Danh sách Tài khoản Nhân sự</CardTitle>
            <CardDescription>
              Tổng cộng {totalElements.toLocaleString('vi-VN')} tài khoản · thay đổi quyền hoặc khóa
              sẽ thu hồi JWT tức thì
            </CardDescription>
          </div>
          <Button
            type="button"
            onClick={() => {
              setFormMode('create');
              setEditingUser(null);
              setFormOpen(true);
            }}
          >
            <Plus className="mr-1.5 h-4 w-4" aria-hidden="true" />
            Tạo tài khoản
          </Button>
        </div>

        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              type="search"
              aria-label="Tìm kiếm nhân sự theo email hoặc họ tên"
              placeholder="Tìm email hoặc họ tên..."
              className="h-9 pl-8"
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
            />
          </div>

          <select
            aria-label="Lọc nhân sự theo phòng ban"
            className="h-9 rounded-md border border-input bg-background px-2.5 text-sm"
            value={departmentFilter}
            onChange={(e) => {
              setDepartmentFilter(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Tất cả phòng ban</option>
            {departments.map((dept) => (
              <option key={dept.id} value={dept.id}>
                {dept.code} — {dept.name}
              </option>
            ))}
          </select>

          <select
            aria-label="Lọc nhân sự theo vai trò"
            className="h-9 rounded-md border border-input bg-background px-2.5 text-sm"
            value={roleFilter}
            onChange={(e) => {
              setRoleFilter(e.target.value as '' | Role);
              setPage(0);
            }}
          >
            <option value="">Tất cả vai trò</option>
            <option value="SYSTEM_ADMIN">{ROLE_LABELS.SYSTEM_ADMIN}</option>
            <option value="DEPARTMENT_MANAGER">{ROLE_LABELS.DEPARTMENT_MANAGER}</option>
            <option value="STANDARD_EMPLOYEE">{ROLE_LABELS.STANDARD_EMPLOYEE}</option>
          </select>

          <select
            aria-label="Lọc nhân sự theo trạng thái"
            className="h-9 rounded-md border border-input bg-background px-2.5 text-sm"
            value={activeFilter}
            onChange={(e) => {
              setActiveFilter(e.target.value as '' | 'true' | 'false');
              setPage(0);
            }}
          >
            <option value="">Tất cả trạng thái</option>
            <option value="true">Đang hoạt động</option>
            <option value="false">Đã khóa</option>
          </select>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {error ? (
          <div
            role="alert"
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive"
          >
            <span>{error}</span>
            <Button type="button" variant="outline" size="sm" onClick={reload}>
              Thử lại
            </Button>
          </div>
        ) : null}

        <div
          tabIndex={0}
          role="region"
          aria-label="Bảng tài khoản nhân sự"
          className="max-h-[520px] overflow-auto rounded-md border border-slate-200 focus:outline-none focus:ring-2 focus:ring-ring"
        >
          <Table>
            <TableHeader className="sticky top-0 z-10 bg-slate-50 shadow-sm">
              <TableRow>
                <TableHead scope="col">Nhân sự</TableHead>
                <TableHead scope="col">Vai trò</TableHead>
                <TableHead scope="col">Phòng ban</TableHead>
                <TableHead scope="col">Trạng thái</TableHead>
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
                    Đang tải danh sách nhân sự...
                  </TableCell>
                </TableRow>
              ) : items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="h-24 text-center text-muted-foreground">
                    Không tìm thấy tài khoản nào phù hợp.
                  </TableCell>
                </TableRow>
              ) : (
                items.map((row) => {
                  const isSelf = row.id === currentUserId;
                  return (
                    <TableRow key={row.id}>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-slate-900">{row.fullName}</span>
                          {isSelf ? (
                            <Badge variant="outline" className="text-[10px]">
                              Bạn
                            </Badge>
                          ) : null}
                        </div>
                        <div className="text-xs text-muted-foreground">{row.email}</div>
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-xs">
                        <Badge variant="secondary">{ROLE_LABELS[row.role]}</Badge>
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-xs">
                        {row.departmentCode
                          ? `${row.departmentCode} — ${row.departmentName ?? ''}`
                          : 'Chưa gán'}
                      </TableCell>
                      <TableCell className="whitespace-nowrap">
                        {row.isActive ? (
                          <Badge className="bg-emerald-100 text-emerald-800 hover:bg-emerald-100">
                            Đang hoạt động
                          </Badge>
                        ) : (
                          <Badge variant="destructive">Đã khóa</Badge>
                        )}
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-xs text-slate-600">
                        {new Date(row.createdAt).toLocaleDateString('vi-VN')}
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-right">
                        <div className="inline-flex items-center justify-end gap-1">
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            aria-label={`Chỉnh sửa ${row.email}`}
                            onClick={() => {
                              setFormMode('edit');
                              setEditingUser(row);
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
                            aria-label={`Đặt lại mật khẩu ${row.email}`}
                            onClick={() => setResetUser(row)}
                          >
                            <KeyRound className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                            Đổi MK
                          </Button>
                          <Button
                            type="button"
                            variant={row.isActive ? 'outline' : 'secondary'}
                            size="sm"
                            disabled={isSelf}
                            title={
                              isSelf
                                ? 'Không thể tự vô hiệu hóa tài khoản đang đăng nhập'
                                : row.isActive
                                  ? 'Khóa tài khoản'
                                  : 'Mở khóa tài khoản'
                            }
                            aria-label={`${row.isActive ? 'Khóa' : 'Mở khóa'} ${row.email}`}
                            onClick={() => {
                              setToggleError(null);
                              setToggleTarget(row);
                            }}
                          >
                            {row.isActive ? (
                              <>
                                <Lock className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                                Khóa
                              </>
                            ) : (
                              <>
                                <Unlock className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                                Mở khóa
                              </>
                            )}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
          <span>
            Trang {currentPage + 1} / {totalPages} ({totalElements.toLocaleString('vi-VN')} tài
            khoản)
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

      <UserFormDialog
        open={formOpen}
        onOpenChange={setFormOpen}
        mode={formMode}
        initialUser={editingUser}
        currentUserId={currentUserId}
        departments={departments}
        onSaved={reload}
      />

      <ResetPasswordDialog
        user={resetUser}
        open={resetUser !== null}
        onOpenChange={(open) => {
          if (!open) setResetUser(null);
        }}
      />

      <Dialog
        open={toggleTarget !== null}
        onOpenChange={(open) => {
          if (!open && !toggling) {
            setToggleTarget(null);
            setToggleError(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>
              {toggleTarget?.isActive ? 'Xác nhận khóa tài khoản' : 'Xác nhận mở khóa tài khoản'}
            </DialogTitle>
            <DialogDescription>
              {toggleTarget?.isActive
                ? `Khóa tài khoản ${toggleTarget.email} sẽ thu hồi ngay lập tức toàn bộ Access Token & Refresh Token đang hoạt động.`
                : `Cho phép tài khoản ${toggleTarget?.email} đăng nhập lại vào hệ thống.`}
            </DialogDescription>
          </DialogHeader>
          {toggleError ? (
            <div
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-xs text-destructive"
            >
              {toggleError}
            </div>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={toggling}
              onClick={() => {
                setToggleTarget(null);
                setToggleError(null);
              }}
            >
              Hủy
            </Button>
            <Button
              type="button"
              variant={toggleTarget?.isActive ? 'destructive' : 'default'}
              disabled={toggling}
              onClick={() => void handleConfirmToggleActive()}
            >
              {toggling
                ? 'Đang xử lý...'
                : toggleTarget?.isActive
                  ? 'Xác nhận khóa'
                  : 'Xác nhận mở khóa'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}
