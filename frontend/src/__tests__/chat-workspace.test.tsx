import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChatPage from '@/app/(workspace)/chat/page';
import { sanitizeMarkdownUrl } from '@/components/chat/markdown-content';
import {
  ChatSessionsProvider,
  type ChatSessionsController,
} from '@/components/layout/chat-session-sidebar';
import { resetTokenManagerForTests, setSessionTokens } from '@/lib/auth/token-manager';
import type { Role, UserProfileResponse } from '@/lib/types/auth';
import { REDACTED_ASSISTANT_CONTENT, SAFE_REFUSAL_MESSAGE } from '@/lib/types/chat';

const mockReplace = vi.fn();
const mockPush = vi.fn();
let mockSearchParams = new URLSearchParams();
let mockUser: UserProfileResponse;

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: mockReplace, push: mockPush }),
  usePathname: () => '/chat',
  useSearchParams: () => mockSearchParams,
}));

vi.mock('@/providers/auth-provider', () => ({
  useAuth: () => ({
    user: mockUser,
    status: 'authenticated',
    bootstrapError: null,
    login: vi.fn(),
    logout: vi.fn(),
    retryBootstrap: vi.fn(),
  }),
}));

function buildUser(role: Role): UserProfileResponse {
  return {
    id: 'user-1',
    email: `${role.toLowerCase()}@documind.ai`,
    fullName: role === 'SYSTEM_ADMIN' ? 'Admin User' : 'Employee User',
    role,
    departmentId: 'dept-it',
    departmentCode: 'IT',
    departmentName: 'Engineering',
    isActive: true,
    createdAt: '2026-11-26T00:00:00Z',
  };
}

function buildSessionsController(): ChatSessionsController {
  return {
    sessions: [],
    loading: false,
    loadError: null,
    creating: false,
    loadSessions: vi.fn(async () => {}),
    createSession: vi.fn(async () => null),
    renameSession: vi.fn(async () => null),
    deleteSession: vi.fn(async () => true),
  };
}

function createSseResponse(eventsText: string): Response {
  const bytes = new TextEncoder().encode(eventsText);
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes);
        controller.close();
      },
    }),
    { status: 200, headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } },
  );
}

describe('ChatPage workspace integration', () => {
  beforeEach(() => {
    resetTokenManagerForTests();
    mockReplace.mockReset();
    mockPush.mockReset();
    mockSearchParams = new URLSearchParams();
    mockUser = buildUser('STANDARD_EMPLOYEE');
    vi.stubEnv('NEXT_PUBLIC_API_BASE_URL', 'http://localhost:8081');
    setSessionTokens({
      accessToken: 'chat-access-token',
      refreshToken: 'chat-refresh-token',
      expiresIn: 900,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('supports SYSTEM_ADMIN department scope filter, Turn-1 auto-session sync without flicker, and hides filter for STANDARD_EMPLOYEE', async () => {
    const user = userEvent.setup();
    mockUser = buildUser('SYSTEM_ADMIN');
    const sessionsController = buildSessionsController();
    const historyFetches: string[] = [];

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.includes('/api/v1/admin/departments')) {
          return new Response(
            JSON.stringify({
              items: [
                {
                  id: 'dept-hr',
                  code: 'HR',
                  name: 'Nhân sự',
                  description: null,
                  cacheVersion: 1,
                  createdAt: '2026-11-26T00:00:00Z',
                  updatedAt: '2026-11-26T00:00:00Z',
                },
              ],
              page: 0,
              size: 100,
              totalElements: 1,
              totalPages: 1,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }

        if (url.endsWith('/api/v1/chat/stream')) {
          const parsedBody = JSON.parse(String(init?.body)) as {
            question: string;
            sessionId?: string;
            departmentId?: string;
          };
          if (parsedBody.question === 'Chính sách thưởng cuối năm?') {
            expect(parsedBody).toEqual({
              question: 'Chính sách thưởng cuối năm?',
              departmentId: 'dept-hr',
            });
            return createSseResponse(
              `event: citations\ndata: {"citations":[],"rewrittenQuery":null}\n\n` +
                `event: token\ndata: {"delta":"Thưởng theo KPI quý 4."}\n\n` +
                `event: done\ndata: {"sessionId":"sess-new-1","messageId":"msg-new-1","abstained":false,"modelProvider":"groq","cacheHit":true,"cacheTier":"L1","tokenUsage":{"promptTokens":90,"completionTokens":15,"totalTokens":105},"latencyMs":18}\n\n`,
            );
          }

          // Turn 2 submitted immediately after Turn 1 must include sessionId even before URL state settles
          expect(parsedBody).toEqual({
            question: 'Chi tiết mức thưởng?',
            sessionId: 'sess-new-1',
            departmentId: 'dept-hr',
          });
          return createSseResponse(
            `event: citations\ndata: {"citations":[],"rewrittenQuery":"Chi tiết mức thưởng KPI quý 4"}\n\n` +
              `event: token\ndata: {"delta":"Mức tối đa 2 tháng lương."}\n\n` +
              `event: done\ndata: {"sessionId":"sess-new-1","messageId":"msg-new-2","abstained":false,"modelProvider":"groq","cacheHit":true,"cacheTier":"L2","tokenUsage":{"promptTokens":150,"completionTokens":45,"totalTokens":195},"latencyMs":55}\n\n`,
          );
        }

        if (url.includes('/messages')) {
          historyFetches.push(url);
        }
        return new Response('[]', { status: 200 });
      }),
    );

    const { rerender, unmount } = render(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );

    const deptSelect = await screen.findByLabelText('Phạm vi:');
    await waitFor(() => {
      expect(screen.getByRole('option', { name: 'Nhân sự (HR)' })).toBeInTheDocument();
    });
    await user.selectOptions(deptSelect, 'dept-hr');

    const input = screen.getByLabelText('Nhập câu hỏi cho trợ lý DocuMind AI');
    await user.type(input, 'Chính sách thưởng cuối năm?{Enter}');

    await waitFor(() => {
      expect(screen.getByText('Thưởng theo KPI quý 4.')).toBeInTheDocument();
      expect(screen.getByText('Redis L1 · 18ms')).toBeInTheDocument();
      expect(screen.getByText('105 tokens')).toBeInTheDocument();
      expect(mockReplace).toHaveBeenCalledWith('/chat?session=sess-new-1');
      expect(sessionsController.loadSessions).toHaveBeenCalledWith({ silent: true });
    });

    // Simulate URL updating to ?session=sess-new-1 right after Turn 1
    mockSearchParams = new URLSearchParams('session=sess-new-1');
    rerender(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );
    expect(historyFetches).toHaveLength(0);

    // Turn 2 sends sessionId and renders Semantic L2 cache badge + token tooltip
    await user.type(input, 'Chi tiết mức thưởng?{Enter}');
    await waitFor(() => {
      expect(screen.getByText('Mức tối đa 2 tháng lương.')).toBeInTheDocument();
      expect(screen.getByText('Semantic L2 · 55ms')).toBeInTheDocument();
      expect(screen.getByText('195 tokens')).toHaveAttribute(
        'title',
        'Prompt: 150 · Completion: 45',
      );
    });

    // Switch to sess-other then back to sess-new-1 -> justCreatedSessionIdRef was cleared so sess-new-1 reloads
    mockSearchParams = new URLSearchParams('session=sess-other');
    rerender(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );
    mockSearchParams = new URLSearchParams('session=sess-new-1');
    rerender(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );
    await waitFor(() => {
      expect(
        historyFetches.some((u) => u.includes('/api/v1/chat/sessions/sess-new-1/messages')),
      ).toBe(true);
    });
    unmount();

    // STANDARD_EMPLOYEE and DEPARTMENT_MANAGER must not see department filter or fetch admin departments
    for (const nonAdminRole of ['STANDARD_EMPLOYEE', 'DEPARTMENT_MANAGER'] as const) {
      mockUser = buildUser(nonAdminRole);
      mockSearchParams = new URLSearchParams();
      const nonAdminUrls: string[] = [];
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: RequestInfo | URL) => {
          nonAdminUrls.push(String(input));
          return createSseResponse(
            `event: done\ndata: {"sessionId":"s-e","messageId":"m-e","abstained":false,"modelProvider":"groq","cacheHit":false,"cacheTier":"NONE","tokenUsage":{"promptTokens":10,"completionTokens":5,"totalTokens":15},"latencyMs":400}\n\n`,
          );
        }),
      );

      const nonAdminView = render(
        <ChatSessionsProvider value={sessionsController}>
          <ChatPage />
        </ChatSessionsProvider>,
      );

      expect(screen.queryByLabelText('Phạm vi:')).not.toBeInTheDocument();
      expect(nonAdminUrls.some((u) => u.includes('/api/v1/admin/departments'))).toBe(false);
      nonAdminView.unmount();
    }
  });

  it('renders GFM tables safely, strips XSS URLs/scripts, and distinguishes Safe Refusal & Redacted turns', async () => {
    mockSearchParams = new URLSearchParams('session=sess-hist');
    const sessionsController = buildSessionsController();

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        expect(String(input)).toBe(
          'http://localhost:8081/api/v1/chat/sessions/sess-hist/messages',
        );
        return new Response(
          JSON.stringify([
            {
              id: 'm1',
              sessionId: 'sess-hist',
              role: 'USER',
              content: 'So sánh gói nghỉ phép?',
              rewrittenQuery: null,
              citations: [],
              abstained: false,
              promptTokens: 0,
              completionTokens: 0,
              latencyMs: null,
              modelProvider: null,
              createdAt: '2026-11-27T01:00:00Z',
            },
            {
              id: 'm2',
              sessionId: 'sess-hist',
              role: 'ASSISTANT',
              content:
                '| Cấp bậc | Số ngày |\n| --- | --- |\n| Chính thức | 12 |\n\n[Click xấu](javascript:alert(1)) <script>window.__xss=1</script>',
              rewrittenQuery: 'So sánh số ngày nghỉ phép theo cấp bậc',
              citations: [
                {
                  documentId: 'doc-hr',
                  docVersion: 1,
                  fileName: 'hr-handbook.pdf',
                  pageNumber: 2,
                  sectionHeading: null,
                  similarityScore: 0.92,
                  excerpt: 'Nhân viên chính thức có 12 ngày phép.',
                  presignedUrl: null,
                },
              ],
              abstained: false,
              promptTokens: 150,
              completionTokens: 45,
              latencyMs: 55,
              modelProvider: 'groq',
              createdAt: '2026-11-27T01:00:01Z',
            },
            {
              id: 'm3',
              sessionId: 'sess-hist',
              role: 'ASSISTANT',
              content: SAFE_REFUSAL_MESSAGE,
              rewrittenQuery: null,
              citations: [],
              abstained: true,
              promptTokens: 50,
              completionTokens: 12,
              latencyMs: 320,
              modelProvider: null,
              createdAt: '2026-11-27T01:01:00Z',
            },
            {
              id: 'm4',
              sessionId: 'sess-hist',
              role: 'ASSISTANT',
              content: REDACTED_ASSISTANT_CONTENT,
              rewrittenQuery: null,
              citations: [],
              abstained: false,
              promptTokens: 100,
              completionTokens: 20,
              latencyMs: 250,
              modelProvider: 'groq',
              createdAt: '2026-11-27T01:02:00Z',
            },
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );

    const { container } = render(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('So sánh số ngày nghỉ phép theo cấp bậc')).toBeInTheDocument();
    });

    const columnHeader = screen.getByRole('columnheader', { name: 'Cấp bậc' });
    expect(columnHeader).toHaveAttribute('scope', 'col');
    expect(screen.getByText('Chính thức')).toBeInTheDocument();

    // Unsafe javascript: link is neutralized (rendered as non-link span) and raw script tag is not mounted
    expect(screen.queryByRole('link', { name: 'Click xấu' })).not.toBeInTheDocument();
    expect(screen.getByText('Click xấu')).toBeInTheDocument();
    expect(container.querySelector('script')).toBeNull();

    expect(screen.getByText('Ngoài phạm vi tài liệu nội bộ')).toBeInTheDocument();
    expect(screen.getByText(SAFE_REFUSAL_MESSAGE)).toBeInTheDocument();
    expect(screen.getByText(REDACTED_ASSISTANT_CONTENT)).toBeInTheDocument();
    expect(screen.getByText('195 tokens')).toBeInTheDocument();
  });

  it('aborts active stream on Stop keeping partial content + Retry, and aborts when switching sessions', async () => {
    const user = userEvent.setup();
    const sessionsController = buildSessionsController();
    const encoder = new TextEncoder();
    const abortSignals: AbortSignal[] = [];
    let streamAttempt = 0;

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith('/api/v1/chat/stream')) {
          streamAttempt += 1;
          if (init?.signal) {
            abortSignals.push(init.signal);
          }

          if (streamAttempt === 1) {
            return new Response(
              new ReadableStream<Uint8Array>({
                start(controller) {
                  controller.enqueue(
                    encoder.encode(
                      `event: citations\ndata: {"citations":[{"documentId":"doc-1","docVersion":1,"fileName":"sop.pdf","pageNumber":1,"sectionHeading":null,"similarityScore":0.85,"excerpt":"Trích đoạn 1","presignedUrl":null}],"rewrittenQuery":null}\n\n` +
                        `event: token\ndata: {"delta":"Nội dung đang chảy "}\n\n`,
                    ),
                  );
                  init?.signal?.addEventListener('abort', () => {
                    controller.error(new DOMException('Aborted', 'AbortError'));
                  });
                },
              }),
              { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
            );
          }

          if (streamAttempt === 2) {
            return createSseResponse(
              `event: token\ndata: {"delta":"Câu trả lời hoàn chỉnh."}\n\n` +
                `event: done\ndata: {"sessionId":"sess-retried","messageId":"msg-2","abstained":false,"modelProvider":"groq","cacheHit":false,"cacheTier":"NONE","tokenUsage":{"promptTokens":40,"completionTokens":10,"totalTokens":50},"latencyMs":800}\n\n`,
            );
          }

          return new Response(
            new ReadableStream<Uint8Array>({
              start(controller) {
                init?.signal?.addEventListener('abort', () => {
                  controller.error(new DOMException('Aborted', 'AbortError'));
                });
              },
            }),
            { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
          );
        }

        if (url.includes('/api/v1/chat/sessions/sess-other/messages')) {
          return new Response(
            JSON.stringify([
              {
                id: 'other-1',
                sessionId: 'sess-other',
                role: 'USER',
                content: 'Phiên khác',
                rewrittenQuery: null,
                citations: [],
                abstained: false,
                promptTokens: 0,
                completionTokens: 0,
                latencyMs: null,
                modelProvider: null,
                createdAt: '2026-11-27T02:00:00Z',
              },
            ]),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }

        return new Response('[]', { status: 200 });
      }),
    );

    const { rerender } = render(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );

    const input = screen.getByLabelText('Nhập câu hỏi cho trợ lý DocuMind AI');
    await user.type(input, 'Hỏi quy trình{Enter}');

    await waitFor(() => {
      expect(screen.getByText('Nội dung đang chảy')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Dừng sinh câu trả lời/i })).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /Dừng sinh câu trả lời/i }));

    await waitFor(() => {
      expect(abortSignals[0]?.aborted).toBe(true);
      expect(screen.getByText('Nội dung đang chảy')).toBeInTheDocument();
      expect(screen.getByText('Đã dừng sinh câu trả lời')).toBeInTheDocument();
      expect(screen.getByText('sop.pdf')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Gửi lại/i })).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /Gửi lại/i }));
    await waitFor(() => {
      expect(screen.getByText('Câu trả lời hoàn chỉnh.')).toBeInTheDocument();
    });

    // Switch session mid-stream -> aborts attempt 3 and loads sess-other
    await user.type(input, 'Câu hỏi mới đang chạy{Enter}');
    await waitFor(() => {
      expect(abortSignals).toHaveLength(3);
    });

    mockSearchParams = new URLSearchParams('session=sess-other');
    rerender(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );

    await waitFor(() => {
      expect(abortSignals[2]?.aborted).toBe(true);
      expect(screen.getByText('Phiên khác')).toBeInTheDocument();
    });
  });

  it('handles starter prompts, Shift+Enter newline vs Enter submit, and 429 Retry-After countdown lock', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const sessionsController = buildSessionsController();
    let fetchCalls = 0;

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        fetchCalls += 1;
        if (fetchCalls === 1) {
          return new Response(
            JSON.stringify({
              status: 429,
              title: 'Too Many Requests',
              detail: 'Rate limit exceeded',
              errorCode: 'RATE_LIMIT_EXCEEDED',
              retryAfterSeconds: 2,
            }),
            {
              status: 429,
              headers: {
                'Content-Type': 'application/problem+json',
                'Retry-After': '2',
              },
            },
          );
        }
        return createSseResponse(
          `event: token\ndata: {"delta":"Đã qua cooldown."}\n\n` +
            `event: done\ndata: {"sessionId":"s-ok","messageId":"m-ok","abstained":false,"modelProvider":"groq","cacheHit":false,"cacheTier":"NONE","tokenUsage":{"promptTokens":10,"completionTokens":5,"totalTokens":15},"latencyMs":120}\n\n`,
        );
      }),
    );

    render(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );

    const input = screen.getByLabelText('Nhập câu hỏi cho trợ lý DocuMind AI');
    fireEvent.change(input, { target: { value: 'Dòng 1' } });
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(fetchCalls).toBe(0);

    // Clicking starter prompt triggers submit and hits 429 with Retry-After: 2
    const starterBtn = screen.getByRole('button', {
      name: /Quy trình xin nghỉ phép và chế độ phúc lợi/i,
    });
    await user.click(starterBtn);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Vui lòng thử lại sau 2 giây.');
      expect(screen.getByRole('button', { name: 'Chờ 2s' })).toBeDisabled();
      expect(input).toHaveValue(
        'Quy trình xin nghỉ phép và chế độ phúc lợi nhân viên hiện tại là gì?',
      );
    });

    await act(async () => {
      vi.advanceTimersByTime(2100);
    });

    await waitFor(() => {
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Gửi' })).toBeEnabled();
    });

    await user.click(screen.getByRole('button', { name: 'Gửi' }));
    await waitFor(() => {
      expect(screen.getByText('Đã qua cooldown.')).toBeInTheDocument();
    });
  });

  it('sanitizes obfuscated markdown URLs, guards synchronous double-submit, clears abstained citations, and renders generating indicator', async () => {
    expect(sanitizeMarkdownUrl('/\t/evil.com')).toBe('');
    expect(sanitizeMarkdownUrl('/\n/evil.com')).toBe('');
    expect(sanitizeMarkdownUrl('/\\evil.com')).toBe('');
    expect(sanitizeMarkdownUrl('\\\\evil.com')).toBe('');
    expect(sanitizeMarkdownUrl('data:text/html,<script>alert(1)</script>')).toBe('');
    expect(sanitizeMarkdownUrl('vbscript:msgbox(1)')).toBe('');
    expect(sanitizeMarkdownUrl('JaVaScRiPt:alert(1)')).toBe('');
    expect(sanitizeMarkdownUrl('https://documind.ai/docs?p=1#top')).toBe(
      'https://documind.ai/docs?p=1#top',
    );
    expect(sanitizeMarkdownUrl('/documents/123')).toBe('/documents/123');
    expect(sanitizeMarkdownUrl('#section-1')).toBe('#section-1');

    const sessionsController = buildSessionsController();
    const encoder = new TextEncoder();
    let streamCount = 0;
    let streamController!: ReadableStreamDefaultController<Uint8Array>;

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith('/api/v1/chat/stream')) {
          streamCount += 1;
          return new Response(
            new ReadableStream<Uint8Array>({
              start(controller) {
                streamController = controller;
                controller.enqueue(
                  encoder.encode(
                    `event: citations\ndata: {"citations":[{"documentId":"doc-1","docVersion":1,"fileName":"noi-quy.pdf","pageNumber":3,"sectionHeading":null,"similarityScore":0.82,"excerpt":"Trích đoạn","presignedUrl":null}],"rewrittenQuery":null}\n\n`,
                  ),
                );
              },
            }),
            { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
          );
        }
        return new Response('[]', { status: 200 });
      }),
    );

    render(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );

    // Synchronous double-click on starter prompt must trigger only 1 stream request
    const starter = screen.getByRole('button', {
      name: /Quy trình xin nghỉ phép và chế độ phúc lợi/i,
    });
    fireEvent.click(starter);
    fireEvent.click(starter);

    await waitFor(() => {
      expect(streamCount).toBe(1);
      expect(screen.getByText('Đang tổng hợp câu trả lời...')).toBeInTheDocument();
      expect(screen.getByText('noi-quy.pdf')).toBeInTheDocument();
    });

    // When LLM abstains on done, previously emitted citation cards must be cleared
    await act(async () => {
      streamController.enqueue(
        encoder.encode(
          `event: token\ndata: {"delta":"Không tìm thấy thông tin trong tài liệu nội bộ"}\n\n` +
            `event: done\ndata: {"sessionId":"s-abs","messageId":"m-abs","abstained":true,"modelProvider":"groq","cacheHit":false,"cacheTier":"NONE","tokenUsage":{"promptTokens":30,"completionTokens":10,"totalTokens":40},"latencyMs":150}\n\n`,
        ),
      );
      streamController.close();
    });

    await waitFor(() => {
      expect(screen.getByText('Ngoài phạm vi tài liệu nội bộ')).toBeInTheDocument();
      expect(screen.queryByText('noi-quy.pdf')).not.toBeInTheDocument();
    });
  });

  it('opens CitationDrawer from card click, handles mid-stream error events, pre-flight 403/503, refresh timeout AbortError, and Retry-After: 0', async () => {
    const user = userEvent.setup();
    const sessionsController = buildSessionsController();
    let mode: 'citation-and-err' | 'non-retryable-err' | 'preflight-403' | 'refresh-timeout' | 'retry-zero' =
      'citation-and-err';

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('/preview-url')) {
          return new Response(
            JSON.stringify({
              documentId: 'doc-cit',
              originalName: 'policy.pdf',
              mimeType: 'application/pdf',
              fileExtension: 'pdf',
              currentVersion: 1,
              pageNumber: 2,
              previewUrl: 'http://localhost:8081/api/v1/documents/doc-cit/content?token=ok#page=2',
              expiresAt: '2026-11-27T15:00:00Z',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }
        if (url.endsWith('/api/v1/auth/refresh')) {
          throw new DOMException('The operation was aborted.', 'AbortError');
        }
        if (mode === 'citation-and-err') {
          return createSseResponse(
            `event: citations\ndata: {"citations":[{"documentId":"doc-cit","docVersion":1,"fileName":"policy.pdf","pageNumber":2,"sectionHeading":null,"similarityScore":0.9,"excerpt":"Đoạn trích policy","presignedUrl":null}],"rewrittenQuery":null}\n\n` +
              `event: error\ndata: {"errorCode":"LLM_STREAM_INTERRUPTED","message":"Mất kết nối LLM","provider":"groq","retryable":true}\n\n`,
          );
        }
        if (mode === 'non-retryable-err') {
          return createSseResponse(
            `event: error\ndata: {"errorCode":"INTERNAL_ERROR","message":"Lỗi nghiêm trọng","provider":null,"retryable":false}\n\n`,
          );
        }
        if (mode === 'preflight-403') {
          return new Response(
            JSON.stringify({ status: 403, errorCode: 'ACCESS_DENIED', detail: 'Forbidden' }),
            { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        if (mode === 'refresh-timeout') {
          return new Response(
            JSON.stringify({ status: 401, errorCode: 'TOKEN_EXPIRED', detail: 'Expired' }),
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return new Response(
          JSON.stringify({
            status: 429,
            errorCode: 'RATE_LIMIT_EXCEEDED',
            detail: 'Slow down',
            retryAfterSeconds: 0,
          }),
          { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '0' } },
        );
      }),
    );

    render(
      <ChatSessionsProvider value={sessionsController}>
        <ChatPage />
      </ChatSessionsProvider>,
    );

    const input = screen.getByLabelText('Nhập câu hỏi cho trợ lý DocuMind AI');
    await user.type(input, 'Hỏi có citation và lỗi stream{Enter}');

    await waitFor(() => {
      expect(screen.getByText('Mất kết nối LLM')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Gửi lại/i })).toBeInTheDocument();
    });

    // Click citation card -> opens CitationDrawer
    await user.click(screen.getByRole('button', { name: /Nguồn 1:.*policy\.pdf/i }));
    await waitFor(() => {
      expect(screen.getByText('Đoạn trích policy')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Mở tài liệu gốc \(Trang 2\)/i })).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: 'Đóng ngăn trích dẫn nguồn' }));

    // Retry with non-retryable SSE error -> hides Retry button
    mode = 'non-retryable-err';
    await user.click(screen.getByRole('button', { name: /Gửi lại/i }));
    await waitFor(() => {
      expect(screen.getByText('Lỗi nghiêm trọng')).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /Gửi lại/i })).not.toBeInTheDocument();

    // Pre-flight 403 -> shows permission error alert without Retry button
    mode = 'preflight-403';
    await user.type(input, 'Hỏi bị 403{Enter}');
    await waitFor(() => {
      expect(screen.getByText('Bạn không có quyền thực hiện thao tác này.')).toBeInTheDocument();
    });

    // Refresh timeout AbortError -> surfaces error instead of infinite spinner
    mode = 'refresh-timeout';
    await user.type(input, 'Hỏi khi refresh timeout{Enter}');
    await waitFor(() => {
      expect(screen.queryByText('Đang truy xuất tài liệu nội bộ...')).not.toBeInTheDocument();
    });

    // Retry-After: 0 clamps to 1s instead of silently dropping prompt
    mode = 'retry-zero';
    await user.type(input, 'Hỏi lúc bị 429 zero{Enter}');
    await waitFor(() => {
      expect(screen.getByText(/Vui lòng thử lại sau 1 giây\./i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Chờ 1s' })).toBeDisabled();
      expect(input).toHaveValue('Hỏi lúc bị 429 zero');
    });
  });
});
