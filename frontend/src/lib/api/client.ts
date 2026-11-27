import {
  ensureValidAccessToken,
  refreshAccessToken,
} from '@/lib/auth/token-manager';
import { getApiBaseUrl } from '@/lib/env';
import { parseApiErrorResponse } from '@/lib/types/problem';

export interface ApiFetchOptions extends Omit<RequestInit, 'headers' | 'body'> {
  headers?: Record<string, string>;
  body?: RequestInit['body'] | (() => RequestInit['body'] | undefined);
  authenticated?: boolean;
}

export function buildApiUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${getApiBaseUrl()}${normalizedPath}`;
}

export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { authenticated = true, headers: extraHeaders, body, ...rest } = options;
  const targetUrl = buildApiUrl(path);

  const execute = async (token: string | null): Promise<Response> => {
    const resolvedBody = typeof body === 'function' ? body() : body;
    const headers = new Headers(extraHeaders);
    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json, application/problem+json');
    }
    if (resolvedBody && typeof resolvedBody === 'string' && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    return fetch(targetUrl, {
      ...rest,
      body: resolvedBody,
      headers,
    });
  };

  let usedToken: string | null = null;
  if (authenticated) {
    usedToken = await ensureValidAccessToken();
  }

  let response = await execute(usedToken);

  if (response.status === 401 && authenticated) {
    const nextToken = await refreshAccessToken(usedToken ?? undefined);
    response = await execute(nextToken);
  }

  if (!response.ok) {
    throw await parseApiErrorResponse(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}
