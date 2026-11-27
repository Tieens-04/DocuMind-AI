'use client';

import React from 'react';
import { AlertCircle, Building2, CheckCircle2, Clock, Globe, Loader2, Lock } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import {
  ACCESS_LEVEL_LABELS,
  DOCUMENT_STATUS_LABELS,
  type AccessLevel,
  type DocumentStatus,
} from '@/lib/types/documents';
import { cn } from '@/lib/utils';

const STATUS_STYLES: Record<DocumentStatus, string> = {
  PENDING: 'border-amber-200 bg-amber-50 text-amber-800',
  PROCESSING: 'border-sky-200 bg-sky-50 text-sky-800',
  COMPLETED: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  FAILED: 'border-destructive/30 bg-destructive/10 text-destructive',
};

const ACCESS_LEVEL_STYLES: Record<AccessLevel, string> = {
  PUBLIC: 'border-teal-200 bg-teal-50 text-teal-800',
  DEPARTMENT: 'border-slate-200 bg-slate-100 text-slate-800',
  CONFIDENTIAL: 'border-rose-200 bg-rose-50 text-rose-800',
};

interface DocumentStatusBadgeProps {
  status: DocumentStatus;
  chunkCount?: number;
}

export function DocumentStatusBadge({ status, chunkCount }: DocumentStatusBadgeProps) {
  return (
    <div className="inline-flex flex-wrap items-center gap-1.5">
      <Badge variant="outline" className={cn('gap-1 font-medium', STATUS_STYLES[status])}>
        {status === 'PENDING' && <Clock className="h-3 w-3" aria-hidden="true" />}
        {status === 'PROCESSING' && (
          <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
        )}
        {status === 'COMPLETED' && <CheckCircle2 className="h-3 w-3" aria-hidden="true" />}
        {status === 'FAILED' && <AlertCircle className="h-3 w-3" aria-hidden="true" />}
        <span>{DOCUMENT_STATUS_LABELS[status]}</span>
      </Badge>
      {status === 'COMPLETED' && typeof chunkCount === 'number' && chunkCount > 0 && (
        <span className="text-xs text-muted-foreground">{chunkCount} đoạn</span>
      )}
    </div>
  );
}

interface AccessLevelBadgeProps {
  accessLevel: AccessLevel;
}

export function AccessLevelBadge({ accessLevel }: AccessLevelBadgeProps) {
  return (
    <Badge variant="outline" className={cn('gap-1 font-medium', ACCESS_LEVEL_STYLES[accessLevel])}>
      {accessLevel === 'PUBLIC' && <Globe className="h-3 w-3" aria-hidden="true" />}
      {accessLevel === 'DEPARTMENT' && <Building2 className="h-3 w-3" aria-hidden="true" />}
      {accessLevel === 'CONFIDENTIAL' && <Lock className="h-3 w-3" aria-hidden="true" />}
      <span>{ACCESS_LEVEL_LABELS[accessLevel]}</span>
    </Badge>
  );
}
