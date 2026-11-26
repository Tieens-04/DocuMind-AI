import React from 'react';
import Link from 'next/link';
import { ShieldAlert } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
} from '@/components/ui/card';
import { ROLE_LABELS, type Role } from '@/lib/types/auth';

interface ForbiddenViewProps {
  role: Role;
  attemptedPath: string;
}

export function ForbiddenView({ role, attemptedPath }: ForbiddenViewProps) {
  return (
    <div className="flex min-h-[60vh] items-center justify-center p-6">
      <Card className="w-full max-w-lg border-destructive/30">
        <CardHeader className="space-y-2">
          <div className="flex items-center gap-2 text-destructive">
            <ShieldAlert className="h-5 w-5" aria-hidden="true" />
            <Badge variant="destructive">403 Forbidden</Badge>
          </div>
          <h1 className="text-xl font-semibold leading-none tracking-tight">
            Không đủ quyền truy cập
          </h1>
          <CardDescription>
            Vai trò hiện tại của bạn không được phép truy cập đường dẫn{' '}
            <code className="rounded bg-muted px-1.5 py-0.5 text-xs">{attemptedPath}</code>.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-2 text-sm text-slate-600">
            <span>Quyền hạn hiện tại:</span>
            <Badge variant="secondary">{ROLE_LABELS[role]}</Badge>
          </div>
        </CardContent>
        <CardFooter>
          <Button asChild>
            <Link href="/chat">Quay về Trợ lý Chat</Link>
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}
