'use client';

import React, { Suspense, useState } from 'react';
import { LogOut, Menu, ShieldCheck } from 'lucide-react';
import {
  ChatSessionSidebar,
  useChatSessions,
  type ChatSessionsController,
} from '@/components/layout/chat-session-sidebar';
import { RoleNav } from '@/components/layout/role-nav';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet';
import { ROLE_LABELS, type UserProfileResponse } from '@/lib/types/auth';

interface AppShellProps {
  user: UserProfileResponse;
  onLogout: () => Promise<void>;
  children: React.ReactNode;
}

function SidebarContent({
  user,
  sessionsController,
  isLoggingOut,
  onLogout,
  onNavigate,
}: {
  user: UserProfileResponse;
  sessionsController: ChatSessionsController;
  isLoggingOut: boolean;
  onLogout: () => void;
  onNavigate?: () => void;
}) {
  return (
    <div className="flex h-full flex-col gap-4">
      <div className="flex items-center gap-2.5 border-b border-slate-200 pb-3">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <ShieldCheck className="h-5 w-5" aria-hidden="true" />
        </div>
        <div className="min-w-0">
          <div className="truncate text-sm font-bold text-slate-900">DocuMind AI</div>
          <div className="truncate text-xs text-slate-500">Enterprise RAG Workspace</div>
        </div>
      </div>

      <RoleNav role={user.role} onNavigate={onNavigate} />

      <div className="flex min-h-0 flex-1 flex-col border-t border-slate-200 pt-3">
        <Suspense
          fallback={<p className="px-2 text-xs text-muted-foreground">Đang tải phiên chat...</p>}
        >
          <ChatSessionSidebar controller={sessionsController} onSelectSession={onNavigate} />
        </Suspense>
      </div>

      <div className="space-y-2.5 border-t border-slate-200 pt-3">
        <div className="space-y-1">
          <div className="truncate text-sm font-semibold text-slate-900">{user.fullName}</div>
          <div className="truncate text-xs text-slate-500">{user.email}</div>
          <div className="flex flex-wrap items-center gap-1.5 pt-1">
            <Badge variant="secondary">{ROLE_LABELS[user.role]}</Badge>
            {user.departmentCode ? <Badge variant="outline">{user.departmentCode}</Badge> : null}
          </div>
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="w-full justify-start gap-2"
          onClick={onLogout}
          disabled={isLoggingOut}
        >
          <LogOut className="h-4 w-4" aria-hidden="true" />
          <span>{isLoggingOut ? 'Đang đăng xuất...' : 'Đăng xuất'}</span>
        </Button>
      </div>
    </div>
  );
}

export function AppShell({ user, onLogout, children }: AppShellProps) {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const sessionsController = useChatSessions();

  async function handleLogout() {
    if (isLoggingOut) {
      return;
    }
    setIsLoggingOut(true);
    try {
      await onLogout();
    } finally {
      setIsLoggingOut(false);
    }
  }

  return (
    <div className="flex min-h-screen bg-slate-50">
      <aside className="hidden w-72 shrink-0 flex-col border-r border-slate-200 bg-white p-4 md:flex">
        <SidebarContent
          user={user}
          sessionsController={sessionsController}
          isLoggingOut={isLoggingOut}
          onLogout={() => void handleLogout()}
        />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center justify-between border-b border-slate-200 bg-white px-4 md:hidden">
          <div className="flex items-center gap-2">
            <ShieldCheck className="h-5 w-5 text-primary" aria-hidden="true" />
            <span className="font-semibold text-slate-900">DocuMind AI</span>
          </div>

          <Sheet open={mobileOpen} onOpenChange={setMobileOpen}>
            <SheetTrigger asChild>
              <Button type="button" variant="outline" size="icon" aria-label="Mở menu điều hướng">
                <Menu className="h-5 w-5" aria-hidden="true" />
              </Button>
            </SheetTrigger>
            <SheetContent side="left" className="w-80 p-4">
              <SheetHeader className="sr-only">
                <SheetTitle>Menu DocuMind AI</SheetTitle>
                <SheetDescription>Điều hướng và lịch sử hội thoại theo vai trò</SheetDescription>
              </SheetHeader>
              <SidebarContent
                user={user}
                sessionsController={sessionsController}
                isLoggingOut={isLoggingOut}
                onLogout={() => void handleLogout()}
                onNavigate={() => setMobileOpen(false)}
              />
            </SheetContent>
          </Sheet>
        </header>

        <main className="min-w-0 flex-1 p-4 md:p-6">{children}</main>
      </div>
    </div>
  );
}
