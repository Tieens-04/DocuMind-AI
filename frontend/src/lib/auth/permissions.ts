import type { Role } from '@/lib/types/auth';

export type NavIconName = 'chat' | 'documents' | 'analytics' | 'users';

export interface NavItem {
  href: string;
  label: string;
  icon: NavIconName;
  allowedRoles: readonly Role[];
}

export const WORKSPACE_NAV_ITEMS: readonly NavItem[] = [
  {
    href: '/chat',
    label: 'Trợ lý Chat',
    icon: 'chat',
    allowedRoles: ['STANDARD_EMPLOYEE', 'DEPARTMENT_MANAGER', 'SYSTEM_ADMIN'],
  },
  {
    href: '/documents',
    label: 'Kho Tài liệu',
    icon: 'documents',
    allowedRoles: ['DEPARTMENT_MANAGER', 'SYSTEM_ADMIN'],
  },
  {
    href: '/admin/analytics',
    label: 'FinOps & Audit',
    icon: 'analytics',
    allowedRoles: ['SYSTEM_ADMIN'],
  },
  {
    href: '/admin/users',
    label: 'Nhân sự & Phòng ban',
    icon: 'users',
    allowedRoles: ['SYSTEM_ADMIN'],
  },
];

const ROUTE_RULES: ReadonlyArray<{ prefix: string; allowedRoles: readonly Role[] }> = [
  { prefix: '/admin', allowedRoles: ['SYSTEM_ADMIN'] },
  { prefix: '/documents', allowedRoles: ['DEPARTMENT_MANAGER', 'SYSTEM_ADMIN'] },
  {
    prefix: '/chat',
    allowedRoles: ['STANDARD_EMPLOYEE', 'DEPARTMENT_MANAGER', 'SYSTEM_ADMIN'],
  },
];

export function getNavItemsForRole(role: Role): NavItem[] {
  return WORKSPACE_NAV_ITEMS.filter((item) => item.allowedRoles.includes(role));
}

export function canAccessRoute(role: Role, pathname: string): boolean {
  const normalized = pathname.trim() || '/';
  for (const rule of ROUTE_RULES) {
    if (normalized === rule.prefix || normalized.startsWith(`${rule.prefix}/`)) {
      return rule.allowedRoles.includes(role);
    }
  }
  return false;
}

export function sanitizeNextPath(rawNext: string | null | undefined): string {
  if (!rawNext) {
    return '/chat';
  }
  const trimmed = rawNext.trim();
  if (
    !trimmed.startsWith('/') ||
    trimmed.startsWith('//') ||
    /[\u0000-\u001F\u007F]/.test(trimmed) ||
    trimmed.includes('\\')
  ) {
    return '/chat';
  }

  try {
    const parsed = new URL(trimmed, 'http://documind.local');
    if (
      parsed.origin !== 'http://documind.local' ||
      parsed.pathname.startsWith('//') ||
      parsed.pathname === '/login' ||
      parsed.pathname.startsWith('/login/')
    ) {
      return '/chat';
    }
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return '/chat';
  }
}
