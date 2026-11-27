import type { Role } from '@/lib/types/auth';
import type { CacheTier, PageResponse } from '@/lib/types/chat';

export type { PageResponse };

export type AuditActionType =
  | 'CHAT_QUERY'
  | 'DOC_UPLOAD'
  | 'DOC_REINDEX'
  | 'DOC_DELETE'
  | 'AUTH_LOGIN'
  | 'AUTH_LOGOUT'
  | 'RATE_LIMITED';

export const AUDIT_ACTION_TYPES: readonly AuditActionType[] = [
  'CHAT_QUERY',
  'DOC_UPLOAD',
  'DOC_REINDEX',
  'DOC_DELETE',
  'AUTH_LOGIN',
  'AUTH_LOGOUT',
  'RATE_LIMITED',
];

export const AUDIT_ACTION_LABELS: Record<AuditActionType, string> = {
  CHAT_QUERY: 'Truy vấn Chat',
  DOC_UPLOAD: 'Tải lên tài liệu',
  DOC_REINDEX: 'Cập nhật phiên bản',
  DOC_DELETE: 'Xóa tài liệu',
  AUTH_LOGIN: 'Đăng nhập',
  AUTH_LOGOUT: 'Đăng xuất',
  RATE_LIMITED: 'Vượt giới hạn (429)',
};

export const UNASSIGNED_DEPARTMENT_LABEL = 'Hệ thống / Chưa gán phòng ban';
export const DEPARTMENT_CODE_REGEX = /^[A-Z0-9_-]{2,32}$/;
export const MIN_PASSWORD_LENGTH = 8;
export const MAX_PASSWORD_UTF8_BYTES = 72;

export function getUtf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}

export interface AdminTimeWindowParams {
  from?: string;
  to?: string;
}

export interface AdminAnalyticsSummaryResponse {
  from: string;
  to: string;
  successfulQueries: number;
  cacheHits: number;
  cacheHitRatioPercent: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  averageLatencyMs: number;
}

export interface AdminDepartmentTokenUsageResponse {
  departmentId: string | null;
  departmentCode: string | null;
  departmentName: string | null;
  successfulQueries: number;
  cacheHits: number;
  cacheHitRatioPercent: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface AdminDepartmentTokenUsageReportResponse {
  from: string;
  to: string;
  items: AdminDepartmentTokenUsageResponse[];
}

export interface AdminDailyTrendPointResponse {
  date: string;
  successfulQueries: number;
  cacheHits: number;
  cacheHitRatioPercent: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface AdminDailyTrendsResponse {
  from: string;
  to: string;
  items: AdminDailyTrendPointResponse[];
}

export interface AdminAuditLogItemResponse {
  id: string;
  userId: string | null;
  userEmail: string | null;
  departmentId: string | null;
  departmentCode: string | null;
  departmentName: string | null;
  sessionId: string | null;
  actionType: AuditActionType;
  queryText: string | null;
  abstained: boolean;
  cacheHit: boolean;
  cacheTier: CacheTier;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  latencyMs: number;
  provider: string | null;
  statusCode: number;
  ipAddress: string | null;
  createdAt: string;
}

export interface AdminAuditLogsQuery extends AdminTimeWindowParams {
  actionType?: AuditActionType;
  departmentId?: string;
  userId?: string;
  statusCode?: number;
  cacheHit?: boolean;
  page?: number;
  size?: number;
}

export interface AdminUserResponse {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  departmentId: string | null;
  departmentCode: string | null;
  departmentName: string | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AdminUsersQuery {
  departmentId?: string;
  role?: Role;
  isActive?: boolean;
  q?: string;
  page?: number;
  size?: number;
}

export interface CreateUserRequest {
  email: string;
  fullName: string;
  password: string;
  role: Role;
  departmentId: string;
  isActive?: boolean;
}

export interface UpdateUserRequest {
  email?: string;
  fullName?: string;
  role?: Role;
  departmentId?: string;
  isActive?: boolean;
}

export interface AdminResetPasswordRequest {
  newPassword: string;
}

export interface AdminDepartmentResponse {
  id: string;
  code: string;
  name: string;
  description: string | null;
  cacheVersion: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdminDepartmentsQuery {
  q?: string;
  page?: number;
  size?: number;
}

export interface CreateDepartmentRequest {
  code: string;
  name: string;
  description?: string | null;
}

export interface UpdateDepartmentRequest {
  code?: string;
  name?: string;
  description?: string | null;
}
