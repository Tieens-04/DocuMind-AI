import { NextRequest } from 'next/server';
import { describe, expect, it } from 'vitest';
import { AUTH_SESSION_COOKIE } from '@/lib/auth/constants';
import { middleware } from '@/middleware';

function buildRequest(pathWithQuery: string, withSessionCookie: boolean): NextRequest {
  const headers = new Headers();
  if (withSessionCookie) {
    headers.set('cookie', `${AUTH_SESSION_COOKIE}=1`);
  }
  return new NextRequest(`http://localhost:3002${pathWithQuery}`, { headers });
}

describe('middleware route protection', () => {
  it('redirects / based on session cookie presence', () => {
    const unauth = middleware(buildRequest('/', false));
    expect(unauth.status).toBe(307);
    expect(new URL(unauth.headers.get('location')!).pathname).toBe('/login');

    const auth = middleware(buildRequest('/', true));
    expect(auth.status).toBe(307);
    expect(new URL(auth.headers.get('location')!).pathname).toBe('/chat');
  });

  it('redirects unauthenticated protected paths to /login preserving next query', () => {
    const docs = middleware(buildRequest('/documents', false));
    expect(docs.status).toBe(307);
    const docsLoc = new URL(docs.headers.get('location')!);
    expect(docsLoc.pathname).toBe('/login');
    expect(docsLoc.searchParams.get('next')).toBe('/documents');

    const chatWithSession = middleware(
      buildRequest('/chat?session=11111111-1111-1111-1111-111111111111', false),
    );
    const chatLoc = new URL(chatWithSession.headers.get('location')!);
    expect(chatLoc.pathname).toBe('/login');
    expect(chatLoc.searchParams.get('next')).toBe(
      '/chat?session=11111111-1111-1111-1111-111111111111',
    );

    const adminUsers = middleware(buildRequest('/admin/users', false));
    expect(new URL(adminUsers.headers.get('location')!).searchParams.get('next')).toBe(
      '/admin/users',
    );
  });

  it('honors sanitized next on /login when session cookie is active and blocks open redirects', () => {
    const validNext = middleware(buildRequest('/login?next=%2Fdocuments', true));
    expect(validNext.status).toBe(307);
    expect(new URL(validNext.headers.get('location')!).pathname).toBe('/documents');

    const evilNext = middleware(buildRequest('/login?next=%2F%09%2Fevil.example', true));
    expect(evilNext.status).toBe(307);
    expect(new URL(evilNext.headers.get('location')!).pathname).toBe('/chat');
  });

  it('allows authenticated requests through to protected workspace routes', () => {
    const res = middleware(buildRequest('/admin/analytics', true));
    expect(res.status).toBe(200);
    expect(res.headers.get('location')).toBeNull();
  });
});
