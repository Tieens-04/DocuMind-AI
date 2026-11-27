'use client';

import React from 'react';
import { SendHorizontal, Square } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { MAX_CHAT_QUESTION_CHARS } from '@/lib/types/chat';

interface ChatComposerProps {
  value: string;
  onChange: (nextValue: string) => void;
  onSubmit: () => void;
  onStop: () => void;
  isStreaming: boolean;
  disabled?: boolean;
  rateLimitSeconds: number;
}

export function ChatComposer({
  value,
  onChange,
  onSubmit,
  onStop,
  isStreaming,
  disabled = false,
  rateLimitSeconds,
}: ChatComposerProps) {
  const isRateLimited = rateLimitSeconds > 0;
  const canSubmit = !isStreaming && !disabled && !isRateLimited && value.trim().length > 0;

  function handleKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      if (canSubmit) {
        onSubmit();
      }
    }
  }

  function handleFormSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (canSubmit) {
      onSubmit();
    }
  }

  return (
    <form onSubmit={handleFormSubmit} className="mx-auto w-full max-w-4xl space-y-2">
      {isRateLimited ? (
        <div
          id="chat-rate-limit-alert"
          role="alert"
          className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-xs font-medium text-amber-900"
        >
          Bạn thao tác quá nhanh. Vui lòng thử lại sau {rateLimitSeconds} giây.
        </div>
      ) : null}

      <div className="flex items-end gap-2 rounded-xl border border-slate-200 bg-white p-2 shadow-sm focus-within:border-primary/60 focus-within:ring-2 focus-within:ring-ring/20">
        <label htmlFor="chat-question-input" className="sr-only">
          Nhập câu hỏi cho trợ lý DocuMind AI
        </label>
        <textarea
          id="chat-question-input"
          rows={2}
          maxLength={MAX_CHAT_QUESTION_CHARS}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          aria-describedby={
            isRateLimited ? 'chat-composer-hint chat-rate-limit-alert' : 'chat-composer-hint'
          }
          placeholder="Đặt câu hỏi về tài liệu nội bộ (ví dụ: Quy trình nghỉ phép năm...)"
          className="max-h-40 min-h-[44px] w-full resize-none bg-transparent px-2 py-1.5 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
        />

        {isStreaming ? (
          <Button
            type="button"
            variant="destructive"
            size="sm"
            onClick={onStop}
            aria-label="Dừng sinh câu trả lời"
            className="shrink-0"
          >
            <Square className="h-3.5 w-3.5 fill-current" aria-hidden="true" />
            <span>Dừng</span>
          </Button>
        ) : (
          <Button type="submit" size="sm" disabled={!canSubmit} className="shrink-0">
            <SendHorizontal className="h-4 w-4" aria-hidden="true" />
            <span>{isRateLimited ? `Chờ ${rateLimitSeconds}s` : 'Gửi'}</span>
          </Button>
        )}
      </div>

      <div
        id="chat-composer-hint"
        className="flex items-center justify-between px-1 text-[11px] text-slate-500"
      >
        <span>Nhấn Enter để gửi · Shift+Enter để xuống dòng</span>
        {value.length > 3200 ? (
          <span>
            {value.length}/{MAX_CHAT_QUESTION_CHARS}
          </span>
        ) : null}
      </div>
    </form>
  );
}
