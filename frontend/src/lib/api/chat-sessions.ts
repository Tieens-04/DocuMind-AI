import { apiFetch } from '@/lib/api/client';
import type { ChatSessionResponse, PageResponse } from '@/lib/types/chat';

export async function listChatSessions(
  page = 0,
  size = 50,
  signal?: AbortSignal,
): Promise<PageResponse<ChatSessionResponse>> {
  return apiFetch<PageResponse<ChatSessionResponse>>(
    `/api/v1/chat/sessions?page=${page}&size=${size}`,
    { method: 'GET', signal },
  );
}

export async function createChatSession(title?: string): Promise<ChatSessionResponse> {
  const trimmed = title?.trim();
  return apiFetch<ChatSessionResponse>('/api/v1/chat/sessions', {
    method: 'POST',
    body: JSON.stringify(trimmed ? { title: trimmed } : {}),
  });
}

export async function renameChatSession(
  sessionId: string,
  title: string,
): Promise<ChatSessionResponse> {
  return apiFetch<ChatSessionResponse>(`/api/v1/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'PATCH',
    body: JSON.stringify({ title: title.trim() }),
  });
}

export async function deleteChatSession(sessionId: string): Promise<void> {
  return apiFetch<void>(`/api/v1/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  });
}
