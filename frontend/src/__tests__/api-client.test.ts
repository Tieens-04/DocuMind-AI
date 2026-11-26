import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '@/lib/api/client';
import { AUTH_SESSION_COOKIE } from '@/lib/auth/constants';
import {
  clearSessionTokens,
  getAccessToken,
  getRefreshToken,
  initCrossTabAuthSync,
  refreshAccessToken,
  resetTokenManagerForTests,
  setSessionTokens,
  subscribeSessionExpired,
} from '@/lib/auth/token-manager';
import { ApiError } from '@/lib/types/problem';

function createTokenBundle(accessToken: string, refreshToken: string, refreshExpiresIn = 604800) {
  return {
    accessToken,
    refreshToken,
    tokenType: 'Bearer',
    expiresIn: 900,
    refreshExpiresIn,
    user: {
      id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      email: 'admin@documind.ai',
      fullName: 'System Administrator',
      role: 'SYSTEM_ADMIN' as const,
      departmentId: '11111111-1111-1111-1111-111111111111',
      departmentCode: 'IT',
      departmentName: 'IT',
      isActive: true,
      createdAt: '2026-11-26T00:00:00Z',
    },
  };
}

describe('apiFetch & single-flight 401 refresh', () => {
  beforeEach(() => {
    resetTokenManagerForTests();
    vi.stubEnv('NEXT_PUBLIC_API_BASE_URL', 'http://localhost:8081');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('deduplicates concurrent 401 refreshes into a single /api/v1/auth/refresh call and retries callers', async () => {
    setSessionTokens({
      accessToken: 'stale-access-token',
      refreshToken: 'initial-refresh-token',
      expiresIn: 900,
      refreshExpiresIn: 3600,
    });
    expect(document.cookie).toContain(`${AUTH_SESSION_COOKIE}=1`);

    let refreshCallCount = 0;
    let protectedCallCount = 0;

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const headers = new Headers(init?.headers);

      if (url.endsWith('/api/v1/auth/refresh')) {
        refreshCallCount += 1;
        await new Promise((resolve) => setTimeout(resolve, 15));
        expect(JSON.parse(String(init?.body))).toEqual({
          refreshToken: 'initial-refresh-token',
        });
        return new Response(
          JSON.stringify(createTokenBundle('fresh-access-token', 'rotated-refresh-token')),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }

      if (url.endsWith('/api/v1/chat/sessions')) {
        protectedCallCount += 1;
        if (headers.get('Authorization') === 'Bearer stale-access-token') {
          return new Response(
            JSON.stringify({
              status: 401,
              title: 'Token Expired',
              detail: 'Access token expired',
              errorCode: 'TOKEN_EXPIRED',
            }),
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        expect(headers.get('Authorization')).toBe('Bearer fresh-access-token');
        return new Response(
          JSON.stringify({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }

      throw new Error(`Unexpected URL: ${url}`);
    });

    vi.stubGlobal('fetch', fetchMock);

    const [res1, res2, res3] = await Promise.all([
      apiFetch('/api/v1/chat/sessions'),
      apiFetch('/api/v1/chat/sessions'),
      apiFetch('/api/v1/chat/sessions'),
    ]);

    expect(refreshCallCount).toBe(1);
    expect(protectedCallCount).toBe(6);
    expect(res1).toEqual({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    expect(res2).toEqual(res1);
    expect(res3).toEqual(res1);
    expect(getAccessToken()).toBe('fresh-access-token');
    expect(getRefreshToken()).toBe('rotated-refresh-token');
  });

  it('uses navigator.locks + BroadcastChannel macrotask ordering without overwriting newer localStorage tokens', async () => {
    let lockTail = Promise.resolve();
    const requestMock = vi.fn(async (_name: string, task: () => Promise<unknown>) => {
      const prev = lockTail;
      let release!: () => void;
      lockTail = new Promise<void>((resolve) => {
        release = resolve;
      });
      await prev;
      try {
        return await task();
      } finally {
        release();
      }
    });
    vi.stubGlobal('navigator', { locks: { request: requestMock } });

    const channels: MockChannel[] = [];
    class MockChannel {
      private listeners = new Set<(event: MessageEvent) => void>();
      constructor() {
        channels.push(this);
      }
      addEventListener(_type: string, listener: (event: MessageEvent) => void) {
        this.listeners.add(listener);
      }
      postMessage(data: unknown) {
        setTimeout(() => {
          channels.forEach((channel) => {
            if (channel !== this) {
              channel.listeners.forEach((listener) => listener({ data } as MessageEvent));
            }
          });
        }, 0);
      }
      close() {
        this.listeners.clear();
      }
    }
    vi.stubGlobal('BroadcastChannel', MockChannel);

    initCrossTabAuthSync();
    window.localStorage.setItem('documind.refresh_token', 'r1');

    // Simulate a prior tab holding the lock, rotating r1 -> r2, writing localStorage, and broadcasting.
    const externalTab = new MockChannel();
    const refreshCalls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        const body = JSON.parse(String(init?.body)) as { refreshToken: string };
        refreshCalls.push(body.refreshToken);
        return new Response(
          JSON.stringify(createTokenBundle('t3', 'r3')),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );

    const priorHolderPromise = requestMock('documind-auth-refresh', async () => {
      window.localStorage.setItem('documind.refresh_token', 'r2');
      externalTab.postMessage({
        type: 'SESSION_UPDATED',
        accessToken: 't2',
        expiresIn: 900,
        refreshToken: 'r2',
        refreshExpiresIn: 604800,
      });
    });

    const acquiredToken = await Promise.all([priorHolderPromise, refreshAccessToken()]).then(
      ([, token]) => token,
    );

    expect(acquiredToken).toBe('t2');
    expect(refreshCalls).toHaveLength(0);

    // Simulate a delayed stale BroadcastChannel message from an older rotation arriving after r3 is persisted.
    window.localStorage.setItem('documind.refresh_token', 'r3');
    externalTab.postMessage({
      type: 'SESSION_UPDATED',
      accessToken: 't1-stale',
      expiresIn: 900,
      refreshToken: 'r1-stale',
    });
    await new Promise((resolve) => setTimeout(resolve, 15));

    expect(getRefreshToken()).toBe('r3');
    expect(getAccessToken()).toBe('t2');
  });

  it('does not resurrect session if logout clears tokens while /refresh is in flight', async () => {
    setSessionTokens(createTokenBundle('stale-access', 'r1'));
    const expiredHandler = vi.fn();
    subscribeSessionExpired(expiredHandler);

    let resolveFetch!: (res: Response) => void;
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            resolveFetch = resolve;
          }),
      ),
    );

    const refreshPromise = refreshAccessToken('stale-access');
    await new Promise((resolve) => setTimeout(resolve, 10));
    clearSessionTokens({ broadcast: true });

    resolveFetch(
      new Response(JSON.stringify(createTokenBundle('zombie-token', 'r2')), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    await expect(refreshPromise).rejects.toMatchObject({
      status: 401,
      errorCode: 'AUTHENTICATION_REQUIRED',
    });
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(document.cookie).not.toContain(`${AUTH_SESSION_COOKIE}=1`);
  });

  it('preserves refresh token on transient 503 or 429 refresh errors', async () => {
    setSessionTokens(createTokenBundle('expired-access', 'valid-refresh'));
    const expiredHandler = vi.fn();
    subscribeSessionExpired(expiredHandler);

    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(
          JSON.stringify({
            status: 503,
            title: 'Service Unavailable',
            detail: 'Redis auth state unavailable',
            errorCode: 'AUTH_STATE_STORE_UNAVAILABLE',
          }),
          { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    await expect(refreshAccessToken('expired-access')).rejects.toMatchObject({
      status: 503,
      errorCode: 'AUTH_STATE_STORE_UNAVAILABLE',
    });
    expect(getRefreshToken()).toBe('valid-refresh');
    expect(expiredHandler).not.toHaveBeenCalled();
  });

  it('clears stored tokens and notifies session-expired listeners when refresh fails with REFRESH_TOKEN_REUSED', async () => {
    setSessionTokens({
      accessToken: 'stale-access-token',
      refreshToken: 'reused-refresh-token',
      expiresIn: 900,
    });

    const expiredHandler = vi.fn();
    subscribeSessionExpired(expiredHandler);

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith('/api/v1/auth/refresh')) {
          return new Response(
            JSON.stringify({
              status: 401,
              title: 'Refresh Token Reused',
              detail: 'Refresh token reuse detected',
              errorCode: 'REFRESH_TOKEN_REUSED',
            }),
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return new Response(
          JSON.stringify({
            status: 401,
            title: 'Token Expired',
            detail: 'Expired',
            errorCode: 'TOKEN_EXPIRED',
          }),
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    await expect(apiFetch('/api/v1/auth/me')).rejects.toMatchObject({
      status: 401,
      errorCode: 'REFRESH_TOKEN_REUSED',
    });

    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(document.cookie).not.toContain(`${AUTH_SESSION_COOKIE}=1`);
    expect(expiredHandler).toHaveBeenCalledTimes(1);
  });

  it('handles storage clear events and removes storage listeners on reset', () => {
    setSessionTokens(createTokenBundle('access-1', 'refresh-1', 1200));
    const expiredHandler = vi.fn();
    subscribeSessionExpired(expiredHandler);

    window.dispatchEvent(new StorageEvent('storage', { key: null, newValue: null }));
    expect(expiredHandler).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBeNull();

    const removeSpy = vi.spyOn(window, 'removeEventListener');
    resetTokenManagerForTests();
    expect(removeSpy).toHaveBeenCalledWith('storage', expect.any(Function));
  });

  it('parses RFC 7807 ProblemDetail and Retry-After header on 429 Too Many Requests', async () => {
    setSessionTokens({
      accessToken: 'valid-token',
      refreshToken: 'valid-refresh',
      expiresIn: 900,
    });

    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(
          JSON.stringify({
            status: 429,
            title: 'Too Many Requests',
            detail: 'Rate limit exceeded',
            errorCode: 'RATE_LIMIT_EXCEEDED',
          }),
          {
            status: 429,
            headers: {
              'Content-Type': 'application/problem+json',
              'Retry-After': '42',
            },
          },
        ),
      ),
    );

    let caught: unknown = null;
    try {
      await apiFetch('/api/v1/chat/sessions');
    } catch (err) {
      caught = err;
    }

    expect(caught).toBeInstanceOf(ApiError);
    expect(caught).toMatchObject({
      status: 429,
      errorCode: 'RATE_LIMIT_EXCEEDED',
      retryAfterSeconds: 42,
    });
  });
});
