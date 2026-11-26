const DEFAULT_API_BASE_URL = 'http://localhost:8081';

export function getApiBaseUrl(): string {
  const raw = process.env.NEXT_PUBLIC_API_BASE_URL;
  const trimmed = raw ? raw.trim() : '';
  const base = trimmed.length > 0 ? trimmed : DEFAULT_API_BASE_URL;
  return base.replace(/\/+$/, '');
}

export function isDemoAccountsEnabled(): boolean {
  return process.env.NEXT_PUBLIC_DEMO_ACCOUNTS === 'true';
}
