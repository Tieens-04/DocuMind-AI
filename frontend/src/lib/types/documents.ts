import type { UserProfileResponse } from '@/lib/types/auth';

export type AccessLevel = 'PUBLIC' | 'DEPARTMENT' | 'CONFIDENTIAL';
export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface DocumentResponse {
  id: string;
  departmentId: string;
  uploadedBy: string | null;
  originalName: string;
  fileExtension: string;
  mimeType: string;
  fileSizeBytes: number;
  fileHash: string;
  accessLevel: AccessLevel;
  status: DocumentStatus;
  currentVersion: number;
  chunkCount: number;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentStatusResponse {
  id: string;
  status: DocumentStatus;
  chunkCount: number;
  currentVersion: number;
  errorMessage: string | null;
  updatedAt: string;
}

export interface UpdateDocumentMetadataRequest {
  originalName?: string;
  accessLevel?: AccessLevel;
  departmentId?: string;
}

export interface ListDocumentsParams {
  departmentId?: string;
  status?: DocumentStatus;
  accessLevel?: AccessLevel;
  page?: number;
  size?: number;
}

export type UploadQueueItemStatus =
  | 'queued'
  | 'uploading'
  | 'paused_rate_limit'
  | 'uploaded'
  | 'cancelled'
  | 'error';

export interface UploadQueueItem {
  id: string;
  file: File;
  accessLevel: AccessLevel;
  departmentId?: string;
  status: UploadQueueItemStatus;
  progress: number | null;
  errorMessage: string | null;
}

export const MAX_DOCUMENT_SIZE_BYTES = 52_428_800; // 50 MiB, matches HashingBoundedInputStream
export const ALLOWED_DOCUMENT_EXTENSIONS = ['.pdf', '.docx', '.txt', '.md'] as const;
export const DOCUMENT_POLL_INTERVAL_MS = 2500;
export const DEFAULT_DOCUMENT_PAGE_SIZE = 100;

export const ACCESS_LEVEL_LABELS: Record<AccessLevel, string> = {
  DEPARTMENT: 'Phòng ban',
  PUBLIC: 'Công khai',
  CONFIDENTIAL: 'Bảo mật',
};

export const DOCUMENT_STATUS_LABELS: Record<DocumentStatus, string> = {
  PENDING: 'Đang chờ',
  PROCESSING: 'Đang xử lý',
  COMPLETED: 'Hoàn tất',
  FAILED: 'Lỗi xử lý',
};

export function validateDocumentFile(file: File): string | null {
  const trimmedName = file.name.trim();
  const lowerName = trimmedName.toLowerCase();
  const hasValidExt = ALLOWED_DOCUMENT_EXTENSIONS.some((ext) => lowerName.endsWith(ext));
  if (!hasValidExt) {
    return 'Chỉ hỗ trợ tệp .pdf, .docx, .txt hoặc .md.';
  }
  const lastDot = trimmedName.lastIndexOf('.');
  if (lastDot <= 0 || trimmedName.slice(0, lastDot).replace(/\./g, '').trim().length === 0) {
    return 'Tên tệp phải có nội dung trước phần mở rộng.';
  }
  if (trimmedName.length > 255) {
    return 'Tên tệp không được vượt quá 255 ký tự.';
  }
  if (file.size <= 0) {
    return 'Tệp rỗng (0 byte) không thể tải lên.';
  }
  if (file.size > MAX_DOCUMENT_SIZE_BYTES) {
    return 'Tệp vượt quá giới hạn tối đa 50 MB.';
  }
  return null;
}

export function normalizeVietnameseSearch(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[đĐ]/g, 'd')
    .toLowerCase()
    .trim();
}

export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '0 B';
  }
  if (bytes < 1024) {
    return `${Math.round(bytes)} B`;
  }
  const kb = bytes / 1024;
  if (kb < 1024) {
    return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)} KB`;
  }
  const mb = kb / 1024;
  return `${mb.toFixed(mb < 10 ? 2 : 1)} MB`;
}

export function canMutateDocument(
  doc: Pick<DocumentResponse, 'departmentId'>,
  user: Pick<UserProfileResponse, 'role' | 'departmentId'> | null,
): boolean {
  if (!user) {
    return false;
  }
  if (user.role === 'SYSTEM_ADMIN') {
    return true;
  }
  if (user.role === 'DEPARTMENT_MANAGER') {
    return Boolean(user.departmentId && doc.departmentId === user.departmentId);
  }
  return false;
}
