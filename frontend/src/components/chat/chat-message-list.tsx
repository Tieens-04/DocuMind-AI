'use client';

import React from 'react';
import {
  AlertTriangle,
  Bot,
  Loader2,
  RotateCcw,
  ShieldAlert,
  Sparkles,
  User as UserIcon,
  Zap,
} from 'lucide-react';
import { CitationCards } from '@/components/chat/citation-cards';
import { MarkdownContent } from '@/components/chat/markdown-content';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  REDACTED_ASSISTANT_CONTENT,
  SAFE_REFUSAL_MESSAGE,
  type ChatUiMessage,
  type CitationDto,
} from '@/lib/types/chat';

export const STARTER_PROMPTS = [
  'Quy trình xin nghỉ phép và chế độ phúc lợi nhân viên hiện tại là gì?',
  'Chính sách bảo mật dữ liệu và phân quyền truy cập tài liệu nội bộ quy định thế nào?',
  'Hướng dẫn onboarding kỹ thuật cho nhân sự mới trong tuần đầu tiên?',
  'Tóm tắt các quy định làm việc từ xa và báo cáo công việc của công ty.',
];

interface ChatMessageListProps {
  messages: ChatUiMessage[];
  isLoadingHistory: boolean;
  historyError: string | null;
  isStreaming: boolean;
  isRateLimited?: boolean;
  onSelectStarter: (question: string) => void;
  onSelectCitation: (citation: CitationDto) => void;
  onRetryMessage: (message: ChatUiMessage) => void;
  onReloadHistory: () => void;
}

function formatLatency(latencyMs: number | null | undefined): string | null {
  if (latencyMs == null || latencyMs < 0) {
    return null;
  }
  if (latencyMs < 1000) {
    return `${latencyMs}ms`;
  }
  return `${(latencyMs / 1000).toFixed(1)}s`;
}

function renderTelemetryBadges(message: ChatUiMessage) {
  if (message.status !== 'completed' || message.redacted) {
    return null;
  }

  const latencyLabel = formatLatency(message.latencyMs);
  const promptTokens = message.promptTokens ?? 0;
  const completionTokens = message.completionTokens ?? 0;
  const totalTokens = message.totalTokens ?? promptTokens + completionTokens;

  let cacheBadgeLabel: string | null = null;
  let cacheBadgeClass = 'bg-slate-100 text-slate-600 border-slate-200';
  if (message.cacheTier === 'L1') {
    cacheBadgeLabel = latencyLabel ? `Redis L1 · ${latencyLabel}` : 'Redis L1';
    cacheBadgeClass = 'bg-emerald-50 text-emerald-700 border-emerald-200';
  } else if (message.cacheTier === 'L2') {
    cacheBadgeLabel = latencyLabel ? `Semantic L2 · ${latencyLabel}` : 'Semantic L2';
    cacheBadgeClass = 'bg-sky-50 text-sky-700 border-sky-200';
  } else if (latencyLabel) {
    cacheBadgeLabel = latencyLabel;
  }

  if (!cacheBadgeLabel && totalTokens <= 0) {
    return null;
  }

  return (
    <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-slate-500">
      {cacheBadgeLabel ? (
        <span
          className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 font-medium ${cacheBadgeClass}`}
        >
          {message.cacheTier === 'L1' || message.cacheTier === 'L2' ? (
            <Zap className="h-3 w-3" aria-hidden="true" />
          ) : null}
          <span>{cacheBadgeLabel}</span>
        </span>
      ) : null}

      {totalTokens > 0 ? (
        <span
          title={`Prompt: ${promptTokens} · Completion: ${completionTokens}`}
          aria-label={`Tổng ${totalTokens} tokens (Prompt ${promptTokens}, Completion ${completionTokens})`}
          className="inline-flex items-center rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 font-medium text-slate-600"
        >
          {totalTokens} tokens
        </span>
      ) : null}
    </div>
  );
}

export function ChatMessageList({
  messages,
  isLoadingHistory,
  historyError,
  isStreaming,
  isRateLimited = false,
  onSelectStarter,
  onSelectCitation,
  onRetryMessage,
  onReloadHistory,
}: ChatMessageListProps) {
  if (isLoadingHistory) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 py-12 text-sm text-slate-500">
        <Loader2 className="h-5 w-5 animate-spin text-primary" aria-hidden="true" />
        <p>Đang tải lịch sử hội thoại...</p>
      </div>
    );
  }

  if (historyError) {
    return (
      <div
        role="alert"
        className="mx-auto my-8 max-w-lg space-y-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"
      >
        <p>{historyError}</p>
        <Button type="button" variant="outline" size="sm" onClick={onReloadHistory}>
          <RotateCcw className="h-4 w-4" aria-hidden="true" />
          <span>Thử tải lại</span>
        </Button>
      </div>
    );
  }

  if (messages.length === 0) {
    return (
      <div className="mx-auto flex max-w-2xl flex-1 flex-col justify-center gap-6 py-8">
        <div className="space-y-2 text-center">
          <div className="mx-auto flex h-11 w-11 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <Sparkles className="h-5 w-5" aria-hidden="true" />
          </div>
          <h2 className="text-lg font-semibold text-slate-900">
            Hỏi đáp Tri thức Nội bộ DocuMind AI
          </h2>
          <p className="text-sm text-slate-500">
            Mỗi câu trả lời đều đối chiếu trực tiếp từ tài liệu được phân quyền của bạn và kèm
            trích dẫn nguồn xác thực.
          </p>
        </div>

        <div className="grid gap-2.5 sm:grid-cols-2">
          {STARTER_PROMPTS.map((prompt) => (
            <button
              key={prompt}
              type="button"
              disabled={isStreaming || isRateLimited}
              onClick={() => onSelectStarter(prompt)}
              className="min-h-11 rounded-lg border border-slate-200 bg-white p-3 text-left text-xs leading-relaxed text-slate-700 shadow-sm transition-colors hover:border-primary/40 hover:bg-primary/5 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
            >
              {prompt}
            </button>
          ))}
        </div>
      </div>
    );
  }

  return (
    <ol className="mx-auto w-full max-w-4xl space-y-4">
      {messages.map((message) => {
        if (message.role === 'USER') {
          return (
            <li key={message.id} className="flex justify-end">
              <div className="flex max-w-[85%] items-start gap-2.5 sm:max-w-[75%]">
                <div className="rounded-2xl rounded-tr-sm bg-primary px-4 py-2.5 text-sm text-primary-foreground shadow-sm whitespace-pre-wrap break-words">
                  {message.content}
                </div>
                <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-slate-200 text-slate-700">
                  <UserIcon className="h-4 w-4" aria-hidden="true" />
                </div>
              </div>
            </li>
          );
        }

        const isRedacted =
          message.redacted === true || message.content === REDACTED_ASSISTANT_CONTENT;
        const isAbstained =
          !isRedacted &&
          (message.abstained === true || message.content.trim() === SAFE_REFUSAL_MESSAGE);

        return (
          <li key={message.id} className="flex justify-start">
            <div className="flex w-full max-w-[95%] items-start gap-2.5 sm:max-w-[88%]">
              <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <Bot className="h-4 w-4" aria-hidden="true" />
              </div>

              <div className="min-w-0 flex-1 rounded-2xl rounded-tl-sm border border-slate-200 bg-white p-4 shadow-sm">
                {message.rewrittenQuery ? (
                  <p className="mb-2.5 rounded bg-slate-50 px-2.5 py-1 text-xs text-slate-500">
                    <span className="font-medium text-slate-600">Truy vấn ngữ cảnh:</span>{' '}
                    {message.rewrittenQuery}
                  </p>
                ) : null}

                {message.status === 'searching' ? (
                  <div
                    role="progressbar"
                    aria-label="Đang truy xuất tài liệu nội bộ"
                    className="flex items-center gap-2 text-sm text-slate-500"
                  >
                    <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" />
                    <span>Đang truy xuất tài liệu nội bộ...</span>
                  </div>
                ) : null}

                {isRedacted ? (
                  <div className="flex items-start gap-2.5 rounded-md border border-slate-200 bg-slate-100 p-3 text-sm text-slate-700">
                    <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-slate-500" aria-hidden="true" />
                    <p>{message.content}</p>
                  </div>
                ) : isAbstained ? (
                  <div className="space-y-1.5 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
                    <div className="flex items-center gap-1.5 font-medium">
                      <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600" aria-hidden="true" />
                      <span>Ngoài phạm vi tài liệu nội bộ</span>
                    </div>
                    <p className="text-amber-800">{message.content}</p>
                  </div>
                ) : message.content ? (
                  <div>
                    <MarkdownContent content={message.content} />
                    {message.status === 'streaming' ? (
                      <span
                        aria-hidden="true"
                        className="ml-1 inline-block h-4 w-1.5 animate-pulse bg-primary align-text-bottom"
                      />
                    ) : null}
                  </div>
                ) : message.status === 'streaming' ? (
                  <div
                    role="progressbar"
                    aria-label="Đang tổng hợp câu trả lời"
                    className="flex items-center gap-2 text-sm text-slate-500"
                  >
                    <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" />
                    <span>Đang tổng hợp câu trả lời...</span>
                  </div>
                ) : null}

                {message.status === 'stopped' ? (
                  <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-2.5">
                    <Badge variant="secondary">Đã dừng sinh câu trả lời</Badge>
                    {message.sourceQuestion ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={isStreaming}
                        onClick={() => onRetryMessage(message)}
                      >
                        <RotateCcw className="h-3.5 w-3.5" aria-hidden="true" />
                        <span>Gửi lại</span>
                      </Button>
                    ) : null}
                  </div>
                ) : null}

                {message.status === 'error' && message.errorMessage ? (
                  <div
                    role="alert"
                    className="mt-2.5 space-y-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive"
                  >
                    <p>{message.errorMessage}</p>
                    {message.retryable !== false && message.sourceQuestion ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={isStreaming}
                        onClick={() => onRetryMessage(message)}
                      >
                        <RotateCcw className="h-3.5 w-3.5" aria-hidden="true" />
                        <span>Gửi lại</span>
                      </Button>
                    ) : null}
                  </div>
                ) : null}

                {!isRedacted && !isAbstained ? (
                  <CitationCards citations={message.citations} onSelect={onSelectCitation} />
                ) : null}
                {renderTelemetryBadges(message)}
              </div>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
