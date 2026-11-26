'use client';

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { fetchCurrentUser, loginRequest, logoutRequest } from '@/lib/api/auth';
import {
  clearSessionTokens,
  hasStoredSession,
  initCrossTabAuthSync,
  subscribeSessionExpired,
  subscribeSessionUpdated,
} from '@/lib/auth/token-manager';
import type { LoginRequestPayload, UserProfileResponse } from '@/lib/types/auth';
import { ApiError, formatApiErrorMessage } from '@/lib/types/problem';

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'error';

interface AuthContextValue {
  user: UserProfileResponse | null;
  status: AuthStatus;
  bootstrapError: string | null;
  login: (payload: LoginRequestPayload) => Promise<UserProfileResponse>;
  logout: () => Promise<void>;
  retryBootstrap: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [user, setUser] = useState<UserProfileResponse | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [bootstrapError, setBootstrapError] = useState<string | null>(null);

  const loadProfile = useCallback(async (isMounted?: () => boolean): Promise<void> => {
    const stillActive = () => (isMounted ? isMounted() : true);
    if (!hasStoredSession()) {
      if (stillActive()) {
        setUser(null);
        setBootstrapError(null);
        setStatus('unauthenticated');
      }
      return;
    }

    setBootstrapError(null);
    setStatus('loading');

    try {
      const profile = await fetchCurrentUser();
      if (stillActive()) {
        setUser(profile);
        setBootstrapError(null);
        setStatus('authenticated');
      }
    } catch (error) {
      const isTerminalAuthError =
        (error instanceof ApiError && (error.status === 401 || error.status === 403)) ||
        !hasStoredSession();

      if (isTerminalAuthError) {
        clearSessionTokens({ broadcast: false });
        if (stillActive()) {
          setUser(null);
          setBootstrapError(null);
          setStatus('unauthenticated');
        }
        return;
      }

      if (stillActive()) {
        setUser(null);
        setBootstrapError(formatApiErrorMessage(error));
        setStatus('error');
      }
    }
  }, []);

  useEffect(() => {
    initCrossTabAuthSync();
    let active = true;
    void loadProfile(() => active);
    return () => {
      active = false;
    };
  }, [loadProfile]);

  useEffect(() => {
    return subscribeSessionExpired(() => {
      setUser(null);
      setBootstrapError(null);
      setStatus('unauthenticated');
      if (pathname !== '/login') {
        toast.info('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
      }
    });
  }, [pathname]);

  useEffect(() => {
    return subscribeSessionUpdated((syncedUser) => {
      if (syncedUser) {
        setUser(syncedUser);
        setBootstrapError(null);
        setStatus('authenticated');
      } else {
        void loadProfile();
      }
    });
  }, [loadProfile]);

  const login = useCallback(async (payload: LoginRequestPayload): Promise<UserProfileResponse> => {
    const tokenResponse = await loginRequest(payload);
    setUser(tokenResponse.user);
    setBootstrapError(null);
    setStatus('authenticated');
    return tokenResponse.user;
  }, []);

  const logout = useCallback(async (): Promise<void> => {
    try {
      await logoutRequest();
    } catch {
      clearSessionTokens({ broadcast: true });
    } finally {
      setUser(null);
      setBootstrapError(null);
      setStatus('unauthenticated');
      router.replace('/login');
    }
  }, [router]);

  const retryBootstrap = useCallback(async (): Promise<void> => {
    await loadProfile();
  }, [loadProfile]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      status,
      bootstrapError,
      login,
      logout,
      retryBootstrap,
    }),
    [bootstrapError, login, logout, retryBootstrap, status, user],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return ctx;
}
