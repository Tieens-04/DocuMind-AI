'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { AuditLogDetailSheet } from '@/components/admin/audit-log-detail-sheet';
import { AuditLogTable, type AuditLogFilterState } from '@/components/admin/audit-log-table';
import { FinOpsCharts } from '@/components/admin/finops-charts';
import { FinOpsKpiCards } from '@/components/admin/finops-kpi-cards';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  getAdminAnalyticsSummary,
  getAdminDailyTrends,
  getAdminTokenUsageByDepartment,
  listAdminAuditLogs,
  listAdminDepartmentsPage,
  listAdminUsers,
} from '@/lib/api/admin';
import type {
  AdminAnalyticsSummaryResponse,
  AdminAuditLogItemResponse,
  AdminDailyTrendPointResponse,
  AdminDepartmentResponse,
  AdminDepartmentTokenUsageResponse,
  AdminTimeWindowParams,
  AdminUserResponse,
  PageResponse,
} from '@/lib/types/admin';
import { formatApiErrorMessage } from '@/lib/types/problem';

type WindowPreset = '7d' | '30d' | '90d' | 'custom';

const INITIAL_FILTERS: AuditLogFilterState = {
  actionType: '',
  departmentId: '',
  cacheHit: '',
  statusCode: '',
  userId: '',
};

const MAX_WINDOW_MS = 366 * 24 * 60 * 60 * 1000;

function computePresetWindow(days: number): Required<AdminTimeWindowParams> {
  const to = new Date();
  const from = new Date(to.getTime() - days * 24 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: to.toISOString() };
}

function toUtcDateInputValue(iso: string): string {
  return iso.slice(0, 10);
}

export default function AdminAnalyticsPage() {
  const [preset, setPreset] = useState<WindowPreset>('30d');
  const [appliedWindow, setAppliedWindow] = useState<Required<AdminTimeWindowParams>>(() =>
    computePresetWindow(30),
  );
  const [customFromDate, setCustomFromDate] = useState(() =>
    toUtcDateInputValue(appliedWindow.from),
  );
  const [customToDate, setCustomToDate] = useState(() =>
    toUtcDateInputValue(appliedWindow.to),
  );
  const [windowError, setWindowError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);

  const [summary, setSummary] = useState<AdminAnalyticsSummaryResponse | null>(null);
  const [deptUsage, setDeptUsage] = useState<AdminDepartmentTokenUsageResponse[]>([]);
  const [dailyTrends, setDailyTrends] = useState<AdminDailyTrendPointResponse[]>([]);
  const [overviewLoading, setOverviewLoading] = useState(true);
  const [overviewError, setOverviewError] = useState<string | null>(null);

  const [departments, setDepartments] = useState<AdminDepartmentResponse[]>([]);
  const [users, setUsers] = useState<AdminUserResponse[]>([]);

  const [filters, setFilters] = useState<AuditLogFilterState>(INITIAL_FILTERS);
  const [auditPage, setAuditPage] = useState(0);
  const [auditData, setAuditData] = useState<PageResponse<AdminAuditLogItemResponse> | null>(null);
  const [auditLoading, setAuditLoading] = useState(true);
  const [auditError, setAuditError] = useState<string | null>(null);
  const [selectedLog, setSelectedLog] = useState<AdminAuditLogItemResponse | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    Promise.allSettled([
      listAdminDepartmentsPage({ page: 0, size: 100 }, controller.signal),
      listAdminUsers({ page: 0, size: 100 }, controller.signal),
    ]).then(([deptResult, userResult]) => {
      if (controller.signal.aborted) return;
      if (deptResult.status === 'fulfilled') {
        setDepartments(deptResult.value.items);
      }
      if (userResult.status === 'fulfilled') {
        setUsers(userResult.value.items);
      }
    });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setOverviewLoading(true);
    setOverviewError(null);

    Promise.all([
      getAdminAnalyticsSummary(appliedWindow, controller.signal),
      getAdminTokenUsageByDepartment(appliedWindow, controller.signal),
      getAdminDailyTrends(appliedWindow, controller.signal),
    ])
      .then(([summaryRes, deptRes, trendRes]) => {
        if (controller.signal.aborted) return;
        setSummary(summaryRes);
        setDeptUsage(deptRes.items);
        setDailyTrends(trendRes.items);
        setOverviewLoading(false);
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setOverviewError(formatApiErrorMessage(error));
        setOverviewLoading(false);
      });

    return () => controller.abort();
  }, [appliedWindow, refreshTick]);

  useEffect(() => {
    const controller = new AbortController();
    setAuditLoading(true);
    setAuditError(null);

    listAdminAuditLogs(
      {
        from: appliedWindow.from,
        to: appliedWindow.to,
        actionType: filters.actionType || undefined,
        departmentId: filters.departmentId || undefined,
        userId: filters.userId || undefined,
        statusCode: filters.statusCode ? Number(filters.statusCode) : undefined,
        cacheHit: filters.cacheHit === '' ? undefined : filters.cacheHit === 'true',
        page: auditPage,
        size: 20,
      },
      controller.signal,
    )
      .then((res) => {
        if (controller.signal.aborted) return;
        setAuditData(res);
        setAuditLoading(false);
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAuditError(formatApiErrorMessage(error));
        setAuditLoading(false);
      });

    return () => controller.abort();
  }, [appliedWindow, auditPage, filters, refreshTick]);

  const handleSelectPreset = useCallback((nextPreset: Exclude<WindowPreset, 'custom'>) => {
    const days = nextPreset === '7d' ? 7 : nextPreset === '90d' ? 90 : 30;
    const nextWindow = computePresetWindow(days);
    setPreset(nextPreset);
    setWindowError(null);
    setCustomFromDate(toUtcDateInputValue(nextWindow.from));
    setCustomToDate(toUtcDateInputValue(nextWindow.to));
    setAuditPage(0);
    setAppliedWindow(nextWindow);
  }, []);

  const handleApplyCustomWindow = useCallback(() => {
    if (!customFromDate || !customToDate) {
      setWindowError('Vui lòng chọn đầy đủ ngày bắt đầu và ngày kết thúc.');
      return;
    }
    const fromIso = `${customFromDate}T00:00:00Z`;
    const toDateObj = new Date(`${customToDate}T00:00:00Z`);
    toDateObj.setUTCDate(toDateObj.getUTCDate() + 1);
    const fromMs = Date.parse(fromIso);
    const toMs = toDateObj.getTime();
    if (Number.isNaN(fromMs) || Number.isNaN(toMs) || fromMs >= toMs) {
      setWindowError('Ngày bắt đầu phải nhỏ hơn hoặc bằng ngày kết thúc.');
      return;
    }
    if (toMs - fromMs > MAX_WINDOW_MS) {
      setWindowError('Khoảng thời gian thống kê tối đa 366 ngày.');
      return;
    }
    setWindowError(null);
    setAuditPage(0);
    setAppliedWindow({ from: fromIso, to: toDateObj.toISOString() });
  }, [customFromDate, customToDate]);

  const handleFilterChange = useCallback((nextFilters: AuditLogFilterState) => {
    setFilters(nextFilters);
    setAuditPage(0);
  }, []);

  const handleResetFilters = useCallback(() => {
    setFilters(INITIAL_FILTERS);
    setAuditPage(0);
  }, []);

  const handleRefresh = useCallback(() => {
    if (preset === 'custom') {
      setRefreshTick((tick) => tick + 1);
      return;
    }
    const days = preset === '7d' ? 7 : preset === '90d' ? 90 : 30;
    const nextWindow = computePresetWindow(days);
    setCustomFromDate(toUtcDateInputValue(nextWindow.from));
    setCustomToDate(toUtcDateInputValue(nextWindow.to));
    setAppliedWindow(nextWindow);
  }, [preset]);

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            FinOps &amp; Audit Logs
          </h1>
          <p className="text-sm text-muted-foreground">
            Theo dõi chi phí Token, tỷ lệ Redis Cache Hit, độ trễ và nhật ký truy vấn toàn hệ thống
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="secondary">SYSTEM_ADMIN Only</Badge>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleRefresh}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
            Làm mới
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-end justify-between gap-3 rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Khung thời gian thống kê">
          {(['7d', '30d', '90d'] as const).map((item) => (
            <Button
              key={item}
              type="button"
              size="sm"
              variant={preset === item ? 'default' : 'outline'}
              onClick={() => handleSelectPreset(item)}
            >
              {item === '7d' ? '7 ngày' : item === '30d' ? '30 ngày' : '90 ngày'}
            </Button>
          ))}
          <Button
            type="button"
            size="sm"
            variant={preset === 'custom' ? 'default' : 'outline'}
            onClick={() => setPreset('custom')}
          >
            Tùy chỉnh
          </Button>
        </div>

        {preset === 'custom' ? (
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex flex-col gap-1 text-xs font-medium text-slate-600">
              Từ ngày (UTC)
              <Input
                type="date"
                aria-label="Từ ngày"
                className="h-9 w-40"
                value={customFromDate}
                onChange={(e) => setCustomFromDate(e.target.value)}
              />
            </label>
            <label className="flex flex-col gap-1 text-xs font-medium text-slate-600">
              Đến ngày (UTC)
              <Input
                type="date"
                aria-label="Đến ngày"
                className="h-9 w-40"
                value={customToDate}
                onChange={(e) => setCustomToDate(e.target.value)}
              />
            </label>
            <Button type="button" size="sm" onClick={handleApplyCustomWindow}>
              Áp dụng
            </Button>
          </div>
        ) : null}
      </div>

      {windowError ? (
        <div
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/5 px-4 py-2.5 text-sm text-destructive"
        >
          {windowError}
        </div>
      ) : null}

      {overviewError ? (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive"
        >
          <span>{overviewError}</span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleRefresh}
          >
            Thử lại
          </Button>
        </div>
      ) : null}

      <FinOpsKpiCards summary={summary} loading={overviewLoading} />

      <FinOpsCharts
        departmentUsage={deptUsage}
        dailyTrends={dailyTrends}
        loading={overviewLoading}
      />

      {auditError ? (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive"
        >
          <span>{auditError}</span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleRefresh}
          >
            Thử lại
          </Button>
        </div>
      ) : null}

      <AuditLogTable
        data={auditData}
        loading={auditLoading}
        filters={filters}
        departments={departments}
        users={users}
        onFilterChange={handleFilterChange}
        onResetFilters={handleResetFilters}
        onPageChange={setAuditPage}
        onSelectLog={setSelectedLog}
      />

      <AuditLogDetailSheet
        item={selectedLog}
        open={selectedLog !== null}
        onOpenChange={(open) => {
          if (!open) setSelectedLog(null);
        }}
        onFilterByUser={(userId) => handleFilterChange({ ...filters, userId })}
      />
    </div>
  );
}
