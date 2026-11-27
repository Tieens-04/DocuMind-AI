import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  getDocumentPreviewUrl,
  listAdminDepartments,
  listChatMessages,
  streamChatAnswer,
} from '@/lib/api/chat-stream';
import { resetTokenManagerForTests, setSessionTokens } from '@/lib/auth/token-manager';
import { ApiError } from '@/lib/types/problem';

function createSseStream(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(chunk);
      }
      controller.close();
    },
  });
}

describe('chat-stream API & typed SSE parser', () => {
  beforeEach(() => {
    resetTokenManagerForTests();
    vi.stubEnv('NEXT_PUBLIC_API_BASE_URL', 'http://localhost:8081');
    setSessionTokens({
      accessToken: 'initial-access-token',
      refreshToken: 'initial-refresh-token',
      expiresIn: 900,
    });
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('decodes split multi-byte Vietnamese UTF-8 chunks, CRLF boundaries, citations, tokens and done', async () => {
    const encoder = new TextEncoder();
    const citationsPayload = JSON.stringify({
      citations: [
        {
          documentId: 'doc-1',
          docVersion: 2,
          fileName: 'so-tay-nhan-vien.pdf',
          pageNumber: 4,
          sectionHeading: null,
          similarityScore: 0.91,
          excerpt: 'Nhân viên được hưởng 12 ngày phép năm.',
          presignedUrl: 'http://localhost:8081/api/v1/documents/doc-1/content?token=abc#page=4',
        },
      ],
      rewrittenQuery: 'Quy định số ngày phép năm của nhân viên',
    });

    const fullStreamBytes = encoder.encode(
      `: keepalive\r\n\r\nevent: citations\r\ndata: ${citationsPayload}\r\n\r\n` +
        `event: token\r\ndata: {"delta":"Phép năm "}\r\n\r\n` +
        `event: token\r\ndata: {"delta":"là 12 ngày."}\r\n\r\n` +
        `event: done\r\ndata: {"sessionId":"sess-1","messageId":"msg-1","abstained":false,"modelProvider":"groq","cacheHit":true,"cacheTier":"L1","tokenUsage":{"promptTokens":120,"completionTokens":18,"totalTokens":138},"latencyMs":24}\r\n\r\n`,
    );

    // Split across a Vietnamese multi-byte character ("ệ" in "Phép" or "Quy")
    const splitPoint1 = fullStreamBytes.indexOf(0xc3) + 1;
    const splitPoint2 = Math.floor(fullStreamBytes.length * 0.72);
    const chunk1 = fullStreamBytes.slice(0, splitPoint1);
    const chunk2 = fullStreamBytes.slice(splitPoint1, splitPoint2);
    const chunk3 = fullStreamBytes.slice(splitPoint2);

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        expect(String(input)).toBe('http://localhost:8081/api/v1/chat/stream');
        expect(init?.method).toBe('POST');
        expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer initial-access-token');
        expect(JSON.parse(String(init?.body))).toEqual({ question: 'Còn phép năm thì sao?' });
        return new Response(createSseStream([chunk1, chunk2, chunk3]), {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' },
        });
      }),
    );

    const citationsEvents: unknown[] = [];
    const tokens: string[] = [];
    const doneEvents: unknown[] = [];
    const errorEvents: unknown[] = [];

    await streamChatAnswer(
      { question: 'Còn phép năm thì sao?' },
      {
        onCitations: (event) => citationsEvents.push(event),
        onToken: (event) => tokens.push(event.delta),
        onDone: (event) => doneEvents.push(event),
        onErrorEvent: (event) => errorEvents.push(event),
      },
    );

    expect(citationsEvents).toHaveLength(1);
    expect(tokens.join('')).toBe('Phép năm là 12 ngày.');
    expect(doneEvents).toEqual([
      {
        sessionId: 'sess-1',
        messageId: 'msg-1',
        abstained: false,
        modelProvider: 'groq',
        cacheHit: true,
        cacheTier: 'L1',
        tokenUsage: { promptTokens: 120, completionTokens: 18, totalTokens: 138 },
        latencyMs: 24,
      },
    ]);
    expect(errorEvents).toHaveLength(0);
  });

  it('performs single-flight refresh on pre-flight 401 and retries SSE request', async () => {
    const encoder = new TextEncoder();
    let streamCalls = 0;

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith('/api/v1/auth/refresh')) {
          return new Response(
            JSON.stringify({
              accessToken: 'fresh-access-token',
              refreshToken: 'rotated-refresh-token',
              tokenType: 'Bearer',
              expiresIn: 900,
              refreshExpiresIn: 604800,
              user: {
                id: 'u-1',
                email: 'user@documind.ai',
                fullName: 'User',
                role: 'STANDARD_EMPLOYEE',
                departmentId: 'd-1',
                departmentCode: 'HR',
                departmentName: 'HR',
                isActive: true,
                createdAt: '2026-11-26T00:00:00Z',
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }

        streamCalls += 1;
        const auth = new Headers(init?.headers).get('Authorization');
        if (auth === 'Bearer initial-access-token') {
          return new Response(
            JSON.stringify({ status: 401, errorCode: 'TOKEN_EXPIRED', detail: 'Expired' }),
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }

        expect(auth).toBe('Bearer fresh-access-token');
        return new Response(
          createSseStream([
            encoder.encode(
              `event: done\ndata: {"sessionId":"s-2","messageId":"m-2","abstained":true,"modelProvider":null,"cacheHit":false,"cacheTier":"NONE","tokenUsage":{"promptTokens":0,"completionTokens":0,"totalTokens":0},"latencyMs":12}\n\n`,
            ),
          ]),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        );
      }),
    );

    let doneSessionId = '';
    await streamChatAnswer(
      { question: 'Hello', sessionId: 's-2' },
      {
        onDone: (event) => {
          doneSessionId = event.sessionId;
        },
      },
    );

    expect(streamCalls).toBe(2);
    expect(doneSessionId).toBe('s-2');
  });

  it('throws ApiError with Retry-After on 429 and surfaces premature EOF as stream interruption', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        return new Response(
          JSON.stringify({
            status: 429,
            title: 'Too Many Requests',
            detail: 'Rate limit exceeded',
            errorCode: 'RATE_LIMIT_EXCEEDED',
            retryAfterSeconds: 42,
          }),
          {
            status: 429,
            headers: {
              'Content-Type': 'application/problem+json',
              'Retry-After': '42',
            },
          },
        );
      }),
    );

    await expect(streamChatAnswer({ question: 'Spam' }, {})).rejects.toMatchObject({
      status: 429,
      errorCode: 'RATE_LIMIT_EXCEEDED',
      retryAfterSeconds: 42,
    } satisfies Partial<ApiError>);

    const encoder = new TextEncoder();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        return new Response(
          createSseStream([encoder.encode(`event: token\ndata: {"delta":"Dở dang"}\n\n`)]),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        );
      }),
    );

    const errors: string[] = [];
    await streamChatAnswer(
      { question: 'Interrupted' },
      {
        onErrorEvent: (event) => errors.push(event.errorCode),
      },
    );
    expect(errors).toEqual(['LLM_STREAM_INTERRUPTED']);

    const abortController = new AbortController();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        return new Response(
          new ReadableStream<Uint8Array>({
            start(controller) {
              controller.enqueue(encoder.encode(`event: token\ndata: {"delta":"Đang đọc"}\n\n`));
            },
          }),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        );
      }),
    );

    const streamPromise = streamChatAnswer(
      { question: 'Cancel me' },
      {
        onToken: () => abortController.abort(),
      },
      abortController.signal,
    );
    await expect(streamPromise).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('fetches session messages, document preview url with optional page, and admin departments', async () => {
    const requestedUrls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        requestedUrls.push(url);
        if (url.includes('/messages')) {
          return new Response(JSON.stringify([]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        if (url.includes('/preview-url')) {
          return new Response(
            JSON.stringify({
              documentId: 'doc-9',
              originalName: 'policy.pdf',
              mimeType: 'application/pdf',
              fileExtension: 'pdf',
              currentVersion: 1,
              pageNumber: 7,
              previewUrl: 'http://localhost:8081/api/v1/documents/doc-9/content?token=t#page=7',
              expiresAt: '2026-11-27T14:00:00Z',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }
        return new Response(
          JSON.stringify({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );

    await listChatMessages('sess-10');
    const preview = await getDocumentPreviewUrl('doc-9', 7);
    await getDocumentPreviewUrl('doc-9', null);
    await listAdminDepartments();

    expect(preview.pageNumber).toBe(7);
    expect(requestedUrls).toEqual([
      'http://localhost:8081/api/v1/chat/sessions/sess-10/messages',
      'http://localhost:8081/api/v1/documents/doc-9/preview-url?page=7',
      'http://localhost:8081/api/v1/documents/doc-9/preview-url',
      'http://localhost:8081/api/v1/admin/departments?page=0&size=100',
    ]);
  });

  it('handles SSE error events, ignores unknown non-JSON proxy events, cancels reader on malformed JSON, and stops buffered blocks on abort', async () => {
    const encoder = new TextEncoder();

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        return new Response(
          createSseStream([
            encoder.encode(
              `event: ping\ndata: ok\n\n` +
                `event: error\ndata: {"errorCode":"LLM_PROVIDER_UNAVAILABLE","message":"Upstream down","provider":"groq","retryable":true}\n\n`,
            ),
          ]),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        );
      }),
    );

    const sseErrors: unknown[] = [];
    await streamChatAnswer(
      { question: 'Test error event' },
      { onErrorEvent: (event) => sseErrors.push(event) },
    );
    expect(sseErrors).toEqual([
      {
        errorCode: 'LLM_PROVIDER_UNAVAILABLE',
        message: 'Upstream down',
        provider: 'groq',
        retryable: true,
      },
    ]);

    const cancelSpy = vi.fn(async () => {});
    const malformedBody = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(`event: token\ndata: {broken-json\n\n`));
      },
      cancel: cancelSpy,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(malformedBody, { status: 200 })),
    );

    await expect(streamChatAnswer({ question: 'Malformed' }, {})).rejects.toThrow(
      'Không thể giải mã sự kiện SSE "token".',
    );
    expect(cancelSpy).toHaveBeenCalledTimes(1);

    const abortController = new AbortController();
    const receivedTokens: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        return new Response(
          createSseStream([
            encoder.encode(
              `event: token\ndata: {"delta":"First"}\n\n` +
                `event: token\ndata: {"delta":"SecondShouldNotDispatch"}\n\n`,
            ),
          ]),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        );
      }),
    );

    await expect(
      streamChatAnswer(
        { question: 'Abort mid-chunk' },
        {
          onToken: (event) => {
            receivedTokens.push(event.delta);
            abortController.abort();
          },
        },
        abortController.signal,
      ),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(receivedTokens).toEqual(['First']);
  });

  it('propagates 401 refresh failure and parses header-only Retry-After on 429', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith('/api/v1/auth/refresh')) {
          return new Response(
            JSON.stringify({ status: 401, errorCode: 'TOKEN_REVOKED', detail: 'Revoked' }),
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return new Response(
          JSON.stringify({ status: 401, errorCode: 'TOKEN_EXPIRED', detail: 'Expired' }),
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    await expect(streamChatAnswer({ question: 'Auth fail' }, {})).rejects.toMatchObject({
      status: 401,
      errorCode: 'TOKEN_REVOKED',
    } satisfies Partial<ApiError>);

    setSessionTokens({
      accessToken: 'initial-access-token',
      refreshToken: 'initial-refresh-token',
      expiresIn: 900,
    });

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        return new Response('', {
          status: 429,
          headers: { 'Retry-After': '17' },
        });
      }),
    );

    await expect(streamChatAnswer({ question: 'Rate limited' }, {})).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 17,
    } satisfies Partial<ApiError>);
  });
});
