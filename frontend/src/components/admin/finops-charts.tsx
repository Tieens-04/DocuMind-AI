'use client';

import React, { useMemo } from 'react';
import {
  Area,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { BarChart3, TrendingUp } from 'lucide-react';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  UNASSIGNED_DEPARTMENT_LABEL,
  type AdminDailyTrendPointResponse,
  type AdminDepartmentTokenUsageResponse,
} from '@/lib/types/admin';

interface FinOpsChartsProps {
  departmentUsage: AdminDepartmentTokenUsageResponse[];
  dailyTrends: AdminDailyTrendPointResponse[];
  loading: boolean;
}

function formatCompactNumber(value: number): string {
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(1)}M`;
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(1)}K`;
  }
  return String(value);
}

function formatUtcDayLabel(isoDate: string): string {
  const parts = isoDate.split('-');
  if (parts.length !== 3) {
    return isoDate;
  }
  return `${parts[2]}/${parts[1]}`;
}

export function formatDepartmentTooltipValue(value: unknown, name: unknown): [string, string] {
  return [Number(value ?? 0).toLocaleString('vi-VN'), String(name ?? '')];
}

export function FinOpsCharts({ departmentUsage, dailyTrends, loading }: FinOpsChartsProps) {
  const deptChartData = useMemo(
    () =>
      departmentUsage.map((item) => ({
        label: item.departmentCode
          ? `${item.departmentCode}${item.departmentName ? ` (${item.departmentName})` : ''}`
          : UNASSIGNED_DEPARTMENT_LABEL,
        shortLabel: item.departmentCode ?? 'Hệ thống',
        promptTokens: item.promptTokens,
        completionTokens: item.completionTokens,
        totalTokens: item.totalTokens,
        successfulQueries: item.successfulQueries,
        cacheHitRatioPercent: Number(item.cacheHitRatioPercent ?? 0),
      })),
    [departmentUsage],
  );

  const trendChartData = useMemo(
    () =>
      dailyTrends.map((point) => ({
        date: point.date,
        dayLabel: formatUtcDayLabel(point.date),
        totalTokens: point.totalTokens,
        successfulQueries: point.successfulQueries,
        cacheHitRatioPercent: Number(point.cacheHitRatioPercent ?? 0),
      })),
    [dailyTrends],
  );

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
      <Card className="border-slate-200 shadow-sm" data-testid="chart-token-by-department">
        <CardHeader>
          <div className="flex items-center gap-2">
            <BarChart3 className="h-4 w-4 text-indigo-600" aria-hidden="true" />
            <CardTitle className="text-base">Token tiêu thụ theo Phòng ban</CardTitle>
          </div>
          <CardDescription>
            Tách Prompt &amp; Completion Tokens theo từng phòng ban trong cửa sổ thời gian
          </CardDescription>
        </CardHeader>
        <CardContent>
          {loading && deptChartData.length === 0 ? (
            <div className="h-72 animate-pulse rounded-md bg-slate-100" />
          ) : deptChartData.length === 0 ? (
            <div className="flex h-72 items-center justify-center rounded-md border border-dashed text-sm text-muted-foreground">
              Chưa có dữ liệu tiêu thụ Token theo phòng ban trong khoảng thời gian này.
            </div>
          ) : (
            <div className="space-y-3">
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%" minWidth={0} minHeight={240}>
                  <BarChart
                    data={deptChartData}
                    margin={{ top: 8, right: 16, left: 0, bottom: 8 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="shortLabel" tick={{ fontSize: 12, fill: '#475569' }} />
                    <YAxis
                      tickFormatter={(v) => formatCompactNumber(Number(v))}
                      tick={{ fontSize: 12, fill: '#475569' }}
                    />
                    <Tooltip
                      formatter={formatDepartmentTooltipValue}
                      labelFormatter={(_label, payload) =>
                        payload?.[0]?.payload?.label ?? String(_label)
                      }
                    />
                    <Legend />
                    <Bar
                      dataKey="promptTokens"
                      name="Prompt Tokens"
                      stackId="tokens"
                      fill="#4f46e5"
                      radius={[0, 0, 0, 0]}
                    />
                    <Bar
                      dataKey="completionTokens"
                      name="Completion Tokens"
                      stackId="tokens"
                      fill="#0ea5e9"
                      radius={[4, 4, 0, 0]}
                    />
                  </BarChart>
                </ResponsiveContainer>
              </div>
              <ul className="sr-only" aria-label="Tóm tắt Token theo phòng ban">
                {deptChartData.map((row) => (
                  <li key={row.label}>
                    {row.label}: {row.totalTokens} tokens ({row.successfulQueries} câu hỏi,{' '}
                    {row.cacheHitRatioPercent.toFixed(2)}% cache hit)
                  </li>
                ))}
              </ul>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm" data-testid="chart-daily-trends">
        <CardHeader>
          <div className="flex items-center gap-2">
            <TrendingUp className="h-4 w-4 text-emerald-600" aria-hidden="true" />
            <CardTitle className="text-base">Xu hướng Token &amp; Cache Hit theo ngày (UTC)</CardTitle>
          </div>
          <CardDescription>
            Theo dõi tổng Token tiêu thụ và tỷ lệ Cache Hit (%) mỗi ngày
          </CardDescription>
        </CardHeader>
        <CardContent>
          {loading && trendChartData.length === 0 ? (
            <div className="h-72 animate-pulse rounded-md bg-slate-100" />
          ) : trendChartData.length === 0 ? (
            <div className="flex h-72 items-center justify-center rounded-md border border-dashed text-sm text-muted-foreground">
              Chưa có chuỗi dữ liệu theo ngày trong khoảng thời gian này.
            </div>
          ) : (
            <div className="space-y-3">
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%" minWidth={0} minHeight={240}>
                  <ComposedChart
                    data={trendChartData}
                    margin={{ top: 8, right: 16, left: 0, bottom: 8 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="dayLabel" tick={{ fontSize: 12, fill: '#475569' }} />
                    <YAxis
                      yAxisId="left"
                      tickFormatter={(v) => formatCompactNumber(Number(v))}
                      tick={{ fontSize: 12, fill: '#475569' }}
                    />
                    <YAxis
                      yAxisId="right"
                      orientation="right"
                      domain={[0, 100]}
                      tickFormatter={(v) => `${v}%`}
                      tick={{ fontSize: 12, fill: '#059669' }}
                    />
                    <Tooltip
                      formatter={(value, name) => {
                        if (name === 'Cache Hit (%)') {
                          return [`${Number(value ?? 0).toFixed(2)}%`, name];
                        }
                        return [Number(value ?? 0).toLocaleString('vi-VN'), name];
                      }}
                      labelFormatter={(_label, payload) =>
                        payload?.[0]?.payload?.date
                          ? `Ngày UTC: ${payload[0].payload.date}`
                          : String(_label)
                      }
                    />
                    <Legend />
                    <Area
                      yAxisId="left"
                      type="monotone"
                      dataKey="totalTokens"
                      name="Tổng Tokens"
                      fill="#dbeafe"
                      stroke="#2563eb"
                      strokeWidth={2}
                    />
                    <Line
                      yAxisId="right"
                      type="monotone"
                      dataKey="cacheHitRatioPercent"
                      name="Cache Hit (%)"
                      stroke="#059669"
                      strokeWidth={2}
                      dot={{ r: 3 }}
                    />
                  </ComposedChart>
                </ResponsiveContainer>
              </div>
              <ul className="sr-only" aria-label="Tóm tắt xu hướng theo ngày">
                {trendChartData.map((row) => (
                  <li key={row.date}>
                    {row.date}: {row.totalTokens} tokens, {row.cacheHitRatioPercent.toFixed(2)}% cache hit
                  </li>
                ))}
              </ul>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
