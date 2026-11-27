'use client';

import React, { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { ShieldCheck } from 'lucide-react';
import { toast } from 'sonner';
import { ChatComposer } from '@/components/chat/chat-composer';
import { ChatMessageList } from '@/components/chat/chat-message-list';
import { CitationDrawer } from '@/components/chat/citation-drawer';
import { useOptionalChatSessions } from '@/components/layout/chat-session-sidebar';
import { Badge } from '@/components/ui/badge';
import {
  listAdminDepartments,
  listChatMessages,
  streamChatAnswer,
} from '@/lib/api/chat-stream';
import { hasStoredSession } from '@/lib/auth/token-manager';
import { ROLE_LABELS } from '@/lib/types/auth';
import {
  REDACTED_ASSISTANT_CONTENT,
  type AdminDepartmentOption,
  type ChatMessageResponse,
  type ChatUiMessage,
  type CitationDto,
} from '@/lib/types/chat';
import { ApiError, formatApiErrorMessage } from '@/lib/types/problem';
import { useAuth } from '@/providers/auth-provider';

function mapHistoryMessage(message: ChatMessageResponse): ChatUiMessage {
  return {
    id: message.id,
    role: message.role,
    content: message.content,
    rewrittenQuery: message.rewrittenQuery,
    citations: message.citations,
    abstained: message.abstained,
    redacted:
      message.role === 'ASSISTANT' && message.content === REDACTED_ASSISTANT_CONTENT,
    promptTokens: message.promptTokens,
    completionTokens: message.completionTokens,
    totalTokens: message.promptTokens + message.completionTokens,
    latencyMs: message.latencyMs,
    status: 'completed',
  };
}

function createTempId(prefix: string): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `${prefix}-${crypto.randomUUID()}`;
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function ChatWorkspaceContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const sessionId = searchParams.get('session');
  const { user } = useAuth();
  const sessionsContext = useOptionalChatSessions();
  const isAdmin = user?.role === 'SYSTEM_ADMIN';

  const [messages, setMessages] = useState<ChatUiMessage[]>([]);
  const [questionInput, setQuestionInput] = useState('');
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [isStreaming, setIsStreaming] = useState(false);
  const [selectedCitation, setSelectedCitation] = useState<CitationDto | null>(null);
  const [departments, setDepartments] = useState<AdminDepartmentOption[]>([]);
  const [selectedDepartmentId, setSelectedDepartmentId] = useState('');
  const [rateLimitSeconds, setRateLimitSeconds] = useState(0);

  const streamAbortRef = useRef<AbortController | null>(null);
  const historyAbortRef = useRef<AbortController | null>(null);
  const historySeqRef = useRef(0);
  // Skip reloading GET /messages when Turn 1 updates ?session= right after stream completion
  const justCreatedSessionIdRef = useRef<string | null>(null);
  const activeRunIdRef = useRef(0);
  const stopRequestedRunIdRef = useRef<number | null>(null);
  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const isNearBottomRef = useRef(true);

  const loadSessionHistory = useCallback(async (targetSessionId: string) => {
    historyAbortRef.current?.abort();
    const controller = new AbortController();
    historyAbortRef.current = controller;
    const seq = ++historySeqRef.current;

    setIsLoadingHistory(true);
    setHistoryError(null);
    try {
      const items = await listChatMessages(targetSessionId, controller.signal);
      if (seq !== historySeqRef.current || controller.signal.aborted) {
        return;
      }
      setMessages(items.map(mapHistoryMessage));
      isNearBottomRef.current = true;
    } catch (error) {
      if (controller.signal.aborted || seq !== historySeqRef.current) {
        return;
      }
      setHistoryError(formatApiErrorMessage(error));
    } finally {
      if (seq === historySeqRef.current) {
        setIsLoadingHistory(false);
      }
    }
  }, []);

  useEffect(() => {
    if (!isAdmin) {
      setDepartments([]);
      setSelectedDepartmentId('');
      return;
    }

    const controller = new AbortController();
    void listAdminDepartments(controller.signal)
      .then((page) => {
        if (!controller.signal.aborted) {
          setDepartments(page.items);
        }
      })
      .catch(() => {
        // Admin can still query across all departments if department list fails to load
      });

    return () => {
      controller.abort();
    };
  }, [isAdmin]);

  const isRateLimited = rateLimitSeconds > 0;

  useEffect(() => {
    if (!isRateLimited) {
      return;
    }
    const timer = window.setInterval(() => {
      setRateLimitSeconds((prev) => (prev <= 1 ? 0 : prev - 1));
    }, 1000);
    return () => {
      window.clearInterval(timer);
    };
  }, [isRateLimited]);

  useEffect(() => {
    if (sessionId && justCreatedSessionIdRef.current === sessionId) {
      justCreatedSessionIdRef.current = null;
      return;
    }
    justCreatedSessionIdRef.current = null;

    activeRunIdRef.current += 1;
    stopRequestedRunIdRef.current = null;
    streamAbortRef.current?.abort();
    streamAbortRef.current = null;
    setIsStreaming(false);
    setSelectedCitation(null);

    if (!sessionId) {
      historySeqRef.current += 1;
      historyAbortRef.current?.abort();
      setIsLoadingHistory(false);
      setHistoryError(null);
      setMessages([]);
      return;
    }

    void loadSessionHistory(sessionId);
  }, [loadSessionHistory, sessionId]);

  useEffect(() => {
    return () => {
      streamAbortRef.current?.abort();
      historyAbortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    const container = scrollContainerRef.current;
    if (container && isNearBottomRef.current) {
      container.scrollTop = container.scrollHeight;
    }
  }, [messages]);

  function handleMessagesScroll() {
    const container = scrollContainerRef.current;
    if (!container) {
      return;
    }
    const distanceFromBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight;
    isNearBottomRef.current = distanceFromBottom < 80;
  }

  function handleStopStream() {
    stopRequestedRunIdRef.current = activeRunIdRef.current;
    streamAbortRef.current?.abort();
  }

  async function sendQuestion(
    rawQuestion: string,
    options?: { clearInput?: boolean; replaceAssistantId?: string },
  ) {
    const trimmed = rawQuestion.trim();
    if (
      !trimmed ||
      isStreaming ||
      streamAbortRef.current !== null ||
      isLoadingHistory ||
      rateLimitSeconds > 0
    ) {
      return;
    }

    const controller = new AbortController();
    streamAbortRef.current = controller;

    if (options?.clearInput) {
      setQuestionInput('');
    }

    const userMsgId = createTempId('user');
    const assistantMsgId = createTempId('assistant');
    const runId = ++activeRunIdRef.current;
    stopRequestedRunIdRef.current = null;

    setIsStreaming(true);
    isNearBottomRef.current = true;

    setMessages((prev) => {
      let base = prev;
      if (options?.replaceAssistantId) {
        const idx = prev.findIndex((item) => item.id === options.replaceAssistantId);
        if (idx !== -1) {
          const removeIds = new Set([options.replaceAssistantId]);
          if (idx > 0 && prev[idx - 1].role === 'USER') {
            removeIds.add(prev[idx - 1].id);
          }
          base = prev.filter((item) => !removeIds.has(item.id));
        }
      }
      return [
        ...base,
        {
          id: userMsgId,
          role: 'USER',
          content: trimmed,
          citations: [],
          status: 'completed',
        },
        {
          id: assistantMsgId,
          role: 'ASSISTANT',
          content: '',
          citations: [],
          status: 'searching',
          sourceQuestion: trimmed,
        },
      ];
    });

    try {
      await streamChatAnswer(
        {
          question: trimmed,
          sessionId: sessionId ?? justCreatedSessionIdRef.current,
          departmentId: isAdmin && selectedDepartmentId ? selectedDepartmentId : null,
        },
        {
          onCitations: (event) => {
            if (activeRunIdRef.current !== runId) {
              return;
            }
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantMsgId
                  ? {
                      ...msg,
                      citations: event.citations,
                      rewrittenQuery: event.rewrittenQuery,
                      status: 'streaming',
                    }
                  : msg,
              ),
            );
          },
          onToken: (event) => {
            if (activeRunIdRef.current !== runId) {
              return;
            }
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantMsgId
                  ? {
                      ...msg,
                      content: msg.content + event.delta,
                      status: 'streaming',
                    }
                  : msg,
              ),
            );
          },
          onDone: (event) => {
            if (activeRunIdRef.current !== runId) {
              return;
            }
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantMsgId
                  ? {
                      ...msg,
                      citations: event.abstained ? [] : msg.citations,
                      abstained: event.abstained,
                      cacheTier: event.cacheTier,
                      promptTokens: event.tokenUsage.promptTokens,
                      completionTokens: event.tokenUsage.completionTokens,
                      totalTokens: event.tokenUsage.totalTokens,
                      latencyMs: event.latencyMs,
                      status: 'completed',
                    }
                  : msg,
              ),
            );

            void sessionsContext?.loadSessions({ silent: true });
            if (event.sessionId && event.sessionId !== sessionId) {
              justCreatedSessionIdRef.current = event.sessionId;
              router.replace(`/chat?session=${encodeURIComponent(event.sessionId)}`);
            }
          },
          onErrorEvent: (event) => {
            if (activeRunIdRef.current !== runId) {
              return;
            }
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantMsgId
                  ? {
                      ...msg,
                      status: 'error',
                      errorMessage: event.message,
                      retryable: event.retryable,
                    }
                  : msg,
              ),
            );
          },
        },
        controller.signal,
      );
    } catch (error) {
      if (activeRunIdRef.current !== runId) {
        return;
      }

      if (controller.signal.aborted) {
        if (stopRequestedRunIdRef.current === runId) {
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMsgId ? { ...msg, status: 'stopped' } : msg,
            ),
          );
        }
        return;
      }

      if (error instanceof ApiError && error.status === 429) {
        const retrySeconds = Math.max(1, error.retryAfterSeconds ?? 60);
        setRateLimitSeconds(retrySeconds);
        setMessages((prev) =>
          prev.filter((msg) => msg.id !== userMsgId && msg.id !== assistantMsgId),
        );
        setQuestionInput(trimmed);
        return;
      }

      if (error instanceof ApiError && error.status === 401 && !hasStoredSession()) {
        return;
      }

      const errorMessage = formatApiErrorMessage(error);
      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantMsgId
            ? {
                ...msg,
                status: 'error',
                errorMessage,
                retryable: !(error instanceof ApiError && error.status === 403),
              }
            : msg,
        ),
      );
      toast.error(errorMessage);
    } finally {
      if (activeRunIdRef.current === runId) {
        setIsStreaming(false);
        streamAbortRef.current = null;
      }
    }
  }

  function handleRetryMessage(message: ChatUiMessage) {
    if (!message.sourceQuestion) {
      return;
    }
    void sendQuestion(message.sourceQuestion, { replaceAssistantId: message.id });
  }

  const selectedDept =
    isAdmin && selectedDepartmentId
      ? (departments.find((dept) => dept.id === selectedDepartmentId) ?? null)
      : null;
  const scopeSummaryLabel = isAdmin
    ? selectedDept
      ? `phòng ban ${selectedDept.name} (${selectedDept.code})`
      : 'Toàn hệ thống'
    : `phòng ban ${user?.departmentCode ?? 'N/A'}`;

  return (
    <div className="mx-auto flex h-[calc(100vh-5.5rem)] max-w-5xl flex-col gap-4 md:h-[calc(100vh-3rem)]">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-3">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-slate-900 sm:text-2xl">
            Trợ lý Hỏi đáp Tri thức
          </h1>
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground sm:text-sm">
            <ShieldCheck className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
            <span>
              Truy xuất ngữ nghĩa RAG khóa chặt theo phạm vi{' '}
              <strong>{scopeSummaryLabel}</strong>
            </span>
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {isAdmin ? (
            <div className="flex items-center gap-2">
              <label
                htmlFor="admin-department-scope"
                className="text-xs font-medium text-slate-600"
              >
                Phạm vi:
              </label>
              <select
                id="admin-department-scope"
                value={selectedDepartmentId}
                onChange={(event) => setSelectedDepartmentId(event.target.value)}
                disabled={isStreaming}
                className="h-9 rounded-md border border-slate-200 bg-white px-2.5 text-xs font-medium text-slate-800 shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60"
              >
                <option value="">Toàn hệ thống</option>
                {departments.map((dept) => (
                  <option key={dept.id} value={dept.id}>
                    {dept.name} ({dept.code})
                  </option>
                ))}
              </select>
            </div>
          ) : null}

          {user ? (
            <div className="flex items-center gap-1.5">
              <Badge variant="secondary">{ROLE_LABELS[user.role]}</Badge>
              {user.departmentName ? <Badge variant="outline">{user.departmentName}</Badge> : null}
            </div>
          ) : null}
        </div>
      </div>

      <div
        ref={scrollContainerRef}
        onScroll={handleMessagesScroll}
        className="flex min-h-0 flex-1 flex-col overflow-y-auto pr-1"
      >
        <ChatMessageList
          messages={messages}
          isLoadingHistory={isLoadingHistory}
          historyError={historyError}
          isStreaming={isStreaming}
          isRateLimited={isRateLimited}
          onSelectStarter={(prompt) => void sendQuestion(prompt)}
          onSelectCitation={setSelectedCitation}
          onRetryMessage={handleRetryMessage}
          onReloadHistory={() => {
            if (sessionId) {
              void loadSessionHistory(sessionId);
            }
          }}
        />
      </div>

      <div className="shrink-0 border-t border-slate-200 pt-3">
        <ChatComposer
          value={questionInput}
          onChange={setQuestionInput}
          onSubmit={() => void sendQuestion(questionInput, { clearInput: true })}
          onStop={handleStopStream}
          isStreaming={isStreaming}
          disabled={isLoadingHistory}
          rateLimitSeconds={rateLimitSeconds}
        />
      </div>

      <CitationDrawer citation={selectedCitation} onClose={() => setSelectedCitation(null)} />
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
