'use client';

import React, { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import { MessageSquare, ShieldCheck } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { ROLE_LABELS } from '@/lib/types/auth';
import { useAuth } from '@/providers/auth-provider';

function ChatWorkspaceContent() {
  const searchParams = useSearchParams();
  const sessionId = searchParams.get('session');
  const { user } = useAuth();

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Trợ lý Hỏi đáp Tri thức
          </h1>
          <p className="text-sm text-muted-foreground">
            Truy xuất ngữ nghĩa RAG có lọc phân quyền tự động từ tài liệu nội bộ
          </p>
        </div>
        {user ? (
          <div className="flex items-center gap-2">
            <Badge variant="secondary">{ROLE_LABELS[user.role]}</Badge>
            {user.departmentName ? <Badge variant="outline">{user.departmentName}</Badge> : null}
          </div>
        ) : null}
      </div>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2 text-primary">
            <MessageSquare className="h-5 w-5" aria-hidden="true" />
            <CardTitle className="text-lg">
              {sessionId ? `Phiên hội thoại: ${sessionId}` : 'Sẵn sàng đặt câu hỏi mới'}
            </CardTitle>
          </div>
          <CardDescription>
            {sessionId
              ? 'Bạn đang chọn một phiên hội thoại từ Sidebar. Luồng SSE streaming, Markdown và thẻ trích dẫn sẽ được tích hợp ở DA-22.'
              : 'Chọn một cuộc hội thoại ở thanh bên trái hoặc bấm "Cuộc hội thoại mới" để bắt đầu.'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-start gap-3 rounded-md border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
            <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
            <p>
              Phạm vi tài liệu được khóa chặt theo phòng ban{' '}
              <strong>{user?.departmentCode ?? 'N/A'}</strong> và vai trò{' '}
              <strong>{user?.role}</strong> trên tầng Vector RBAC của Backend.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

export default function ChatPage() {
  return (
    <Suspense
      fallback={<p className="text-sm text-muted-foreground">Đang tải không gian chat...</p>}
    >
      <ChatWorkspaceContent />
    </Suspense>
  );
}
