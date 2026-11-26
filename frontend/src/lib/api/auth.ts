import { apiFetch } from '@/lib/api/client';
import {
  clearSessionTokens,
  ensureValidAccessToken,
  getAccessToken,
  getRefreshToken,
  setSessionTokens,
} from '@/lib/auth/token-manager';
import type {
  AuthTokenResponse,
  LoginRequestPayload,
  UserProfileResponse,
} from '@/lib/types/auth';

export async function loginRequest(payload: LoginRequestPayload): Promise<AuthTokenResponse> {
  const response = await apiFetch<AuthTokenResponse>('/api/v1/auth/login', {
    method: 'POST',
    authenticated: false,
    body: JSON.stringify(payload),
  });
  setSessionTokens(response);
  return response;
}

export async function fetchCurrentUser(): Promise<UserProfileResponse> {
  return apiFetch<UserProfileResponse>('/api/v1/auth/me', {
    method: 'GET',
    authenticated: true,
  });
}

export async function logoutRequest(): Promise<void> {
  try {
    let accessToken = getAccessToken();
    if (!accessToken && getRefreshToken()) {
      accessToken = await ensureValidAccessToken();
    }
    if (accessToken) {
      await apiFetch<void>('/api/v1/auth/logout', {
        method: 'POST',
        authenticated: true,
        body: () => {
          const latestRefreshToken = getRefreshToken();
          return latestRefreshToken ? JSON.stringify({ refreshToken: latestRefreshToken }) : undefined;
        },
      });
    }
  } finally {
    clearSessionTokens({ broadcast: true });
  }
}
