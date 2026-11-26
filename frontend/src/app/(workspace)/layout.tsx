'use client';

import React, { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { AppShell } from '@/components/layout/app-shell';
import { RoleGuard } from '@/components/layout/role-guard';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { useAuth } from '@/providers/auth-provider';

export default function WorkspaceLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const { user, status, bootstrapError, logout, retryBootstrap } = useAuth();

  useEffect(() => {
    if (status === 'unauthenticated') {
      const search = typeof window !== 'undefined' ? window.location.search : '';
      const currentPath = `${pathname || '/chat'}${search}`;
      const nextParam = currentPath !== '/chat' ? `?next=${encodeURIComponent(currentPath)}` : '';
      router.replace(`/login${nextParam}`);
    }
  }, [pathname, router, status]);

  if (status === 'error') {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 p-4">
        <Card className="w-full max-w-md border-slate-200 shadow-sm">
          <CardHeader>
            <CardTitle className="text-lg">Không thể xác thực phiên làm việc</CardTitle>
            <CardDescription>
              Máy chủ xác thực tạm thời gián đoạn. Phiên đăng nhập của bạn vẫn được giữ an toàn.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-destructive">
              {bootstrapError ?? 'Không thể kết nối tới máy chủ DocuMind AI.'}
            </p>
          </CardContent>
          <CardFooter className="flex items-center gap-2">
            <Button type="button" onClick={() => void retryBootstrap()}>
              Thử lại
            </Button>
            <Button type="button" variant="outline" onClick={() => void logout()}>
              Đăng xuất
            </Button>
          </CardFooter>
        </Card>
      </div>
    );
  }

  if (status === 'loading' || !user) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50">
        <p className="text-sm text-muted-foreground">Đang xác thực phiên làm việc...</p>
      </div>
    );
  }

  return (
    <AppShell key={user.id} user={user} onLogout={logout}>
      <RoleGuard user={user}>{children}</RoleGuard>
    </AppShell>
  );
}
