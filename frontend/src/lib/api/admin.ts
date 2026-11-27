import { apiFetch } from '@/lib/api/client';
import type {
  AdminAnalyticsSummaryResponse,
  AdminAuditLogItemResponse,
  AdminAuditLogsQuery,
  AdminDailyTrendsResponse,
  AdminDepartmentResponse,
  AdminDepartmentsQuery,
  AdminDepartmentTokenUsageReportResponse,
  AdminResetPasswordRequest,
  AdminTimeWindowParams,
  AdminUserResponse,
  AdminUsersQuery,
  CreateDepartmentRequest,
  CreateUserRequest,
  PageResponse,
  UpdateDepartmentRequest,
  UpdateUserRequest,
} from '@/lib/types/admin';

function buildQueryString(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : '';
}

export async function getAdminAnalyticsSummary(
  params: AdminTimeWindowParams = {},
  signal?: AbortSignal,
): Promise<AdminAnalyticsSummaryResponse> {
  const qs = buildQueryString({ from: params.from, to: params.to });
  return apiFetch<AdminAnalyticsSummaryResponse>(`/api/v1/admin/analytics/summary${qs}`, {
    method: 'GET',
    signal,
  });
}

export async function getAdminTokenUsageByDepartment(
  params: AdminTimeWindowParams = {},
  signal?: AbortSignal,
): Promise<AdminDepartmentTokenUsageReportResponse> {
  const qs = buildQueryString({ from: params.from, to: params.to });
  return apiFetch<AdminDepartmentTokenUsageReportResponse>(
    `/api/v1/admin/analytics/token-by-department${qs}`,
    { method: 'GET', signal },
  );
}

export async function getAdminDailyTrends(
  params: AdminTimeWindowParams = {},
  signal?: AbortSignal,
): Promise<AdminDailyTrendsResponse> {
  const qs = buildQueryString({ from: params.from, to: params.to });
  return apiFetch<AdminDailyTrendsResponse>(`/api/v1/admin/analytics/daily-trends${qs}`, {
    method: 'GET',
    signal,
  });
}

export async function listAdminAuditLogs(
  params: AdminAuditLogsQuery = {},
  signal?: AbortSignal,
): Promise<PageResponse<AdminAuditLogItemResponse>> {
  const qs = buildQueryString({
    from: params.from,
    to: params.to,
    actionType: params.actionType,
    departmentId: params.departmentId,
    userId: params.userId,
    statusCode: params.statusCode,
    cacheHit: params.cacheHit,
    page: params.page ?? 0,
    size: params.size ?? 20,
  });
  return apiFetch<PageResponse<AdminAuditLogItemResponse>>(
    `/api/v1/admin/analytics/audit-logs${qs}`,
    { method: 'GET', signal },
  );
}

export async function listAdminUsers(
  params: AdminUsersQuery = {},
  signal?: AbortSignal,
): Promise<PageResponse<AdminUserResponse>> {
  const qs = buildQueryString({
    departmentId: params.departmentId,
    role: params.role,
    isActive: params.isActive,
    q: params.q?.trim(),
    page: params.page ?? 0,
    size: params.size ?? 20,
  });
  return apiFetch<PageResponse<AdminUserResponse>>(`/api/v1/admin/users${qs}`, {
    method: 'GET',
    signal,
  });
}

export async function createAdminUser(payload: CreateUserRequest): Promise<AdminUserResponse> {
  return apiFetch<AdminUserResponse>('/api/v1/admin/users', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function updateAdminUser(
  id: string,
  payload: UpdateUserRequest,
): Promise<AdminUserResponse> {
  return apiFetch<AdminUserResponse>(`/api/v1/admin/users/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

export async function resetAdminUserPassword(
  id: string,
  payload: AdminResetPasswordRequest,
): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/users/${encodeURIComponent(id)}/password`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export async function listAdminDepartmentsPage(
  params: AdminDepartmentsQuery = {},
  signal?: AbortSignal,
): Promise<PageResponse<AdminDepartmentResponse>> {
  const qs = buildQueryString({
    q: params.q?.trim(),
    page: params.page ?? 0,
    size: params.size ?? 20,
  });
  return apiFetch<PageResponse<AdminDepartmentResponse>>(`/api/v1/admin/departments${qs}`, {
    method: 'GET',
    signal,
  });
}

export async function createAdminDepartment(
  payload: CreateDepartmentRequest,
): Promise<AdminDepartmentResponse> {
  return apiFetch<AdminDepartmentResponse>('/api/v1/admin/departments', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function updateAdminDepartment(
  id: string,
  payload: UpdateDepartmentRequest,
): Promise<AdminDepartmentResponse> {
  return apiFetch<AdminDepartmentResponse>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

export async function deleteAdminDepartment(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
