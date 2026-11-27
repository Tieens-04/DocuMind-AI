'use client';

import React from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import {
  AUDIT_ACTION_LABELS,
  UNASSIGNED_DEPARTMENT_LABEL,
  type AdminAuditLogItemResponse,
} from '@/lib/types/admin';

interface AuditLogDetailSheetProps {
  item: AdminAuditLogItemResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onFilterByUser?: (userId: string) => void;
}

export function AuditLogDetailSheet({
  item,
  open,
  onOpenChange,
  onFilterByUser,
}: AuditLogDetailSheetProps) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        closeLabel="Đóng chi tiết nhật ký"
        className="w-full overflow-y-auto sm:max-w-lg"
      >
        <SheetHeader>
          <SheetTitle>Chi tiết bản ghi Audit Log</SheetTitle>
          <SheetDescription>
            {item ? `${AUDIT_ACTION_LABELS[item.actionType]} · HTTP ${item.statusCode}` : ''}
          </SheetDescription>
        </SheetHeader>

        {item ? (
          <div className="mt-5 space-y-4 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="secondary">{item.actionType}</Badge>
              <Badge variant={item.statusCode >= 400 ? 'destructive' : 'outline'}>
                HTTP {item.statusCode}
              </Badge>
              {item.cacheHit ? (
                <Badge className="bg-emerald-100 text-emerald-800 hover:bg-emerald-100">
                  Cache Hit ({item.cacheTier})
                </Badge>
              ) : (
                <Badge variant="outline">Cache Miss ({item.cacheTier})</Badge>
              )}
              {item.abstained ? (
                <Badge className="bg-amber-100 text-amber-800 hover:bg-amber-100">
                  Từ chối an toàn (Abstained)
                </Badge>
              ) : null}
            </div>

            <div className="space-y-1">
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                Nội dung truy vấn / Sự kiện
              </div>
              <div className="whitespace-pre-wrap break-words rounded-md border bg-slate-50 p-3 text-slate-900">
                {item.queryText?.trim() ? item.queryText : '— Không có nội dung truy vấn —'}
              </div>
            </div>

            <dl className="grid grid-cols-2 gap-3 rounded-md border p-3">
              <div>
                <dt className="text-xs text-muted-foreground">Người dùng</dt>
                <dd className="mt-0.5 break-all font-medium text-slate-900">
                  {item.userEmail ?? item.userId ?? 'Hệ thống / Ẩn danh'}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Phòng ban</dt>
                <dd className="mt-0.5 font-medium text-slate-900">
                  {item.departmentCode
                    ? `${item.departmentCode} — ${item.departmentName ?? ''}`
                    : UNASSIGNED_DEPARTMENT_LABEL}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Prompt / Completion</dt>
                <dd className="mt-0.5 font-medium text-slate-900">
                  {item.promptTokens.toLocaleString('vi-VN')} /{' '}
                  {item.completionTokens.toLocaleString('vi-VN')}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Tổng Tokens</dt>
                <dd className="mt-0.5 font-semibold text-slate-900">
                  {item.totalTokens.toLocaleString('vi-VN')}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Độ trễ xử lý</dt>
                <dd className="mt-0.5 font-medium text-slate-900">
                  {item.latencyMs.toLocaleString('vi-VN')} ms
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Nhà cung cấp LLM</dt>
                <dd className="mt-0.5 font-medium text-slate-900">{item.provider ?? '—'}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Địa chỉ IP</dt>
                <dd className="mt-0.5 font-mono text-xs text-slate-800">
                  {item.ipAddress ?? '—'}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Thời điểm (UTC/Local)</dt>
                <dd className="mt-0.5 text-xs text-slate-800">
                  {new Date(item.createdAt).toLocaleString('vi-VN')}
                </dd>
              </div>
              <div className="col-span-2">
                <dt className="text-xs text-muted-foreground">Session ID</dt>
                <dd className="mt-0.5 break-all font-mono text-xs text-slate-700">
                  {item.sessionId ?? '—'}
                </dd>
              </div>
              <div className="col-span-2">
                <dt className="text-xs text-muted-foreground">Audit ID</dt>
                <dd className="mt-0.5 break-all font-mono text-xs text-slate-700">{item.id}</dd>
              </div>
            </dl>
          </div>
        ) : null}

        <SheetFooter className="mt-6 gap-2">
          {item?.userId && onFilterByUser ? (
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                onFilterByUser(item.userId!);
                onOpenChange(false);
              }}
            >
              Lọc theo người dùng này
            </Button>
          ) : null}
          <Button type="button" onClick={() => onOpenChange(false)}>
            Đóng
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
