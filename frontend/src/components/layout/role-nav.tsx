'use client';

import React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { BarChart3, FileText, MessageSquare, Users } from 'lucide-react';
import { getNavItemsForRole, type NavIconName } from '@/lib/auth/permissions';
import type { Role } from '@/lib/types/auth';
import { cn } from '@/lib/utils';

const ICON_MAP: Record<NavIconName, React.ComponentType<{ className?: string }>> = {
  chat: MessageSquare,
  documents: FileText,
  analytics: BarChart3,
  users: Users,
};

interface RoleNavProps {
  role: Role;
  onNavigate?: () => void;
}

export function RoleNav({ role, onNavigate }: RoleNavProps) {
  const pathname = usePathname() || '/chat';
  const items = getNavItemsForRole(role);

  return (
    <nav aria-label="Điều hướng chính" className="space-y-1">
      <ul className="space-y-1">
        {items.map((item) => {
          const Icon = ICON_MAP[item.icon];
          const isActive = pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <li key={item.href}>
              <Link
                href={item.href}
                onClick={onNavigate}
                aria-current={isActive ? 'page' : undefined}
                className={cn(
                  'flex items-center gap-2.5 rounded-md border-l-4 px-3 py-2 text-sm transition-colors',
                  isActive
                    ? 'border-primary bg-primary/10 font-semibold text-primary'
                    : 'border-transparent font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900',
                )}
              >
                <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
                <span>{item.label}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
