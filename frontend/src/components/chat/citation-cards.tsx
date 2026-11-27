'use client';

import React from 'react';
import { FileText } from 'lucide-react';
import type { CitationDto } from '@/lib/types/chat';

interface CitationCardsProps {
  citations: CitationDto[];
  onSelect: (citation: CitationDto) => void;
}

function formatCitationLocation(citation: CitationDto): string | null {
  if (citation.pageNumber != null) {
    return `Trang ${citation.pageNumber}`;
  }
  if (citation.sectionHeading) {
    return citation.sectionHeading;
  }
  return null;
}

export function CitationCards({ citations, onSelect }: CitationCardsProps) {
  if (citations.length === 0) {
    return null;
  }

  return (
    <div className="mt-3 space-y-2 border-t border-slate-100 pt-2.5">
      <div className="text-xs font-medium text-slate-500">Nguồn tham chiếu ({citations.length})</div>
      <div className="flex flex-wrap gap-2">
        {citations.map((citation, index) => {
          const location = formatCitationLocation(citation);
          const similarityPct = Number.isFinite(citation.similarityScore)
            ? Math.min(100, Math.max(0, Math.round(citation.similarityScore * 100)))
            : 0;
          const accessibleLabel = [
            `Nguồn ${index + 1}:`,
            citation.fileName,
            location,
            `${similarityPct}% tương đồng`,
          ]
            .filter(Boolean)
            .join(' — ');

          return (
            <button
              key={`${citation.documentId}-${citation.pageNumber ?? 'na'}-${index}`}
              type="button"
              onClick={() => onSelect(citation)}
              aria-label={accessibleLabel}
              className="inline-flex min-h-9 max-w-full items-center gap-2 rounded-md border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-left text-xs text-slate-700 transition-colors hover:border-primary/40 hover:bg-primary/5 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded bg-primary/10 font-semibold text-primary">
                {index + 1}
              </span>
              <FileText className="h-3.5 w-3.5 shrink-0 text-slate-400" aria-hidden="true" />
              <span className="max-w-[180px] truncate font-medium text-slate-800">
                {citation.fileName}
              </span>
              {location ? <span className="shrink-0 text-slate-500">· {location}</span> : null}
              <span className="shrink-0 rounded bg-white px-1.5 py-0.5 text-[11px] font-medium text-slate-600 ring-1 ring-inset ring-slate-200">
                {similarityPct}%
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
