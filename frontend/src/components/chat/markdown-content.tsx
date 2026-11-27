'use client';

import React from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { cn } from '@/lib/utils';

const ALLOWED_PROTOCOLS = new Set(['http:', 'https:', 'mailto:']);
const REMARK_PLUGINS = [remarkGfm];

export function sanitizeMarkdownUrl(rawUrl: string): string {
  const trimmed = (rawUrl || '').trim();
  if (!trimmed) {
    return '';
  }

  // Strip ASCII control characters & whitespace first so obfuscated schemes or "/\t/evil.com" cannot bypass checks
  const compact = trimmed.replace(/[\u0000-\u0020\u007F]+/g, '');
  if (!compact) {
    return '';
  }
  if (compact.startsWith('#')) {
    return compact;
  }
  if (/^[/\\]{2}/.test(compact) || compact.startsWith('\\')) {
    return '';
  }
  if (compact.startsWith('/')) {
    return compact;
  }

  try {
    const parsed = new URL(compact);
    if (
      ALLOWED_PROTOCOLS.has(parsed.protocol) &&
      (parsed.protocol === 'mailto:' || /^https?:\/\//i.test(compact))
    ) {
      return compact;
    }
    return '';
  } catch {
    return '';
  }
}

const MARKDOWN_COMPONENTS: Components = {
  p: ({ children }) => <p className="mb-2.5 leading-relaxed last:mb-0">{children}</p>,
  h1: ({ children }) => <h2 className="mb-2 mt-3 text-base font-bold text-slate-900 first:mt-0">{children}</h2>,
  h2: ({ children }) => <h3 className="mb-2 mt-3 text-sm font-bold text-slate-900 first:mt-0">{children}</h3>,
  h3: ({ children }) => <h4 className="mb-1.5 mt-2.5 text-sm font-semibold text-slate-900 first:mt-0">{children}</h4>,
  ul: ({ children }) => <ul className="mb-2.5 list-disc space-y-1 pl-5 last:mb-0">{children}</ul>,
  ol: ({ children }) => <ol className="mb-2.5 list-decimal space-y-1 pl-5 last:mb-0">{children}</ol>,
  li: ({ children }) => <li className="leading-relaxed">{children}</li>,
  blockquote: ({ children }) => (
    <blockquote className="my-2.5 border-l-2 border-primary/40 pl-3 italic text-slate-600">
      {children}
    </blockquote>
  ),
  pre: ({ children }) => (
    <pre className="my-2.5 overflow-x-auto rounded-md bg-slate-900 p-3 font-mono text-xs text-slate-100 [&_code]:bg-transparent [&_code]:p-0 [&_code]:text-inherit">
      {children}
    </pre>
  ),
  code: ({ className, children }) => (
    <code
      className={cn(
        'rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[0.85em] text-slate-800',
        className,
      )}
    >
      {children}
    </code>
  ),
  a: ({ href, children }) => {
    if (!href) {
      return <span className="text-slate-700">{children}</span>;
    }
    return (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className="font-medium text-primary underline underline-offset-2 hover:text-primary/80"
      >
        {children}
      </a>
    );
  },
  table: ({ children }) => (
    <div className="my-2.5 overflow-x-auto rounded-md border border-slate-200">
      <table className="w-full border-collapse text-left text-xs sm:text-sm">{children}</table>
    </div>
  ),
  thead: ({ children }) => <thead className="bg-slate-100">{children}</thead>,
  th: ({ children }) => (
    <th scope="col" className="border-b border-slate-200 px-3 py-2 font-semibold text-slate-900">
      {children}
    </th>
  ),
  td: ({ children }) => (
    <td className="border-b border-slate-100 px-3 py-2 align-top text-slate-700">{children}</td>
  ),
  hr: () => <hr className="my-3 border-slate-200" />,
};

interface MarkdownContentProps {
  content: string;
  className?: string;
}

export const MarkdownContent = React.memo(function MarkdownContent({
  content,
  className,
}: MarkdownContentProps) {
  return (
    <div className={cn('text-sm text-slate-800 break-words', className)}>
      <ReactMarkdown
        remarkPlugins={REMARK_PLUGINS}
        urlTransform={sanitizeMarkdownUrl}
        components={MARKDOWN_COMPONENTS}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
});
