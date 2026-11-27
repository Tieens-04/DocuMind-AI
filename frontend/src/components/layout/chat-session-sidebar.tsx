'use client';

import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { MessageSquarePlus, Pencil, RefreshCw, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
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
  createChatSession,
  deleteChatSession,
  listChatSessions,
  renameChatSession,
} from '@/lib/api/chat-sessions';
import { hasStoredSession } from '@/lib/auth/token-manager';
import type { ChatSessionResponse } from '@/lib/types/chat';
import { ApiError, formatApiErrorMessage } from '@/lib/types/problem';
import { cn } from '@/lib/utils';

export interface ChatSessionsController {
  sessions: ChatSessionResponse[];
  loading: boolean;
  loadError: string | null;
  creating: boolean;
  loadSessions: (options?: { silent?: boolean }) => Promise<void>;
  createSession: () => Promise<ChatSessionResponse | null>;
  renameSession: (sessionId: string, title: string) => Promise<ChatSessionResponse | null>;
  deleteSession: (sessionId: string) => Promise<boolean>;
}

const ChatSessionsContext = createContext<ChatSessionsController | null>(null);

export function ChatSessionsProvider({
  value,
  children,
}: {
  value: ChatSessionsController;
  children: React.ReactNode;
}) {
  return <ChatSessionsContext.Provider value={value}>{children}</ChatSessionsContext.Provider>;
}

export function useOptionalChatSessions(): ChatSessionsController | null {
  return useContext(ChatSessionsContext);
}

function shouldSuppressExpiredToast(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401 && !hasStoredSession();
}

export function useChatSessions(options?: { enabled?: boolean }): ChatSessionsController {
  const enabled = options?.enabled !== false;
  const [sessions, setSessions] = useState<ChatSessionResponse[]>([]);
  const [loading, setLoading] = useState(enabled);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef<AbortController | null>(null);

  const loadSessions = useCallback(async (loadOptions?: { silent?: boolean }) => {
    abortControllerRef.current?.abort();
    const controller = new AbortController();
    abortControllerRef.current = controller;
    const seq = ++requestSeqRef.current;
    const silent = loadOptions?.silent === true;

    if (!silent) {
      setLoading(true);
      setLoadError(null);
    }
    try {
      const page = await listChatSessions(0, 50, controller.signal);
      if (seq !== requestSeqRef.current || controller.signal.aborted) {
        return;
      }
      setLoadError(null);
      setSessions(page.items);
    } catch (error) {
      if (controller.signal.aborted || seq !== requestSeqRef.current) {
        return;
      }
      if (!silent) {
        setLoadError(formatApiErrorMessage(error));
      }
    } finally {
      if (seq === requestSeqRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    if (enabled) {
      void loadSessions();
    }
    return () => {
      abortControllerRef.current?.abort();
    };
  }, [enabled, loadSessions]);

  const createSession = useCallback(async (): Promise<ChatSessionResponse | null> => {
    setCreating(true);
    try {
      const created = await createChatSession();
      requestSeqRef.current += 1;
      abortControllerRef.current?.abort();
      setLoading(false);
      setSessions((prev) => [created, ...prev]);
      return created;
    } catch (error) {
      if (!shouldSuppressExpiredToast(error)) {
        toast.error(formatApiErrorMessage(error));
      }
      return null;
    } finally {
      setCreating(false);
    }
  }, []);

  const renameSession = useCallback(
    async (sessionId: string, title: string): Promise<ChatSessionResponse | null> => {
      try {
        const updated = await renameChatSession(sessionId, title);
        requestSeqRef.current += 1;
        abortControllerRef.current?.abort();
        setLoading(false);
        setSessions((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
        toast.success('Đã đổi tên cuộc hội thoại.');
        return updated;
      } catch (error) {
        if (!shouldSuppressExpiredToast(error)) {
          toast.error(formatApiErrorMessage(error));
        }
        return null;
      }
    },
    [],
  );

  const deleteSession = useCallback(async (sessionId: string): Promise<boolean> => {
    try {
      await deleteChatSession(sessionId);
      requestSeqRef.current += 1;
      abortControllerRef.current?.abort();
      setLoading(false);
      setSessions((prev) => prev.filter((item) => item.id !== sessionId));
      toast.success('Đã xóa cuộc hội thoại.');
      return true;
    } catch (error) {
      if (!shouldSuppressExpiredToast(error)) {
        toast.error(formatApiErrorMessage(error));
      }
      return false;
    }
  }, []);

  return {
    sessions,
    loading,
    loadError,
    creating,
    loadSessions,
    createSession,
    renameSession,
    deleteSession,
  };
}

interface ChatSessionSidebarProps {
  controller?: ChatSessionsController;
  onSelectSession?: () => void;
}

export function ChatSessionSidebar({ controller, onSelectSession }: ChatSessionSidebarProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const activeSessionId = searchParams.get('session');

  const fallbackController = useChatSessions({ enabled: !controller });
  const {
    sessions,
    loading,
    loadError,
    creating,
    loadSessions,
    createSession,
    renameSession,
    deleteSession,
  } = controller ?? fallbackController;

  const [renameTarget, setRenameTarget] = useState<ChatSessionResponse | null>(null);
  const [renameTitle, setRenameTitle] = useState('');
  const [renaming, setRenaming] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<ChatSessionResponse | null>(null);
  const [deleting, setDeleting] = useState(false);

  async function handleCreateSession() {
    const created = await createSession();
    if (created) {
      router.push(`/chat?session=${created.id}`);
      onSelectSession?.();
    }
  }

  function handleNavigateSession(sessionId: string) {
    router.push(`/chat?session=${sessionId}`);
    onSelectSession?.();
  }

  function openRenameDialog(session: ChatSessionResponse) {
    setRenameTarget(session);
    setRenameTitle(session.title);
  }

  async function handleConfirmRename(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!renameTarget) return;
    const trimmed = renameTitle.trim();
    if (!trimmed || trimmed.length > 255) {
      toast.error('Tên cuộc hội thoại phải từ 1 đến 255 ký tự.');
      return;
    }

    setRenaming(true);
    try {
      const updated = await renameSession(renameTarget.id, trimmed);
      if (updated) {
        setRenameTarget(null);
      }
    } finally {
      setRenaming(false);
    }
  }

  async function handleConfirmDelete() {
    if (!deleteTarget) return;
    const deletingId = deleteTarget.id;
    setDeleting(true);
    try {
      const deleted = await deleteSession(deletingId);
      if (deleted) {
        setDeleteTarget(null);
        if (pathname === '/chat' && activeSessionId === deletingId) {
          router.replace('/chat');
        }
      }
    } finally {
      setDeleting(false);
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2">
      <div className="flex items-center justify-between px-1">
        <h2 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
          Lịch sử hội thoại
        </h2>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-7 w-7"
          onClick={() => void loadSessions()}
          aria-label="Tải lại danh sách hội thoại"
          disabled={loading}
        >
          <RefreshCw className={cn('h-3.5 w-3.5', loading && 'animate-spin')} aria-hidden="true" />
        </Button>
      </div>

      <Button
        type="button"
        variant="outline"
        size="sm"
        className="w-full justify-start gap-2 border-dashed"
        onClick={() => void handleCreateSession()}
        disabled={creating}
      >
        <MessageSquarePlus className="h-4 w-4 text-primary" aria-hidden="true" />
        <span>{creating ? 'Đang tạo...' : 'Cuộc hội thoại mới'}</span>
      </Button>

      <div className="min-h-0 flex-1 overflow-y-auto pr-1">
        {loading ? (
          <p className="px-2 py-4 text-xs text-muted-foreground">Đang tải lịch sử...</p>
        ) : loadError ? (
          <div className="space-y-2 rounded-md border border-destructive/30 bg-destructive/5 p-2.5 text-xs">
            <p className="text-destructive">{loadError}</p>
            <Button type="button" variant="outline" size="sm" onClick={() => void loadSessions()}>
              Thử lại
            </Button>
          </div>
        ) : sessions.length === 0 ? (
          <p className="px-2 py-4 text-xs text-muted-foreground">
            Chưa có cuộc hội thoại nào. Tạo phiên mới để bắt đầu hỏi đáp.
          </p>
        ) : (
          <ul className="space-y-1">
            {sessions.map((session) => {
              const isSelected = pathname === '/chat' && activeSessionId === session.id;
              return (
                <li
                  key={session.id}
                  className={cn(
                    'group flex items-center justify-between gap-1 rounded-md px-2 py-1 text-sm transition-colors',
                    isSelected
                      ? 'bg-slate-200/80 font-medium text-slate-900'
                      : 'text-slate-700 hover:bg-slate-100',
                  )}
                >
                  <button
                    type="button"
                    onClick={() => handleNavigateSession(session.id)}
                    aria-current={isSelected ? 'true' : undefined}
                    className="min-w-0 flex-1 truncate py-1 text-left"
                    title={session.title}
                  >
                    {session.title}
                  </button>
                  <div className="flex shrink-0 items-center gap-0.5">
                    <button
                      type="button"
                      onClick={() => openRenameDialog(session)}
                      aria-label={`Đổi tên ${session.title}`}
                      className="flex h-7 w-7 items-center justify-center rounded text-slate-500 hover:bg-slate-200 hover:text-slate-800"
                    >
                      <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      onClick={() => setDeleteTarget(session)}
                      aria-label={`Xóa ${session.title}`}
                      className="flex h-7 w-7 items-center justify-center rounded text-slate-500 hover:bg-destructive/10 hover:text-destructive"
                    >
                      <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <Dialog
        open={Boolean(renameTarget)}
        onOpenChange={(open) => {
          if (!open && !renaming) {
            setRenameTarget(null);
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Đổi tên cuộc hội thoại</DialogTitle>
            <DialogDescription>
              Nhập tiêu đề mới giúp bạn dễ nhận diện chủ đề trao đổi.
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleConfirmRename} className="space-y-4">
            <div className="space-y-1.5">
              <label htmlFor="rename-session-title" className="text-sm font-medium">
                Tiêu đề
              </label>
              <Input
                id="rename-session-title"
                value={renameTitle}
                onChange={(event) => setRenameTitle(event.target.value)}
                maxLength={255}
                required
                autoFocus
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setRenameTarget(null)}
                disabled={renaming}
              >
                Hủy
              </Button>
              <Button type="submit" disabled={renaming}>
                {renaming ? 'Đang lưu...' : 'Lưu tên mới'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open && !deleting) {
            setDeleteTarget(null);
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Xác nhận xóa cuộc hội thoại</DialogTitle>
            <DialogDescription>
              Bạn có chắc chắn muốn xóa phiên &ldquo;{deleteTarget?.title}&rdquo;? Toàn bộ lịch sử
              tin nhắn trong phiên này sẽ bị xóa vĩnh viễn.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setDeleteTarget(null)}
              disabled={deleting}
            >
              Hủy
            </Button>
            <Button
              type="button"
              variant="destructive"
              onClick={() => void handleConfirmDelete()}
              disabled={deleting}
            >
              {deleting ? 'Đang xóa...' : 'Xóa hội thoại'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
