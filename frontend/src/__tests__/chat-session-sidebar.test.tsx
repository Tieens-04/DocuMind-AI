import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatSessionSidebar } from '@/components/layout/chat-session-sidebar';
import { resetTokenManagerForTests, setSessionTokens } from '@/lib/auth/token-manager';

const mockPush = vi.fn();
const mockReplace = vi.fn();
let mockSearchParams = new URLSearchParams();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush, replace: mockReplace }),
  usePathname: () => '/chat',
  useSearchParams: () => mockSearchParams,
}));

describe('ChatSessionSidebar', () => {
  beforeEach(() => {
    resetTokenManagerForTests();
    mockPush.mockReset();
    mockReplace.mockReset();
    mockSearchParams = new URLSearchParams();
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

  it('executes real chat-sessions API calls (list, select, create, rename, active delete)', async () => {
    const user = userEvent.setup();
    const sessionOneId = '11111111-1111-1111-1111-111111111111';
    const sessionTwoId = '22222222-2222-2222-2222-222222222222';
    mockSearchParams = new URLSearchParams(`session=${sessionOneId}`);

    const recordedCalls: Array<{ method: string; url: string; body?: string }> = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? 'GET';
        const body = typeof init?.body === 'string' ? init.body : undefined;
        recordedCalls.push({ method, url, body });

        expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer test-access-token');

        if (method === 'GET' && url.includes('/api/v1/chat/sessions?page=0&size=50')) {
          return new Response(
            JSON.stringify({
              items: [
                {
                  id: sessionOneId,
                  title: 'Quy chế nghỉ phép',
                  createdAt: '2026-11-26T00:00:00Z',
                  updatedAt: '2026-11-26T00:00:00Z',
                },
              ],
              page: 0,
              size: 50,
              totalElements: 1,
              totalPages: 1,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }

        if (method === 'POST' && url.endsWith('/api/v1/chat/sessions')) {
          return new Response(
            JSON.stringify({
              id: sessionTwoId,
              title: 'Cuộc hội thoại mới',
              createdAt: '2026-11-26T01:00:00Z',
              updatedAt: '2026-11-26T01:00:00Z',
            }),
            { status: 201, headers: { 'Content-Type': 'application/json' } },
          );
        }

        if (method === 'PATCH' && url.endsWith(`/api/v1/chat/sessions/${sessionOneId}`)) {
          return new Response(
            JSON.stringify({
              id: sessionOneId,
              title: 'Quy chế phép năm 2026',
              createdAt: '2026-11-26T00:00:00Z',
              updatedAt: '2026-11-26T02:00:00Z',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }

        if (method === 'DELETE' && url.endsWith(`/api/v1/chat/sessions/${sessionOneId}`)) {
          return new Response(null, { status: 204 });
        }

        throw new Error(`Unexpected request: ${method} ${url}`);
      }),
    );

    render(<ChatSessionSidebar />);

    const activeSessionButton = await screen.findByRole('button', { name: 'Quy chế nghỉ phép' });
    expect(activeSessionButton).toHaveAttribute('aria-current', 'true');

    await user.click(activeSessionButton);
    expect(mockPush).toHaveBeenCalledWith(`/chat?session=${sessionOneId}`);

    await user.click(screen.getByRole('button', { name: /Cuộc hội thoại mới/i }));
    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith(`/chat?session=${sessionTwoId}`);
    });
    expect(
      await screen.findByRole('button', { name: 'Đổi tên Cuộc hội thoại mới' }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Đổi tên Quy chế nghỉ phép' }));
    const renameInput = await screen.findByLabelText(/Tiêu đề/i);
    await user.clear(renameInput);
    await user.type(renameInput, 'Quy chế phép năm 2026');
    await user.click(screen.getByRole('button', { name: /Lưu tên mới/i }));

    expect(await screen.findByText('Quy chế phép năm 2026')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Xóa Quy chế phép năm 2026' }));
    await user.click(await screen.findByRole('button', { name: /Xóa hội thoại/i }));

    await waitFor(() => {
      expect(screen.queryByText('Quy chế phép năm 2026')).not.toBeInTheDocument();
      expect(mockReplace).toHaveBeenCalledWith('/chat');
    });

    expect(recordedCalls).toEqual([
      { method: 'GET', url: 'http://localhost:8081/api/v1/chat/sessions?page=0&size=50', body: undefined },
      { method: 'POST', url: 'http://localhost:8081/api/v1/chat/sessions', body: '{}' },
      {
        method: 'PATCH',
        url: `http://localhost:8081/api/v1/chat/sessions/${sessionOneId}`,
        body: JSON.stringify({ title: 'Quy chế phép năm 2026' }),
      },
      {
        method: 'DELETE',
        url: `http://localhost:8081/api/v1/chat/sessions/${sessionOneId}`,
        body: undefined,
      },
    ]);
  });

  it('prevents stale listSessions responses from overwriting newly created sessions', async () => {
    const user = userEvent.setup();
    let resolveInitialList!: (response: Response) => void;

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? 'GET';
        if (method === 'GET' && url.includes('/api/v1/chat/sessions')) {
          return new Promise<Response>((resolve) => {
            resolveInitialList = resolve;
          });
        }
        if (method === 'POST' && url.endsWith('/api/v1/chat/sessions')) {
          return new Response(
            JSON.stringify({
              id: '33333333-3333-3333-3333-333333333333',
              title: 'Phiên tạo nhanh',
              createdAt: '2026-11-26T01:00:00Z',
              updatedAt: '2026-11-26T01:00:00Z',
            }),
            { status: 201, headers: { 'Content-Type': 'application/json' } },
          );
        }
        throw new Error(`Unexpected request: ${method} ${url}`);
      }),
    );

    render(<ChatSessionSidebar />);

    await user.click(screen.getByRole('button', { name: /Cuộc hội thoại mới/i }));
    expect(await screen.findByText('Phiên tạo nhanh')).toBeInTheDocument();

    resolveInitialList(
      new Response(
        JSON.stringify({
          items: [
            {
              id: '99999999-9999-9999-9999-999999999999',
              title: 'Phiên cũ trả về trễ',
              createdAt: '2026-11-25T00:00:00Z',
              updatedAt: '2026-11-25T00:00:00Z',
            },
          ],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByText('Phiên tạo nhanh')).toBeInTheDocument();
    expect(screen.queryByText('Phiên cũ trả về trễ')).not.toBeInTheDocument();
  });
});
