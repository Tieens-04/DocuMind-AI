'use client';

import React from 'react';
import { Clock, DatabaseZap, MessageSquare, Sparkles } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import type { AdminAnalyticsSummaryResponse } from '@/lib/types/admin';

interface FinOpsKpiCardsProps {
  summary: AdminAnalyticsSummaryResponse | null;
  loading: boolean;
}

function formatLatencyLabel(safeMs: number): string {
  if (safeMs >= 1000) {
    return `${(safeMs / 1000).toFixed(2)} s`;
  }
  return `${safeMs.toFixed(2)} ms`;
}

export function FinOpsKpiCards({ summary, loading }: FinOpsKpiCardsProps) {
  if (loading && !summary) {
    return (
      <div
        className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4"
        aria-label="Đang tải chỉ số FinOps"
      >
        {[0, 1, 2, 3].map((idx) => (
          <Card key={idx} className="border-slate-200">
            <CardHeader className="pb-2">
              <div className="h-4 w-28 animate-pulse rounded bg-slate-200" />
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="h-7 w-24 animate-pulse rounded bg-slate-200" />
              <div className="h-3.5 w-36 animate-pulse rounded bg-slate-100" />
            </CardContent>
          </Card>
        ))}
      </div>
    );
  }

  const queries = summary?.successfulQueries ?? 0;
  const cacheHits = summary?.cacheHits ?? 0;
  const totalTokens = summary?.totalTokens ?? 0;
  const promptTokens = summary?.promptTokens ?? 0;
  const completionTokens = summary?.completionTokens ?? 0;
  const hitRatio = Number(summary?.cacheHitRatioPercent ?? 0);
  const rawAvgLatencyMs = Number(summary?.averageLatencyMs ?? 0);
  const safeAvgLatencyMs = Number.isFinite(rawAvgLatencyMs) ? Math.max(0, rawAvgLatencyMs) : 0;

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Card className="border-slate-200 shadow-sm" data-testid="kpi-total-queries">
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="text-sm font-medium text-slate-600">Tổng câu hỏi</CardTitle>
          <MessageSquare className="h-4 w-4 text-sky-600" aria-hidden="true" />
        </CardHeader>
        <CardContent>
          <div className="text-2xl font-bold text-slate-900">
            {queries.toLocaleString('vi-VN')}
          </div>
          <p className="mt-1 text-xs text-muted-foreground">
            {cacheHits.toLocaleString('vi-VN')} lượt trả về từ Cache
          </p>
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm" data-testid="kpi-total-tokens">
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="text-sm font-medium text-slate-600">Tổng Tokens</CardTitle>
          <Sparkles className="h-4 w-4 text-indigo-600" aria-hidden="true" />
        </CardHeader>
        <CardContent>
          <div className="text-2xl font-bold text-slate-900">
            {totalTokens.toLocaleString('vi-VN')}
          </div>
          <p className="mt-1 text-xs text-muted-foreground">
            Prompt {promptTokens.toLocaleString('vi-VN')} · Completion{' '}
            {completionTokens.toLocaleString('vi-VN')}
          </p>
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm" data-testid="kpi-cache-hit-ratio">
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="text-sm font-medium text-slate-600">Cache Hit Ratio</CardTitle>
          <DatabaseZap className="h-4 w-4 text-emerald-600" aria-hidden="true" />
        </CardHeader>
        <CardContent>
          <div className="text-2xl font-bold text-slate-900">{hitRatio.toFixed(2)}%</div>
          <p className="mt-1 text-xs text-muted-foreground">
            Tỷ lệ L1/L2 trên câu hỏi thành công
          </p>
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm" data-testid="kpi-avg-latency">
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="text-sm font-medium text-slate-600">Độ trễ TB</CardTitle>
          <Clock className="h-4 w-4 text-amber-600" aria-hidden="true" />
        </CardHeader>
        <CardContent>
          <div
            className="text-2xl font-bold text-slate-900"
            title={`${safeAvgLatencyMs.toFixed(2)} ms`}
          >
            {formatLatencyLabel(safeAvgLatencyMs)}
          </div>
          <p className="mt-1 text-xs text-muted-foreground">
            {safeAvgLatencyMs.toFixed(2)} ms trung bình mỗi truy vấn 2xx
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
