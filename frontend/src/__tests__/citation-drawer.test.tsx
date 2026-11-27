import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CitationDrawer } from '@/components/chat/citation-drawer';
import { resetTokenManagerForTests, setSessionTokens } from '@/lib/auth/token-manager';
import type { CitationDto } from '@/lib/types/chat';

const SAMPLE_CITATION: CitationDto = {
  documentId: 'doc-123',
  docVersion: 3,
  fileName: 'chinh-sach-nghi-phep.pdf',
  pageNumber: 5,
  sectionHeading: null,
  similarityScore: 0.88,
  excerpt: 'Mỗi nhân viên chính thức được hưởng 12 ngày phép năm có lương.',
  presignedUrl: 'http://localhost:8081/api/v1/documents/doc-123/content?token=old#page=5',
};

describe('CitationDrawer', () => {
  beforeEach(() => {
    resetTokenManagerForTests();
    vi.stubEnv('NEXT_PUBLIC_API_BASE_URL', 'http://localhost:8081');
    setSessionTokens({
      accessToken: 'test-access-token',
      refreshToken: 'test-refresh-token',
      expiresIn: 900,
    });
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('displays citation metadata, excerpt, and fetches fresh /preview-url?page=5 link', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        expect(String(input)).toBe(
          'http://localhost:8081/api/v1/documents/doc-123/preview-url?page=5',
        );
        return new Response(
          JSON.stringify({
            documentId: 'doc-123',
            originalName: 'chinh-sach-nghi-phep.pdf',
            mimeType: 'application/pdf',
            fileExtension: 'pdf',
            currentVersion: 3,
            pageNumber: 5,
            previewUrl: 'http://localhost:8081/api/v1/documents/doc-123/content?token=fresh#page=5',
            expiresAt: '2026-11-27T15:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );

    render(<CitationDrawer citation={SAMPLE_CITATION} onClose={vi.fn()} />);

    expect(screen.getByText('chinh-sach-nghi-phep.pdf')).toBeInTheDocument();
    expect(screen.getByText('Phiên bản v3')).toBeInTheDocument();
    expect(screen.getByText('Trang 5')).toBeInTheDocument();
    expect(screen.getByText('88% độ tương đồng')).toBeInTheDocument();
    expect(
      screen.getByText('Mỗi nhân viên chính thức được hưởng 12 ngày phép năm có lương.'),
    ).toBeInTheDocument();

    await waitFor(() => {
      const link = screen.getByRole('link', { name: /Mở tài liệu gốc \(Trang 5\)/i });
      expect(link).toHaveAttribute(
        'href',
        'http://localhost:8081/api/v1/documents/doc-123/content?token=fresh#page=5',
      );
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    });
  });

  it('blocks stale fallback link when /preview-url returns 403 and supports retry on transient failure', async () => {
    const user = userEvent.setup();
    let callCount = 0;

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        callCount += 1;
        if (callCount === 1) {
          return new Response(
            JSON.stringify({
              status: 403,
              title: 'Forbidden',
              detail: 'Access denied',
              errorCode: 'DOCUMENT_ACCESS_DENIED',
            }),
            { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        if (callCount === 2) {
          return new Response(
            JSON.stringify({
              status: 503,
              title: 'Service Unavailable',
              detail: 'MinIO timeout',
            }),
            { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return new Response(
          JSON.stringify({
            documentId: 'doc-123',
            originalName: 'chinh-sach-nghi-phep.pdf',
            mimeType: 'application/pdf',
            fileExtension: 'pdf',
            currentVersion: 3,
            pageNumber: 5,
            previewUrl: 'http://localhost:8081/api/v1/documents/doc-123/content?token=retried#page=5',
            expiresAt: '2026-11-27T15:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );

    const { rerender } = render(<CitationDrawer citation={SAMPLE_CITATION} onClose={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        'Bạn không có quyền thực hiện thao tác này.',
      );
    });
    expect(screen.queryByRole('link', { name: /Mở tài liệu gốc/i })).not.toBeInTheDocument();

    rerender(
      <CitationDrawer
        citation={{ ...SAMPLE_CITATION, documentId: 'doc-456' }}
        onClose={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('MinIO timeout');
    });
    // Transient 503 falls back to citation.presignedUrl and offers retry button
    expect(screen.getByRole('link', { name: /Mở tài liệu gốc \(Trang 5\)/i })).toHaveAttribute(
      'href',
      SAMPLE_CITATION.presignedUrl,
    );

    await user.click(screen.getByRole('button', { name: /Thử làm mới liên kết/i }));
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /Mở tài liệu gốc \(Trang 5\)/i })).toHaveAttribute(
        'href',
        'http://localhost:8081/api/v1/documents/doc-123/content?token=retried#page=5',
      );
    });
  });

  it('hides stale presignedUrl while /preview-url is loading and does not flash previous citation URL on switch', async () => {
    let resolveFirst: ((res: Response) => void) | undefined;
    let resolveSecond: ((res: Response) => void) | undefined;
    let callIdx = 0;

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        callIdx += 1;
        if (callIdx === 1) {
          return new Promise<Response>((resolve) => {
            resolveFirst = resolve;
          });
        }
        return new Promise<Response>((resolve) => {
          resolveSecond = resolve;
        });
      }),
    );

    const { rerender } = render(<CitationDrawer citation={SAMPLE_CITATION} onClose={vi.fn()} />);

    // Stale presignedUrl must NOT be clickable while live RBAC verification is still in-flight
    expect(screen.getByText(/Đang tạo liên kết xem trước bảo mật/i)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Mở tài liệu gốc/i })).not.toBeInTheDocument();

    await waitFor(() => {
      expect(resolveFirst).toBeTypeOf('function');
    });

    resolveFirst!(
      new Response(
        JSON.stringify({
          documentId: 'doc-123',
          originalName: 'chinh-sach-nghi-phep.pdf',
          mimeType: 'application/pdf',
          fileExtension: 'pdf',
          currentVersion: 3,
          pageNumber: 5,
          previewUrl: 'http://localhost:8081/api/v1/documents/doc-123/content?token=doc1#page=5',
          expiresAt: '2026-11-27T15:00:00Z',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );

    await waitFor(() => {
      expect(screen.getByRole('link', { name: /Mở tài liệu gốc \(Trang 5\)/i })).toHaveAttribute(
        'href',
        'http://localhost:8081/api/v1/documents/doc-123/content?token=doc1#page=5',
      );
    });

    // Switch quickly to Citation B — must immediately hide Citation A's URL while Citation B is loading
    rerender(
      <CitationDrawer
        citation={{
          ...SAMPLE_CITATION,
          documentId: 'doc-789',
          fileName: 'tai-lieu-b.pdf',
          pageNumber: 9,
          presignedUrl: 'http://localhost:8081/api/v1/documents/doc-789/content?token=oldB#page=9',
        }}
        onClose={vi.fn()}
      />,
    );

    expect(screen.queryByRole('link', { name: /Mở tài liệu gốc/i })).not.toBeInTheDocument();

    await waitFor(() => {
      expect(resolveSecond).toBeTypeOf('function');
    });

    resolveSecond!(
      new Response(
        JSON.stringify({
          documentId: 'doc-789',
          originalName: 'tai-lieu-b.pdf',
          mimeType: 'application/pdf',
          fileExtension: 'pdf',
          currentVersion: 1,
          pageNumber: 9,
          previewUrl: 'http://localhost:8081/api/v1/documents/doc-789/content?token=doc2#page=9',
          expiresAt: '2026-11-27T15:00:00Z',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );

    await waitFor(() => {
      expect(screen.getByRole('link', { name: /Mở tài liệu gốc \(Trang 9\)/i })).toHaveAttribute(
        'href',
        'http://localhost:8081/api/v1/documents/doc-789/content?token=doc2#page=9',
      );
    });
  });

  it('blocks fallback on 401/404, supports non-PDF sectionHeading without ?page=, and surfaces refresh timeout AbortError', async () => {
    const requestedUrls: string[] = [];
    let mode: '404' | '401' | 'non-pdf' | 'refresh-timeout' = '404';

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        requestedUrls.push(url);
        if (url.endsWith('/api/v1/auth/refresh')) {
          if (mode === 'refresh-timeout') {
            throw new DOMException('The operation was aborted.', 'AbortError');
          }
          return new Response(
            JSON.stringify({ status: 401, errorCode: 'TOKEN_REVOKED', detail: 'Phiên hết hạn' }),
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        if (mode === '404') {
          return new Response(
            JSON.stringify({ status: 404, title: 'Not Found', detail: 'Tài liệu không tồn tại' }),
            { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        if (mode === '401' || mode === 'refresh-timeout') {
          return new Response(
            JSON.stringify({ status: 401, errorCode: 'TOKEN_EXPIRED', detail: 'Expired' }),
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return new Response(
          JSON.stringify({
            documentId: 'doc-docx',
            originalName: 'so-tay.docx',
            mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            fileExtension: 'docx',
            currentVersion: 2,
            pageNumber: null,
            previewUrl: 'http://localhost:8081/api/v1/documents/doc-docx/content?token=docx',
            expiresAt: '2026-11-27T15:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );

    const { rerender } = render(<CitationDrawer citation={SAMPLE_CITATION} onClose={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Tài liệu không tồn tại');
    });
    expect(screen.queryByRole('link', { name: /Mở tài liệu gốc/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Thử làm mới liên kết/i })).not.toBeInTheDocument();

    mode = '401';
    rerender(
      <CitationDrawer citation={{ ...SAMPLE_CITATION, documentId: 'doc-401' }} onClose={vi.fn()} />,
    );
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Phiên đăng nhập đã hết hạn.');
    });
    expect(screen.queryByRole('link', { name: /Mở tài liệu gốc/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Thử làm mới liên kết/i })).not.toBeInTheDocument();

    setSessionTokens({
      accessToken: 'test-access-token',
      refreshToken: 'test-refresh-token',
      expiresIn: 900,
    });
    mode = 'refresh-timeout';
    rerender(
      <CitationDrawer
        citation={{ ...SAMPLE_CITATION, documentId: 'doc-timeout' }}
        onClose={vi.fn()}
      />,
    );
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });
    expect(screen.queryByText(/Đang tạo liên kết xem trước/i)).not.toBeInTheDocument();

    mode = 'non-pdf';
    rerender(
      <CitationDrawer
        citation={{
          documentId: 'doc-docx',
          docVersion: 2,
          fileName: 'so-tay.docx',
          pageNumber: null,
          sectionHeading: 'Mục 2.1 — Bảo mật',
          similarityScore: 0.9,
          excerpt: 'Nội dung mục 2.1',
          presignedUrl: null,
        }}
        onClose={vi.fn()}
      />,
    );

    await waitFor(() => {
      const link = screen.getByRole('link', { name: 'Mở tài liệu gốc' });
      expect(link).toHaveAttribute(
        'href',
        'http://localhost:8081/api/v1/documents/doc-docx/content?token=docx',
      );
    });
    expect(screen.getByText('Mục 2.1 — Bảo mật')).toBeInTheDocument();
    expect(requestedUrls).toContain(
      'http://localhost:8081/api/v1/documents/doc-docx/preview-url',
    );
  });
});
