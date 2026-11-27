'use client';

import React, { useEffect, useState } from 'react';
import { ExternalLink, FileText, Loader2, RefreshCw } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { getDocumentPreviewUrl } from '@/lib/api/chat-stream';
import type { CitationDto } from '@/lib/types/chat';
import { ApiError, formatApiErrorMessage } from '@/lib/types/problem';

interface CitationDrawerProps {
  citation: CitationDto | null;
  onClose: () => void;
}

interface PreviewState {
  key: string | null;
  status: 'idle' | 'loading' | 'loaded' | 'error';
  freshUrl: string | null;
  resolvedPage: number | null;
  error: string | null;
  accessBlocked: boolean;
}

const INITIAL_PREVIEW_STATE: PreviewState = {
  key: null,
  status: 'idle',
  freshUrl: null,
  resolvedPage: null,
  error: null,
  accessBlocked: false,
};

function isTerminalAccessError(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    (error.status === 401 || error.status === 403 || error.status === 404)
  );
}

export function CitationDrawer({ citation, onClose }: CitationDrawerProps) {
  const citationKey = citation
    ? `${citation.documentId}:${citation.pageNumber ?? ''}:${citation.docVersion ?? ''}`
    : null;
  const [previewState, setPreviewState] = useState<PreviewState>(INITIAL_PREVIEW_STATE);
  const [retrySeed, setRetrySeed] = useState(0);

  useEffect(() => {
    if (!citation || !citationKey) {
      setPreviewState(INITIAL_PREVIEW_STATE);
      return;
    }

    const controller = new AbortController();
    setPreviewState({
      key: citationKey,
      status: 'loading',
      freshUrl: null,
      resolvedPage: citation.pageNumber ?? null,
      error: null,
      accessBlocked: false,
    });

    void getDocumentPreviewUrl(citation.documentId, citation.pageNumber, controller.signal)
      .then((res) => {
        if (controller.signal.aborted) {
          return;
        }
        setPreviewState({
          key: citationKey,
          status: 'loaded',
          freshUrl: res.previewUrl,
          resolvedPage: res.pageNumber,
          error: null,
          accessBlocked: false,
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setPreviewState({
          key: citationKey,
          status: 'error',
          freshUrl: null,
          resolvedPage: citation.pageNumber ?? null,
          error: formatApiErrorMessage(error),
          accessBlocked: isTerminalAccessError(error),
        });
      });

    return () => {
      controller.abort();
    };
  }, [citation, citationKey, retrySeed]);

  const isCurrent = citationKey !== null && previewState.key === citationKey;
  const loadingUrl = citationKey !== null && (!isCurrent || previewState.status === 'loading');
  const urlError = isCurrent && previewState.status === 'error' ? previewState.error : null;
  const accessBlocked = isCurrent ? previewState.accessBlocked : false;
  const freshUrl = isCurrent && previewState.status === 'loaded' ? previewState.freshUrl : null;
  const resolvedPage =
    isCurrent && previewState.status === 'loaded'
      ? previewState.resolvedPage
      : (citation?.pageNumber ?? null);

  const effectiveUrl =
    loadingUrl || accessBlocked ? null : (freshUrl ?? citation?.presignedUrl ?? null);
  const pageForLabel = resolvedPage ?? citation?.pageNumber ?? null;
  const similarityPct =
    citation && Number.isFinite(citation.similarityScore)
      ? Math.min(100, Math.max(0, Math.round(citation.similarityScore * 100)))
      : 0;

  return (
    <Sheet
      open={Boolean(citation)}
      onOpenChange={(open) => {
        if (!open) {
          onClose();
        }
      }}
    >
      <SheetContent
        side="right"
        closeLabel="Đóng ngăn trích dẫn nguồn"
        className="flex w-full flex-col gap-5 overflow-y-auto sm:max-w-lg"
      >
        {citation ? (
          <>
            <SheetHeader className="pr-8">
              <div className="flex items-start gap-2.5">
                <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                  <FileText className="h-4 w-4" aria-hidden="true" />
                </div>
                <div className="min-w-0 space-y-1">
                  <SheetTitle className="break-words text-base">{citation.fileName}</SheetTitle>
                  <SheetDescription>
                    Đối chiếu đoạn trích gốc từ kho tài liệu nội bộ
                  </SheetDescription>
                </div>
              </div>
            </SheetHeader>

            <div className="flex flex-wrap items-center gap-2">
              {citation.docVersion != null ? (
                <Badge variant="secondary">Phiên bản v{citation.docVersion}</Badge>
              ) : null}
              {citation.pageNumber != null ? (
                <Badge variant="outline">Trang {citation.pageNumber}</Badge>
              ) : null}
              {citation.sectionHeading ? (
                <Badge variant="outline">{citation.sectionHeading}</Badge>
              ) : null}
              <Badge variant="outline">{similarityPct}% độ tương đồng</Badge>
            </div>

            <div className="space-y-2">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                Đoạn trích gốc
              </h3>
              <blockquote className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm leading-relaxed text-slate-800 whitespace-pre-wrap break-words">
                {citation.excerpt}
              </blockquote>
            </div>

            <div className="mt-auto space-y-3 border-t border-slate-200 pt-4">
              {loadingUrl ? (
                <p className="flex items-center gap-2 text-xs text-slate-500">
                  <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
                  <span>Đang tạo liên kết xem trước bảo mật từ MinIO...</span>
                </p>
              ) : null}

              {urlError ? (
                <div
                  role="alert"
                  className="space-y-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive"
                >
                  <p>{urlError}</p>
                  {!accessBlocked ? (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setRetrySeed((prev) => prev + 1)}
                    >
                      <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                      <span>Thử làm mới liên kết</span>
                    </Button>
                  ) : null}
                </div>
              ) : null}

              {effectiveUrl ? (
                <Button asChild className="w-full justify-center gap-2">
                  <a href={effectiveUrl} target="_blank" rel="noopener noreferrer">
                    <ExternalLink className="h-4 w-4" aria-hidden="true" />
                    <span>
                      {pageForLabel != null
                        ? `Mở tài liệu gốc (Trang ${pageForLabel})`
                        : 'Mở tài liệu gốc'}
                    </span>
                  </a>
                </Button>
              ) : !loadingUrl ? (
                <Button type="button" disabled className="w-full">
                  Không có liên kết xem trước khả dụng
                </Button>
              ) : null}
            </div>
          </>
        ) : null}
      </SheetContent>
    </Sheet>
  );
}
