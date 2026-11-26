export type Role = 'STANDARD_EMPLOYEE' | 'DEPARTMENT_MANAGER' | 'SYSTEM_ADMIN';

export interface UserProfileResponse {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  departmentId: string | null;
  departmentCode: string | null;
  departmentName: string | null;
  isActive: boolean;
  createdAt: string;
}

export interface AuthTokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  refreshExpiresIn: number;
  user: UserProfileResponse;
}

export interface LoginRequestPayload {
  email: string;
  password: string;
}

export const ROLE_LABELS: Record<Role, string> = {
  STANDARD_EMPLOYEE: 'Nhân viên (Viewer)',
  DEPARTMENT_MANAGER: 'Trưởng bộ phận (Manager)',
  SYSTEM_ADMIN: 'Quản trị hệ thống (Admin)',
};
