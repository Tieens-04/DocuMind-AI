import { AUTH_SESSION_COOKIE } from '@/lib/auth/constants';
import { getApiBaseUrl } from '@/lib/env';
import type { AuthTokenResponse, UserProfileResponse } from '@/lib/types/auth';
import { ApiError, parseApiErrorResponse } from '@/lib/types/problem';

const REFRESH_TOKEN_STORAGE_KEY = 'documind.refresh_token';
const AUTH_BROADCAST_CHANNEL = 'documind-auth-sync';
const REFRESH_LOCK_NAME = 'documind-auth-refresh';
const CLOCK_SKEW_BUFFER_MS = 5_000;
const REFRESH_REQUEST_TIMEOUT_MS = 10_000;
const SESSION_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

type SyncMessage =
  | {
      type: 'SESSION_UPDATED';
      accessToken: string;
      expiresIn: number;
      refreshToken: string;
      refreshExpiresIn?: number;
      user?: UserProfileResponse;
    }
  | {
      type: 'SESSION_CLEARED';
    };

type SessionExpiredListener = () => void;
type SessionUpdatedListener = (user?: UserProfileResponse) => void;

let memoryAccessToken: string | null = null;
let accessTokenExpiresAtMs = 0;
let sessionEpoch = 0;
let inFlightRefreshPromise: Promise<string> | null = null;
let syncChannel: BroadcastChannel | null = null;
let syncInitialized = false;
let storageListener: ((event: StorageEvent) => void) | null = null;
const expiredListeners = new Set<SessionExpiredListener>();
const updatedListeners = new Set<SessionUpdatedListener>();

function isBrowser(): boolean {
  return typeof window !== 'undefined';
}

function setSessionMarkerCookie(active: boolean, maxAgeSeconds?: number): void {
  if (typeof document === 'undefined') {
    return;
  }
  const secureAttr =
    typeof window !== 'undefined' && window.location?.protocol === 'https:' ? '; Secure' : '';
  if (active) {
    const ttl =
      typeof maxAgeSeconds === 'number' && Number.isFinite(maxAgeSeconds) && maxAgeSeconds > 0
        ? Math.floor(maxAgeSeconds)
        : SESSION_COOKIE_MAX_AGE_SECONDS;
    document.cookie = `${AUTH_SESSION_COOKIE}=1; Path=/; Max-Age=${ttl}; SameSite=Lax${secureAttr}`;
  } else {
    document.cookie = `${AUTH_SESSION_COOKIE}=; Path=/; Max-Age=0; SameSite=Lax${secureAttr}`;
  }
}

function readStoredRefreshToken(): string | null {
  if (!isBrowser()) {
    return null;
  }
  try {
    const value = window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY);
    return value && value.trim().length > 0 ? value.trim() : null;
  } catch {
    return null;
  }
}

function writeStoredRefreshToken(refreshToken: string | null): void {
  if (!isBrowser()) {
    return;
  }
  try {
    if (refreshToken && refreshToken.trim().length > 0) {
      window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, refreshToken.trim());
    } else {
      window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
    }
  } catch {
    // Ignore storage quota or disabled storage errors
  }
}

function postSyncMessage(message: SyncMessage): void {
  if (!isBrowser() || typeof BroadcastChannel === 'undefined') {
    return;
  }
  try {
    if (!syncChannel) {
      syncChannel = new BroadcastChannel(AUTH_BROADCAST_CHANNEL);
    }
    syncChannel.postMessage(message);
  } catch {
    // BroadcastChannel may be unavailable in restricted contexts
  }
}

function notifyExpiredListeners(): void {
  expiredListeners.forEach((listener) => {
    try {
      listener();
    } catch {
      // Keep notifying remaining listeners
    }
  });
}

function notifyUpdatedListeners(user?: UserProfileResponse): void {
  updatedListeners.forEach((listener) => {
    try {
      listener(user);
    } catch {
      // Keep notifying remaining listeners
    }
  });
}

function isTokenUsable(token: string | null, expiresAtMs: number): boolean {
  return Boolean(token) && expiresAtMs - CLOCK_SKEW_BUFFER_MS > Date.now();
}

export function initCrossTabAuthSync(): void {
  if (!isBrowser() || syncInitialized) {
    return;
  }
  syncInitialized = true;

  if (typeof BroadcastChannel !== 'undefined') {
    try {
      syncChannel = new BroadcastChannel(AUTH_BROADCAST_CHANNEL);
      syncChannel.addEventListener('message', (event: MessageEvent<SyncMessage>) => {
        const data = event.data;
        if (!data) {
          return;
        }
        if (data.type === 'SESSION_UPDATED') {
          // Only accept updates matching the latest refresh token already persisted under lock.
          if (readStoredRefreshToken() !== data.refreshToken) {
            return;
          }
          sessionEpoch += 1;
          memoryAccessToken = data.accessToken;
          accessTokenExpiresAtMs = Date.now() + Math.max(0, data.expiresIn) * 1000;
          setSessionMarkerCookie(true, data.refreshExpiresIn);
          notifyUpdatedListeners(data.user);
        } else if (data.type === 'SESSION_CLEARED') {
          sessionEpoch += 1;
          memoryAccessToken = null;
          accessTokenExpiresAtMs = 0;
          writeStoredRefreshToken(null);
          setSessionMarkerCookie(false);
          notifyExpiredListeners();
        }
      });
    } catch {
      // Ignore BroadcastChannel initialization failure
    }
  }

  storageListener = (event: StorageEvent) => {
    if (
      (event.key === null || event.key === REFRESH_TOKEN_STORAGE_KEY) &&
      event.newValue === null
    ) {
      sessionEpoch += 1;
      memoryAccessToken = null;
      accessTokenExpiresAtMs = 0;
      setSessionMarkerCookie(false);
      notifyExpiredListeners();
    }
  };
  window.addEventListener('storage', storageListener);
}

export function subscribeSessionExpired(listener: SessionExpiredListener): () => void {
  initCrossTabAuthSync();
  expiredListeners.add(listener);
  return () => {
    expiredListeners.delete(listener);
  };
}

export function subscribeSessionUpdated(listener: SessionUpdatedListener): () => void {
  initCrossTabAuthSync();
  updatedListeners.add(listener);
  return () => {
    updatedListeners.delete(listener);
  };
}

export function setSessionTokens(
  tokens: Pick<AuthTokenResponse, 'accessToken' | 'refreshToken' | 'expiresIn'> & {
    refreshExpiresIn?: number;
    user?: UserProfileResponse;
  },
): void {
  initCrossTabAuthSync();
  sessionEpoch += 1;
  memoryAccessToken = tokens.accessToken;
  accessTokenExpiresAtMs = Date.now() + Math.max(0, tokens.expiresIn) * 1000;
  writeStoredRefreshToken(tokens.refreshToken);
  setSessionMarkerCookie(true, tokens.refreshExpiresIn);
  postSyncMessage({
    type: 'SESSION_UPDATED',
    accessToken: tokens.accessToken,
    expiresIn: tokens.expiresIn,
    refreshToken: tokens.refreshToken,
    refreshExpiresIn: tokens.refreshExpiresIn,
    user: tokens.user,
  });
}

export function clearSessionTokens(options?: { broadcast?: boolean }): void {
  sessionEpoch += 1;
  memoryAccessToken = null;
  accessTokenExpiresAtMs = 0;
  writeStoredRefreshToken(null);
  setSessionMarkerCookie(false);
  if (options?.broadcast !== false) {
    postSyncMessage({ type: 'SESSION_CLEARED' });
  }
}

export function getAccessToken(): string | null {
  return isTokenUsable(memoryAccessToken, accessTokenExpiresAtMs) ? memoryAccessToken : null;
}

export function getRefreshToken(): string | null {
  return readStoredRefreshToken();
}

export function hasStoredSession(): boolean {
  if (getAccessToken()) {
    return true;
  }
  if (readStoredRefreshToken()) {
    return true;
  }
  if (typeof document !== 'undefined') {
    return document.cookie.split(';').some((part) => part.trim() === `${AUTH_SESSION_COOKIE}=1`);
  }
  return false;
}

async function withCrossTabRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  if (
    typeof navigator !== 'undefined' &&
    navigator.locks &&
    typeof navigator.locks.request === 'function'
  ) {
    return navigator.locks.request(REFRESH_LOCK_NAME, task);
  }
  return task();
}

async function performRefreshExchange(staleAccessToken?: string): Promise<string> {
  // Yield a macrotask so pending BroadcastChannel updates from an earlier tab lock holder apply first.
  await new Promise((resolve) => setTimeout(resolve, 0));
  if (
    memoryAccessToken &&
    memoryAccessToken !== staleAccessToken &&
    isTokenUsable(memoryAccessToken, accessTokenExpiresAtMs)
  ) {
    return memoryAccessToken;
  }

  const refreshToken = readStoredRefreshToken();
  if (!refreshToken) {
    clearSessionTokens({ broadcast: true });
    notifyExpiredListeners();
    throw new ApiError({
      status: 401,
      errorCode: 'AUTHENTICATION_REQUIRED',
      title: 'Authentication Required',
      detail: 'Phiên đăng nhập không tồn tại.',
    });
  }

  const epochBeforeFetch = sessionEpoch;
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), REFRESH_REQUEST_TIMEOUT_MS);

  let response: Response;
  try {
    response = await fetch(`${getApiBaseUrl()}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json, application/problem+json',
      },
      body: JSON.stringify({ refreshToken }),
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timeoutId);
  }

  if (!response.ok) {
    const error = await parseApiErrorResponse(response);
    if (
      (response.status === 401 || response.status === 403) &&
      sessionEpoch === epochBeforeFetch
    ) {
      clearSessionTokens({ broadcast: true });
      notifyExpiredListeners();
    }
    throw error;
  }

  const data = (await response.json()) as AuthTokenResponse;
  if (sessionEpoch !== epochBeforeFetch || readStoredRefreshToken() !== refreshToken) {
    if (
      memoryAccessToken &&
      memoryAccessToken !== staleAccessToken &&
      isTokenUsable(memoryAccessToken, accessTokenExpiresAtMs)
    ) {
      return memoryAccessToken;
    }
    throw new ApiError({
      status: 401,
      errorCode: 'AUTHENTICATION_REQUIRED',
      title: 'Authentication Required',
      detail: 'Phiên đăng nhập đã thay đổi.',
    });
  }

  setSessionTokens(data);
  return data.accessToken;
}

/**
 * Serializes refresh token rotation inside and across browser tabs so concurrent 401 responses
 * never trigger Backend single-use refresh token reuse revocation.
 */
export async function refreshAccessToken(staleAccessToken?: string): Promise<string> {
  if (
    memoryAccessToken &&
    staleAccessToken &&
    memoryAccessToken !== staleAccessToken &&
    isTokenUsable(memoryAccessToken, accessTokenExpiresAtMs)
  ) {
    return memoryAccessToken;
  }

  if (!inFlightRefreshPromise) {
    inFlightRefreshPromise = withCrossTabRefreshLock(() =>
      performRefreshExchange(staleAccessToken),
    ).finally(() => {
      inFlightRefreshPromise = null;
    });
  }
  return inFlightRefreshPromise;
}

export async function ensureValidAccessToken(): Promise<string> {
  const active = getAccessToken();
  if (active) {
    return active;
  }
  return refreshAccessToken();
}

export function resetTokenManagerForTests(): void {
  memoryAccessToken = null;
  accessTokenExpiresAtMs = 0;
  inFlightRefreshPromise = null;
  expiredListeners.clear();
  updatedListeners.clear();
  if (storageListener && isBrowser()) {
    window.removeEventListener('storage', storageListener);
    storageListener = null;
  }
  if (syncChannel) {
    syncChannel.close();
    syncChannel = null;
  }
  syncInitialized = false;
  clearSessionTokens({ broadcast: false });
  sessionEpoch = 0;
}
