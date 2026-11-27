import { apiFetch, buildApiUrl } from '@/lib/api/client';
import { getDocumentPreviewUrl, listAdminDepartments } from '@/lib/api/chat-stream';
import { ensureValidAccessToken, refreshAccessToken } from '@/lib/auth/token-manager';
import type { PageResponse } from '@/lib/types/chat';
import {
  DEFAULT_DOCUMENT_PAGE_SIZE,
  type AccessLevel,
  type DocumentResponse,
  type DocumentStatusResponse,
  type ListDocumentsParams,
  type UpdateDocumentMetadataRequest,
} from '@/lib/types/documents';
import {
  ApiError,
  formatApiErrorMessage,
  parseProblemDetailPayload,
} from '@/lib/types/problem';

export { getDocumentPreviewUrl, listAdminDepartments };

export interface UploadProgressEvent {
  percent: number;
  loaded: number;
  total: number;
}

interface UploadProgressOptions {
  onProgress?: (progress: UploadProgressEvent) => void;
  signal?: AbortSignal;
}

function createAbortError(): DOMException {
  return new DOMException('Aborted', 'AbortError');
}

function sendXhrOnce(
  method: 'POST' | 'PUT',
  targetUrl: string,
  formData: FormData,
  token: string | null,
  options: UploadProgressOptions,
): Promise<{ status: number; bodyText: string; error?: ApiError }> {
  if (options.signal?.aborted) {
    return Promise.reject(createAbortError());
  }

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    let settled = false;

    const cleanup = () => {
      options.signal?.removeEventListener('abort', handleAbort);
    };

    const handleAbort = () => {
      if (settled) {
        return;
      }
      xhr.abort();
    };

    options.signal?.addEventListener('abort', handleAbort, { once: true });

    xhr.open(method, targetUrl, true);
    xhr.setRequestHeader('Accept', 'application/json, application/problem+json');
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    }

    xhr.upload.onprogress = (event) => {
      if (settled || !event.lengthComputable || event.total <= 0) {
        return;
      }
      const percent = Math.min(100, Math.round((event.loaded / event.total) * 100));
      options.onProgress?.({
        percent,
        loaded: event.loaded,
        total: event.total,
      });
    };

    xhr.onload = () => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve({ status: xhr.status, bodyText: xhr.responseText });
        return;
      }
      resolve({
        status: xhr.status,
        bodyText: xhr.responseText,
        error: parseProblemDetailPayload(
          xhr.status,
          xhr.responseText,
          xhr.getResponseHeader('Retry-After'),
        ),
      });
    };

    xhr.onerror = () => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      reject(new Error('Không thể kết nối tới máy chủ DocuMind AI.'));
    };

    xhr.onabort = () => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      reject(createAbortError());
    };

    xhr.send(formData);
  });
}

async function uploadWithProgress(
  method: 'POST' | 'PUT',
  path: string,
  createFormData: () => FormData,
  options: UploadProgressOptions = {},
): Promise<DocumentResponse> {
  if (options.signal?.aborted) {
    throw createAbortError();
  }

  const targetUrl = buildApiUrl(path);
  const usedToken = await ensureValidAccessToken();
  let result = await sendXhrOnce(method, targetUrl, createFormData(), usedToken, options);

  if (result.status === 401 && !options.signal?.aborted) {
    const nextToken = await refreshAccessToken(usedToken ?? undefined);
    result = await sendXhrOnce(method, targetUrl, createFormData(), nextToken, options);
  }

  if (result.error) {
    throw result.error;
  }

  try {
    return JSON.parse(result.bodyText) as DocumentResponse;
  } catch {
    throw new Error('Phản hồi từ máy chủ không hợp lệ.');
  }
}

export async function listDocuments(
  params: ListDocumentsParams = {},
  signal?: AbortSignal,
): Promise<PageResponse<DocumentResponse>> {
  const searchParams = new URLSearchParams();
  searchParams.set('page', String(params.page ?? 0));
  searchParams.set('size', String(params.size ?? DEFAULT_DOCUMENT_PAGE_SIZE));
  if (params.departmentId) {
    searchParams.set('departmentId', params.departmentId);
  }
  if (params.status) {
    searchParams.set('status', params.status);
  }
  if (params.accessLevel) {
    searchParams.set('accessLevel', params.accessLevel);
  }
  return apiFetch<PageResponse<DocumentResponse>>(
    `/api/v1/documents?${searchParams.toString()}`,
    { method: 'GET', signal },
  );
}

export async function getDocumentStatus(
  documentId: string,
  signal?: AbortSignal,
): Promise<DocumentStatusResponse> {
  return apiFetch<DocumentStatusResponse>(
    `/api/v1/documents/${encodeURIComponent(documentId)}/status`,
    { method: 'GET', signal },
  );
}

export async function uploadDocumentWithProgress(params: {
  file: File;
  accessLevel: AccessLevel;
  departmentId?: string;
  onProgress?: (progress: UploadProgressEvent) => void;
  signal?: AbortSignal;
}): Promise<DocumentResponse> {
  const searchParams = new URLSearchParams();
  searchParams.set('accessLevel', params.accessLevel);
  if (params.departmentId) {
    searchParams.set('departmentId', params.departmentId);
  }
  return uploadWithProgress(
    'POST',
    `/api/v1/documents/upload?${searchParams.toString()}`,
    () => {
      const formData = new FormData();
      formData.append('file', params.file);
      return formData;
    },
    { onProgress: params.onProgress, signal: params.signal },
  );
}

export async function replaceDocumentWithProgress(params: {
  documentId: string;
  file: File;
  accessLevel?: AccessLevel;
  onProgress?: (progress: UploadProgressEvent) => void;
  signal?: AbortSignal;
}): Promise<DocumentResponse> {
  const searchParams = new URLSearchParams();
  if (params.accessLevel) {
    searchParams.set('accessLevel', params.accessLevel);
  }
  const query = searchParams.toString();
  const path = `/api/v1/documents/${encodeURIComponent(params.documentId)}${query ? `?${query}` : ''}`;
  return uploadWithProgress(
    'PUT',
    path,
    () => {
      const formData = new FormData();
      formData.append('file', params.file);
      return formData;
    },
    { onProgress: params.onProgress, signal: params.signal },
  );
}

export async function updateDocumentMetadata(
  documentId: string,
  body: UpdateDocumentMetadataRequest,
  signal?: AbortSignal,
): Promise<DocumentResponse> {
  return apiFetch<DocumentResponse>(`/api/v1/documents/${encodeURIComponent(documentId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
    signal,
  });
}

export async function deleteDocument(documentId: string, signal?: AbortSignal): Promise<void> {
  await apiFetch<void>(`/api/v1/documents/${encodeURIComponent(documentId)}`, {
    method: 'DELETE',
    signal,
  });
}

export function formatDocumentErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.errorCode) {
      case 'DUPLICATE_DOCUMENT':
        return 'Tài liệu trùng nội dung đã tồn tại trong phòng ban này.';
      case 'RESOURCE_NOT_FOUND':
        return 'Tài liệu không tồn tại hoặc đã bị xóa.';
      case 'DOCUMENT_TOO_LARGE':
        return 'Tệp vượt quá giới hạn 50 MB.';
      case 'UNSUPPORTED_DOCUMENT_TYPE':
        return 'Định dạng tệp không được hỗ trợ (.pdf, .docx, .txt, .md).';
      default:
        return formatApiErrorMessage(error);
    }
  }
  return formatApiErrorMessage(error);
}
