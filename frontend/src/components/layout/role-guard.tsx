'use client';

import React from 'react';
import { usePathname } from 'next/navigation';
import { ForbiddenView } from '@/components/layout/forbidden-view';
import { canAccessRoute } from '@/lib/auth/permissions';
import type { UserProfileResponse } from '@/lib/types/auth';

interface RoleGuardProps {
  user: UserProfileResponse;
  children: React.ReactNode;
}

export function RoleGuard({ user, children }: RoleGuardProps) {
  const pathname = usePathname() || '/chat';
  if (!canAccessRoute(user.role, pathname)) {
    return <ForbiddenView role={user.role} attemptedPath={pathname} />;
  }
  return <>{children}</>;
}
