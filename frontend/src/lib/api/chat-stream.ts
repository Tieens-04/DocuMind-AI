import { listAdminDepartmentsPage } from '@/lib/api/admin';
import { apiFetch } from '@/lib/api/client';
import { ensureValidAccessToken, refreshAccessToken } from '@/lib/auth/token-manager';
import { getApiBaseUrl } from '@/lib/env';
import type {
  AdminDepartmentOption,
  ChatAskRequest,
  ChatMessageResponse,
  ChatStreamCitationsEvent,
  ChatStreamDoneEvent,
  ChatStreamErrorEvent,
  ChatStreamTokenEvent,
  DocumentPreviewUrlResponse,
  PageResponse,
} from '@/lib/types/chat';
import { parseApiErrorResponse } from '@/lib/types/problem';

export interface ChatStreamHandlers {
  onCitations?: (event: ChatStreamCitationsEvent) => void;
  onToken?: (event: ChatStreamTokenEvent) => void;
  onDone?: (event: ChatStreamDoneEvent) => void;
  onErrorEvent?: (event: ChatStreamErrorEvent) => void;
}

function buildApiUrl(path: string): string {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${getApiBaseUrl()}${normalizedPath}`;
}

function parseSseFieldValue(raw: string): string {
  return raw.startsWith(' ') ? raw.slice(1) : raw;
}

function dispatchSseBlock(
  rawBlock: string,
  handlers: ChatStreamHandlers,
): 'done' | 'error' | 'continue' {
  const lines = rawBlock.split('\n');
  let eventName = 'message';
  const dataLines: string[] = [];

  for (const line of lines) {
    if (!line || line.startsWith(':')) {
      continue;
    }
    if (line.startsWith('event:')) {
      eventName = parseSseFieldValue(line.slice(6)).trim() || 'message';
    } else if (line.startsWith('data:')) {
      dataLines.push(parseSseFieldValue(line.slice(5)));
    }
  }

  if (dataLines.length === 0) {
    return 'continue';
  }

  if (
    eventName !== 'citations' &&
    eventName !== 'token' &&
    eventName !== 'done' &&
    eventName !== 'error'
  ) {
    return 'continue';
  }

  const payloadText = dataLines.join('\n');
  let parsed: unknown;
  try {
    parsed = JSON.parse(payloadText);
  } catch {
    throw new Error(`Không thể giải mã sự kiện SSE "${eventName}".`);
  }

  switch (eventName) {
    case 'citations':
      handlers.onCitations?.(parsed as ChatStreamCitationsEvent);
      return 'continue';
    case 'token':
      handlers.onToken?.(parsed as ChatStreamTokenEvent);
      return 'continue';
    case 'done':
      handlers.onDone?.(parsed as ChatStreamDoneEvent);
      return 'done';
    case 'error':
      handlers.onErrorEvent?.(parsed as ChatStreamErrorEvent);
      return 'error';
    default:
      return 'continue';
  }
}

export async function listChatMessages(
  sessionId: string,
  signal?: AbortSignal,
): Promise<ChatMessageResponse[]> {
  return apiFetch<ChatMessageResponse[]>(
    `/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    { method: 'GET', signal },
  );
}

export async function getDocumentPreviewUrl(
  documentId: string,
  page?: number | null,
  signal?: AbortSignal,
): Promise<DocumentPreviewUrlResponse> {
  const query =
    typeof page === 'number' && Number.isInteger(page) && page >= 1 ? `?page=${page}` : '';
  return apiFetch<DocumentPreviewUrlResponse>(
    `/api/v1/documents/${encodeURIComponent(documentId)}/preview-url${query}`,
    { method: 'GET', signal },
  );
}

export async function listAdminDepartments(
  signal?: AbortSignal,
): Promise<PageResponse<AdminDepartmentOption>> {
  return listAdminDepartmentsPage({ page: 0, size: 100 }, signal);
}

export async function streamChatAnswer(
  request: ChatAskRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const targetUrl = buildApiUrl('/api/v1/chat/stream');
  const payload: Record<string, string> = { question: request.question };
  if (request.sessionId) {
    payload.sessionId = request.sessionId;
  }
  if (request.departmentId) {
    payload.departmentId = request.departmentId;
  }
  const serializedBody = JSON.stringify(payload);

  const execute = (token: string | null) => {
    const headers = new Headers({
      Accept: 'text/event-stream, application/problem+json',
      'Content-Type': 'application/json',
    });
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    return fetch(targetUrl, {
      method: 'POST',
      headers,
      body: serializedBody,
      signal,
    });
  };

  let usedToken = await ensureValidAccessToken();
  let response = await execute(usedToken);

  if (response.status === 401 && !signal?.aborted) {
    usedToken = await refreshAccessToken(usedToken ?? undefined);
    response = await execute(usedToken);
  }

  if (!response.ok) {
    throw await parseApiErrorResponse(response);
  }

  if (!response.body) {
    throw new Error('Máy chủ không hỗ trợ đọc luồng phản hồi SSE.');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let trailingCr = false;
  let terminalReached = false;
  let streamDone = false;

  const normalizeChunk = (text: string, flush = false): string => {
    let input = trailingCr ? `\r${text}` : text;
    trailingCr = false;
    if (!flush && input.endsWith('\r')) {
      trailingCr = true;
      input = input.slice(0, -1);
    }
    return input.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  };

  const onAbort = () => {
    void reader.cancel().catch(() => {});
  };
  signal?.addEventListener('abort', onAbort, { once: true });

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        streamDone = true;
        break;
      }
      if (signal?.aborted) {
        break;
      }
      buffer += normalizeChunk(decoder.decode(value, { stream: true }));

      let separatorIndex = buffer.indexOf('\n\n');
      while (separatorIndex !== -1) {
        if (signal?.aborted) {
          break;
        }
        const block = buffer.slice(0, separatorIndex);
        buffer = buffer.slice(separatorIndex + 2);
        if (block.trim().length > 0) {
          const outcome = dispatchSseBlock(block, handlers);
          if (outcome === 'done' || outcome === 'error') {
            terminalReached = true;
          }
        }
        if (signal?.aborted) {
          break;
        }
        separatorIndex = buffer.indexOf('\n\n');
      }
    }

    if (signal?.aborted) {
      throw new DOMException('Aborted', 'AbortError');
    }

    buffer += normalizeChunk(decoder.decode(), true);
    if (buffer.trim().length > 0) {
      const outcome = dispatchSseBlock(buffer, handlers);
      if (outcome === 'done' || outcome === 'error') {
        terminalReached = true;
      }
    }

    if (!terminalReached) {
      handlers.onErrorEvent?.({
        errorCode: 'LLM_STREAM_INTERRUPTED',
        message: 'Luồng trả lời bị gián đoạn trước khi hoàn tất. Vui lòng thử gửi lại.',
        provider: null,
        retryable: true,
      });
    }
  } finally {
    signal?.removeEventListener('abort', onAbort);
    if (!streamDone) {
      await reader.cancel().catch(() => {});
    }
    reader.releaseLock();
  }
}
