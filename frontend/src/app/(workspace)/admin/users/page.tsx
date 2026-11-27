'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { Building2, Users } from 'lucide-react';
import { DepartmentManagementTab } from '@/components/admin/department-management-tab';
import { UserManagementTab } from '@/components/admin/user-management-tab';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { listAdminDepartmentsPage } from '@/lib/api/admin';
import type { AdminDepartmentResponse } from '@/lib/types/admin';
import { formatApiErrorMessage } from '@/lib/types/problem';
import { useAuth } from '@/providers/auth-provider';

export default function AdminUsersPage() {
  const { user } = useAuth();
  const [departments, setDepartments] = useState<AdminDepartmentResponse[]>([]);
  const [deptError, setDeptError] = useState<string | null>(null);
  const [deptVersion, setDeptVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setDeptError(null);
    listAdminDepartmentsPage({ page: 0, size: 100 }, controller.signal)
      .then((res) => {
        if (controller.signal.aborted) return;
        setDepartments(res.items);
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setDeptError(formatApiErrorMessage(err));
      });
    return () => controller.abort();
  }, [deptVersion]);

  const handleDepartmentsChanged = useCallback(() => {
    setDeptVersion((v) => v + 1);
  }, []);

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Nhân sự &amp; Phòng ban
          </h1>
          <p className="text-sm text-muted-foreground">
            Quản lý tài khoản nhân viên, phòng ban và phân quyền RBAC cấp doanh nghiệp
          </p>
        </div>
        <Badge variant="secondary">SYSTEM_ADMIN Only</Badge>
      </div>

      {deptError ? (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive"
        >
          <span>Không thể tải danh sách phòng ban: {deptError}</span>
          <Button type="button" variant="outline" size="sm" onClick={handleDepartmentsChanged}>
            Thử lại
          </Button>
        </div>
      ) : null}

      <Tabs defaultValue="users" className="space-y-4">
        <TabsList>
          <TabsTrigger value="users" className="gap-1.5">
            <Users className="h-4 w-4" aria-hidden="true" />
            Tài khoản Nhân sự
          </TabsTrigger>
          <TabsTrigger value="departments" className="gap-1.5">
            <Building2 className="h-4 w-4" aria-hidden="true" />
            Phòng ban
          </TabsTrigger>
        </TabsList>

        <TabsContent value="users">
          <UserManagementTab currentUserId={user?.id ?? ''} departments={departments} />
        </TabsContent>

        <TabsContent value="departments">
          <DepartmentManagementTab onDepartmentsChanged={handleDepartmentsChanged} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
