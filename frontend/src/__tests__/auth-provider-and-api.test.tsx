import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { logoutRequest } from '@/lib/api/auth';
import { sanitizeNextPath } from '@/lib/auth/permissions';
import {
  getAccessToken,
  getRefreshToken,
  resetTokenManagerForTests,
  setSessionTokens,
} from '@/lib/auth/token-manager';
import { AuthProvider, useAuth } from '@/providers/auth-provider';

const mockReplace = vi.fn();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: mockReplace, push: vi.fn() }),
  usePathname: () => '/chat',
}));

function AuthProbe() {
  const { user, status, bootstrapError, retryBootstrap } = useAuth();
  return (
    <div>
      <span data-testid="auth-status">{status}</span>
      <span data-testid="auth-role">{user?.role ?? 'none'}</span>
      <span data-testid="auth-error">{bootstrapError ?? ''}</span>
      <button type="button" onClick={() => void retryBootstrap()}>
        Retry
      </button>
    </div>
  );
}

describe('auth.ts, AuthProvider & sanitizeNextPath', () => {
  beforeEach(() => {
    resetTokenManagerForTests();
    mockReplace.mockReset();
    vi.stubEnv('NEXT_PUBLIC_API_BASE_URL', 'http://localhost:8081');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('rotates expired access token before logoutRequest and sends the latest rotated refreshToken', async () => {
    setSessionTokens({
      accessToken: 'expired-access',
      refreshToken: 'refresh-r1',
      expiresIn: 0,
    });

    const logoutBodies: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith('/api/v1/auth/refresh')) {
          return new Response(
            JSON.stringify({
              accessToken: 'fresh-access',
              refreshToken: 'refresh-r2',
              tokenType: 'Bearer',
              expiresIn: 900,
              refreshExpiresIn: 604800,
              user: {
                id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                email: 'admin@documind.ai',
                fullName: 'System Administrator',
                role: 'SYSTEM_ADMIN',
                departmentId: '11111111-1111-1111-1111-111111111111',
                departmentCode: 'IT',
                departmentName: 'IT',
                isActive: true,
                createdAt: '2026-11-26T00:00:00Z',
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }
        if (url.endsWith('/api/v1/auth/logout')) {
          logoutBodies.push(JSON.parse(String(init?.body)));
          expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer fresh-access');
          return new Response(null, { status: 204 });
        }
        throw new Error(`Unexpected URL: ${url}`);
      }),
    );

    await logoutRequest();

    expect(logoutBodies).toEqual([{ refreshToken: 'refresh-r2' }]);
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it('preserves refresh token on 503 bootstrap failure and recovers on retry', async () => {
    const user = userEvent.setup();
    setSessionTokens({
      accessToken: 'valid-access',
      refreshToken: 'keep-refresh-token',
      expiresIn: 900,
    });

    let callCount = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        expect(String(input)).toMatch(/\/api\/v1\/auth\/me$/);
        callCount += 1;
        if (callCount === 1) {
          return new Response(
            JSON.stringify({
              status: 503,
              title: 'Service Unavailable',
              detail: 'Auth state store unavailable',
              errorCode: 'AUTH_STATE_STORE_UNAVAILABLE',
            }),
            { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return new Response(
          JSON.stringify({
            id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
            email: 'manager.hr@documind.ai',
            fullName: 'HR Department Manager',
            role: 'DEPARTMENT_MANAGER',
            departmentId: '22222222-2222-2222-2222-222222222222',
            departmentCode: 'HR',
            departmentName: 'HR',
            isActive: true,
            createdAt: '2026-11-26T00:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );

    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('auth-status')).toHaveTextContent('error');
    });
    expect(getRefreshToken()).toBe('keep-refresh-token');

    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => {
      expect(screen.getByTestId('auth-status')).toHaveTextContent('authenticated');
      expect(screen.getByTestId('auth-role')).toHaveTextContent('DEPARTMENT_MANAGER');
    });
  });

  it('syncs AuthProvider user role across tabs on BroadcastChannel SESSION_UPDATED', async () => {
    const channels: MockSyncChannel[] = [];
    class MockSyncChannel {
      private listeners = new Set<(event: MessageEvent) => void>();
      constructor() {
        channels.push(this);
      }
      addEventListener(_type: string, listener: (event: MessageEvent) => void) {
        this.listeners.add(listener);
      }
      postMessage(data: unknown) {
        channels.forEach((channel) => {
          if (channel !== this) {
            channel.listeners.forEach((listener) => listener({ data } as MessageEvent));
          }
        });
      }
      close() {
        this.listeners.clear();
      }
    }
    vi.stubGlobal('BroadcastChannel', MockSyncChannel);

    setSessionTokens({
      accessToken: 'admin-access',
      refreshToken: 'admin-refresh',
      expiresIn: 900,
    });

    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(
          JSON.stringify({
            id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            email: 'admin@documind.ai',
            fullName: 'System Administrator',
            role: 'SYSTEM_ADMIN',
            departmentId: '11111111-1111-1111-1111-111111111111',
            departmentCode: 'IT',
            departmentName: 'IT',
            isActive: true,
            createdAt: '2026-11-26T00:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );

    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('auth-role')).toHaveTextContent('SYSTEM_ADMIN');
    });

    window.localStorage.setItem('documind.refresh_token', 'sales-refresh');
    const peerTab = new MockSyncChannel();
    peerTab.postMessage({
      type: 'SESSION_UPDATED',
      accessToken: 'sales-access',
      expiresIn: 900,
      refreshToken: 'sales-refresh',
      refreshExpiresIn: 604800,
      user: {
        id: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
        email: 'employee.sales@documind.ai',
        fullName: 'Sales Standard Employee',
        role: 'STANDARD_EMPLOYEE',
        departmentId: '33333333-3333-3333-3333-333333333333',
        departmentCode: 'SALES',
        departmentName: 'SALES',
        isActive: true,
        createdAt: '2026-11-26T00:00:00Z',
      },
    });

    await waitFor(() => {
      expect(screen.getByTestId('auth-role')).toHaveTextContent('STANDARD_EMPLOYEE');
    });
  });

  it('rejects open-redirect payloads including control chars and backslashes in sanitizeNextPath', () => {
    expect(sanitizeNextPath('//evil.example')).toBe('/chat');
    expect(sanitizeNextPath('/\\evil.example')).toBe('/chat');
    expect(sanitizeNextPath('/\t/evil.example')).toBe('/chat');
    expect(sanitizeNextPath('/\n/evil.example')).toBe('/chat');
    expect(sanitizeNextPath('https://evil.example')).toBe('/chat');
    expect(sanitizeNextPath('/login')).toBe('/chat');
    expect(sanitizeNextPath('/login/callback')).toBe('/chat');
    expect(sanitizeNextPath('/documents')).toBe('/documents');
    expect(sanitizeNextPath('/chat?session=11111111-1111-1111-1111-111111111111')).toBe(
      '/chat?session=11111111-1111-1111-1111-111111111111',
    );
  });
});
