import { NextResponse, type NextRequest } from 'next/server';
import { AUTH_SESSION_COOKIE } from '@/lib/auth/constants';
import { sanitizeNextPath } from '@/lib/auth/permissions';

const PROTECTED_PREFIXES = ['/chat', '/documents', '/admin'];

function isProtectedPath(pathname: string): boolean {
  return PROTECTED_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}

export function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const hasSessionCookie = request.cookies.get(AUTH_SESSION_COOKIE)?.value === '1';

  if (pathname === '/') {
    const target = hasSessionCookie ? '/chat' : '/login';
    return NextResponse.redirect(new URL(target, request.url));
  }

  if (pathname === '/login' && hasSessionCookie) {
    const target = sanitizeNextPath(request.nextUrl.searchParams.get('next'));
    return NextResponse.redirect(new URL(target, request.url));
  }

  if (isProtectedPath(pathname) && !hasSessionCookie) {
    const loginUrl = new URL('/login', request.url);
    const nextPath = `${pathname}${search}`;
    if (nextPath !== '/chat') {
      loginUrl.searchParams.set('next', nextPath);
    }
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp|ico)$).*)'],
};
