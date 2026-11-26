import React from 'react';
import { Users } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

export default function AdminUsersPage() {
  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Nhân sự & Phòng ban
          </h1>
          <p className="text-sm text-muted-foreground">
            Quản lý tài khoản nhân viên, phòng ban và phân quyền RBAC cấp doanh nghiệp
          </p>
        </div>
        <Badge variant="secondary">SYSTEM_ADMIN Only</Badge>
      </div>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2 text-primary">
            <Users className="h-5 w-5" aria-hidden="true" />
            <CardTitle className="text-lg">Quản trị Người dùng & Phòng ban (DA-24)</CardTitle>
          </div>
          <CardDescription>
            Route được bảo vệ độc quyền cho vai trò SYSTEM_ADMIN; thao tác tạo/cập nhật tài khoản
            và phòng ban sẽ được hoàn thiện trong DA-24.
          </CardDescription>
        </CardHeader>
      </Card>
    </div>
  );
}
