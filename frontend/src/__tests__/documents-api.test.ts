import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  deleteDocument,
  formatDocumentErrorMessage,
  getDocumentStatus,
  listDocuments,
  replaceDocumentWithProgress,
  updateDocumentMetadata,
  uploadDocumentWithProgress,
} from '@/lib/api/documents';
import { resetTokenManagerForTests, setSessionTokens } from '@/lib/auth/token-manager';
import {
  MAX_DOCUMENT_SIZE_BYTES,
  canMutateDocument,
  formatFileSize,
  normalizeVietnameseSearch,
  validateDocumentFile,
  type DocumentResponse,
} from '@/lib/types/documents';
import { ApiError } from '@/lib/types/problem';
import { installMockXhr } from './helpers/mock-xhr';

function buildDocumentResponse(overrides: Partial<DocumentResponse> = {}): DocumentResponse {
  return {
    id: 'doc-1',
    departmentId: 'dept-it',
    uploadedBy: 'user-1',
    originalName: 'policy.pdf',
    fileExtension: 'pdf',
    mimeType: 'application/pdf',
    fileSizeBytes: 2048,
    fileHash: 'hash-1',
    accessLevel: 'DEPARTMENT',
    status: 'PENDING',
    currentVersion: 1,
    chunkCount: 0,
    errorMessage: null,
    createdAt: '2026-11-27T10:00:00Z',
    updatedAt: '2026-11-27T10:00:00Z',
    ...overrides,
  };
}

describe('documents API & domain helpers', () => {
  beforeEach(() => {
    resetTokenManagerForTests();
    vi.stubEnv('NEXT_PUBLIC_API_BASE_URL', 'http://localhost:8081');
    setSessionTokens({
      accessToken: 'doc-access-token',
      refreshToken: 'doc-refresh-token',
      expiresIn: 900,
    });
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('validates file extensions, empty/stemless files, 50 MiB ceiling, Vietnamese normalization, and RBAC mutation rules', () => {
    expect(validateDocumentFile(new File(['abc'], 'Handbook.PDF', { type: 'application/pdf' }))).toBeNull();
    expect(validateDocumentFile(new File(['abc'], 'notes.md'))).toBeNull();
    expect(validateDocumentFile(new File(['abc'], 'script.exe'))).toContain('.pdf, .docx, .txt');
    expect(validateDocumentFile(new File(['abc'], '.pdf'))).toContain('trước phần mở rộng');
    expect(validateDocumentFile(new File([], 'empty.txt'))).toContain('0 byte');

    const exactBoundary = { name: 'exact.pdf', size: MAX_DOCUMENT_SIZE_BYTES } as File;
    expect(validateDocumentFile(exactBoundary)).toBeNull();

    const oversized = { name: 'big.pdf', size: MAX_DOCUMENT_SIZE_BYTES + 1 } as File;
    expect(validateDocumentFile(oversized)).toContain('50 MB');

    expect(normalizeVietnameseSearch('Chính sách Nghỉ phép Đặc biệt.PDF')).toBe(
      'chinh sach nghi phep dac biet.pdf',
    );
    expect(formatFileSize(1536)).toBe('1.5 KB');
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5.00 MB');

    expect(
      canMutateDocument(
        { departmentId: 'dept-hr' },
        { role: 'DEPARTMENT_MANAGER', departmentId: 'dept-it' },
      ),
    ).toBe(false);
    expect(
      canMutateDocument(
        { departmentId: 'dept-it' },
        { role: 'DEPARTMENT_MANAGER', departmentId: 'dept-it' },
      ),
    ).toBe(true);
    expect(
      canMutateDocument(
        { departmentId: 'dept-hr' },
        { role: 'SYSTEM_ADMIN', departmentId: null },
      ),
    ).toBe(true);
    expect(
      canMutateDocument(
        { departmentId: 'dept-it' },
        { role: 'STANDARD_EMPLOYEE', departmentId: 'dept-it' },
      ),
    ).toBe(false);
    expect(canMutateDocument({ departmentId: 'dept-it' }, null)).toBe(false);
  });

  it('uploads document via XHR with Bearer auth, omits Content-Type, emits progress, and parses 202 response', async () => {
    const progressEvents: number[] = [];
    const expected = buildDocumentResponse({ originalName: 'guide.pdf', accessLevel: 'PUBLIC' });

    const records = installMockXhr((req) => {
      queueMicrotask(() => {
        req.emitProgress(512, 1024);
        req.emitProgress(1024, 1024);
        req.respond(202, expected);
      });
    });

    const file = new File(['hello'], 'guide.pdf', { type: 'application/pdf' });
    const actual = await uploadDocumentWithProgress({
      file,
      accessLevel: 'PUBLIC',
      departmentId: 'dept-hr',
      onProgress: (p) => progressEvents.push(p.percent),
    });

    expect(actual).toEqual(expected);
    expect(progressEvents).toEqual([50, 100]);
    expect(records).toHaveLength(1);
    expect(records[0].method).toBe('POST');
    expect(records[0].url).toBe(
      'http://localhost:8081/api/v1/documents/upload?accessLevel=PUBLIC&departmentId=dept-hr',
    );
    expect(records[0].headers.Authorization).toBe('Bearer doc-access-token');
    expect(records[0].headers['Content-Type']).toBeUndefined();
    expect(records[0].body?.get('file')).toBeInstanceOf(File);
  });

  it('performs single-flight 401 token refresh and retries XHR upload with a fresh FormData', async () => {
    setSessionTokens({
      accessToken: 'expired-token',
      refreshToken: 'valid-refresh',
      expiresIn: 900,
      refreshExpiresIn: 3600,
    });

    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      expect(String(input)).toBe('http://localhost:8081/api/v1/auth/refresh');
      return new Response(
        JSON.stringify({
          accessToken: 'fresh-token',
          refreshToken: 'rotated-refresh',
          tokenType: 'Bearer',
          expiresIn: 900,
          refreshExpiresIn: 3600,
          user: {
            id: 'user-1',
            email: 'manager@documind.ai',
            fullName: 'Manager',
            role: 'DEPARTMENT_MANAGER',
            departmentId: 'dept-it',
            departmentCode: 'IT',
            departmentName: 'IT',
            isActive: true,
            createdAt: '2026-11-26T00:00:00Z',
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const expected = buildDocumentResponse({ id: 'doc-refreshed' });
    const records = installMockXhr((req) => {
      queueMicrotask(() => {
        if (req.headers.Authorization === 'Bearer expired-token') {
          req.respond(401, { status: 401, errorCode: 'TOKEN_EXPIRED', detail: 'Expired' });
          return;
        }
        req.respond(202, expected);
      });
    });

    const actual = await uploadDocumentWithProgress({
      file: new File(['data'], 'retry.txt', { type: 'text/plain' }),
      accessLevel: 'DEPARTMENT',
    });

    expect(actual.id).toBe('doc-refreshed');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(records).toHaveLength(2);
    expect(records[0].headers.Authorization).toBe('Bearer expired-token');
    expect(records[1].headers.Authorization).toBe('Bearer fresh-token');
    expect(records[1].body).not.toBe(records[0].body);
  });

  it('aborts active XHR, handles network failure, and maps all Backend ProblemDetail error codes', async () => {
    const controller = new AbortController();
    const records = installMockXhr((req) => {
      if (req.url.includes('cancel-me')) {
        queueMicrotask(() => controller.abort());
        return;
      }
      if (req.url.includes('network-fail')) {
        queueMicrotask(() => req.failNetwork());
        return;
      }
      if (req.url.includes('accessLevel=CONFIDENTIAL')) {
        queueMicrotask(() =>
          req.respond(
            429,
            {
              status: 429,
              errorCode: 'RATE_LIMIT_EXCEEDED',
              detail: 'Upload rate limit exceeded',
            },
            { 'Retry-After': '18' },
          ),
        );
        return;
      }
      queueMicrotask(() =>
        req.respond(409, {
          status: 409,
          errorCode: 'DUPLICATE_DOCUMENT',
          detail: 'Duplicate hash',
        }),
      );
    });

    await expect(
      replaceDocumentWithProgress({
        documentId: 'cancel-me',
        file: new File(['abc'], 'v2.pdf', { type: 'application/pdf' }),
        signal: controller.signal,
      }),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(records[0].aborted).toBe(true);

    await expect(
      replaceDocumentWithProgress({
        documentId: 'network-fail',
        file: new File(['abc'], 'v2.pdf', { type: 'application/pdf' }),
      }),
    ).rejects.toThrow('Không thể kết nối tới máy chủ DocuMind AI.');

    let rateLimitError: unknown;
    try {
      await uploadDocumentWithProgress({
        file: new File(['abc'], 'secret.pdf', { type: 'application/pdf' }),
        accessLevel: 'CONFIDENTIAL',
      });
    } catch (err) {
      rateLimitError = err;
    }
    expect(rateLimitError).toBeInstanceOf(ApiError);
    expect((rateLimitError as ApiError).status).toBe(429);
    expect((rateLimitError as ApiError).retryAfterSeconds).toBe(18);
    expect(formatDocumentErrorMessage(rateLimitError)).toContain('18 giây');

    let duplicateError: unknown;
    try {
      await uploadDocumentWithProgress({
        file: new File(['abc'], 'dup.pdf', { type: 'application/pdf' }),
        accessLevel: 'DEPARTMENT',
      });
    } catch (err) {
      duplicateError = err;
    }
    expect(duplicateError).toBeInstanceOf(ApiError);
    expect((duplicateError as ApiError).status).toBe(409);
    expect(formatDocumentErrorMessage(duplicateError)).toContain('trùng nội dung');

    expect(
      formatDocumentErrorMessage(
        new ApiError({ status: 404, errorCode: 'RESOURCE_NOT_FOUND', detail: 'Not found' }),
      ),
    ).toBe('Tài liệu không tồn tại hoặc đã bị xóa.');
    expect(
      formatDocumentErrorMessage(
        new ApiError({ status: 413, errorCode: 'DOCUMENT_TOO_LARGE', detail: 'Too large' }),
      ),
    ).toBe('Tệp vượt quá giới hạn 50 MB.');
    expect(
      formatDocumentErrorMessage(
        new ApiError({
          status: 415,
          errorCode: 'UNSUPPORTED_DOCUMENT_TYPE',
          detail: 'Unsupported media type',
        }),
      ),
    ).toBe('Định dạng tệp không được hỗ trợ (.pdf, .docx, .txt, .md).');
    expect(
      formatDocumentErrorMessage(
        new ApiError({ status: 403, errorCode: 'DOCUMENT_ACCESS_DENIED', detail: 'Forbidden' }),
      ),
    ).toBe('Bạn không có quyền thực hiện thao tác này.');
  });

  it('calls listDocuments, getDocumentStatus, updateDocumentMetadata, and deleteDocument with expected contracts', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith('http://localhost:8081/api/v1/documents?')) {
        expect(url).toContain('page=0');
        expect(url).toContain('size=100');
        expect(url).toContain('status=PROCESSING');
        expect(url).toContain('accessLevel=PUBLIC');
        expect(url).toContain('departmentId=dept-hr');
        return new Response(
          JSON.stringify({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          { status: 200 },
        );
      }
      if (url.endsWith('/api/v1/documents/doc-1/status')) {
        return new Response(
          JSON.stringify({
            id: 'doc-1',
            status: 'COMPLETED',
            chunkCount: 12,
            currentVersion: 1,
            errorMessage: null,
            updatedAt: '2026-11-27T10:05:00Z',
          }),
          { status: 200 },
        );
      }
      if (url.endsWith('/api/v1/documents/doc-1') && init?.method === 'PATCH') {
        expect(JSON.parse(String(init.body))).toEqual({
          originalName: 'renamed.pdf',
          accessLevel: 'PUBLIC',
        });
        return new Response(
          JSON.stringify(buildDocumentResponse({ originalName: 'renamed.pdf', accessLevel: 'PUBLIC' })),
          { status: 200 },
        );
      }
      if (url.endsWith('/api/v1/documents/doc-1') && init?.method === 'DELETE') {
        return new Response(null, { status: 204 });
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const list = await listDocuments({
      status: 'PROCESSING',
      accessLevel: 'PUBLIC',
      departmentId: 'dept-hr',
    });
    expect(list.items).toEqual([]);

    const status = await getDocumentStatus('doc-1');
    expect(status.status).toBe('COMPLETED');
    expect(status.chunkCount).toBe(12);

    const patched = await updateDocumentMetadata('doc-1', {
      originalName: 'renamed.pdf',
      accessLevel: 'PUBLIC',
    });
    expect(patched.originalName).toBe('renamed.pdf');

    await deleteDocument('doc-1');
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });
});
