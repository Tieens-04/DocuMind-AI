import React from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DocumentsPage from '@/app/(workspace)/documents/page';
import { resetTokenManagerForTests, setSessionTokens } from '@/lib/auth/token-manager';
import type { Role, UserProfileResponse } from '@/lib/types/auth';
import { MAX_DOCUMENT_SIZE_BYTES, type DocumentResponse } from '@/lib/types/documents';
import { installMockXhr } from './helpers/mock-xhr';

let mockUser: UserProfileResponse;

vi.mock('@/providers/auth-provider', () => ({
  useAuth: () => ({
    user: mockUser,
    status: 'authenticated',
    bootstrapError: null,
    login: vi.fn(),
    logout: vi.fn(),
    retryBootstrap: vi.fn(),
  }),
}));

function buildUser(role: Role, departmentId: string | null = 'dept-it'): UserProfileResponse {
  return {
    id: 'user-1',
    email: `${role.toLowerCase()}@documind.ai`,
    fullName: role === 'SYSTEM_ADMIN' ? 'Admin User' : 'Department Manager',
    role,
    departmentId,
    departmentCode: departmentId ? 'IT' : null,
    departmentName: departmentId ? 'Engineering' : null,
    isActive: true,
    createdAt: '2026-11-26T00:00:00Z',
  };
}

function buildDoc(overrides: Partial<DocumentResponse> = {}): DocumentResponse {
  return {
    id: 'doc-1',
    departmentId: 'dept-it',
    uploadedBy: 'user-1',
    originalName: 'Tài liệu nội bộ.pdf',
    fileExtension: 'pdf',
    mimeType: 'application/pdf',
    fileSizeBytes: 10240,
    fileHash: 'hash-1',
    accessLevel: 'DEPARTMENT',
    status: 'COMPLETED',
    currentVersion: 1,
    chunkCount: 8,
    errorMessage: null,
    createdAt: '2026-11-27T09:00:00Z',
    updatedAt: '2026-11-27T09:05:00Z',
    ...overrides,
  };
}

describe('DocumentsPage workspace integration', () => {
  beforeEach(() => {
    resetTokenManagerForTests();
    mockUser = buildUser('DEPARTMENT_MANAGER', 'dept-it');
    vi.stubEnv('NEXT_PUBLIC_API_BASE_URL', 'http://localhost:8081');
    setSessionTokens({
      accessToken: 'manager-access-token',
      refreshToken: 'manager-refresh-token',
      expiresIn: 900,
    });
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('validates invalid file types, 0-byte files, and >50MB files, and hides Admin department selector for DEPARTMENT_MANAGER', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(
          JSON.stringify({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          { status: 200 },
        ),
      ),
    );

    render(<DocumentsPage />);

    await waitFor(() => {
      expect(screen.getByText(/Kho tài liệu hiện chưa có tệp nào/i)).toBeInTheDocument();
    });

    expect(screen.queryByLabelText(/Phòng ban sở hữu/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Lọc theo phòng ban/i)).not.toBeInTheDocument();

    const fileInput = screen.getByLabelText('Chọn tệp tải lên') as HTMLInputElement;
    expect(fileInput).toHaveAttribute('tabindex', '-1');

    const badExt = new File(['bad'], 'malware.exe', { type: 'application/octet-stream' });
    const emptyFile = new File([], 'empty.pdf', { type: 'application/pdf' });
    const tooLarge = new File(['x'], 'huge.pdf', { type: 'application/pdf' });
    Object.defineProperty(tooLarge, 'size', { value: MAX_DOCUMENT_SIZE_BYTES + 10 });

    fireEvent.change(fileInput, { target: { files: [badExt, emptyFile, tooLarge] } });

    expect(await screen.findByText(/malware\.exe: Chỉ hỗ trợ tệp/i)).toBeInTheDocument();
    expect(screen.getByText(/empty\.pdf: Tệp rỗng/i)).toBeInTheDocument();
    expect(screen.getByText(/huge\.pdf: Tệp vượt quá giới hạn tối đa 50 MB/i)).toBeInTheDocument();
  });

  it('requires department selection for SYSTEM_ADMIN without departmentId, supports drop + active XHR abort, and resumes queued files even if 429-paused item is cancelled', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mockUser = buildUser('SYSTEM_ADMIN', null);

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('/api/v1/admin/departments')) {
          return new Response(
            JSON.stringify({
              items: [
                {
                  id: 'dept-hr',
                  code: 'HR',
                  name: 'Nhân sự',
                  description: null,
                  cacheVersion: 1,
                  createdAt: '2026-11-26T00:00:00Z',
                  updatedAt: '2026-11-26T00:00:00Z',
                },
              ],
              page: 0,
              size: 100,
              totalElements: 1,
              totalPages: 1,
            }),
            { status: 200 },
          );
        }
        return new Response(
          JSON.stringify({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          { status: 200 },
        );
      }),
    );

    const xhrCalls = installMockXhr(() => {});

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<DocumentsPage />);

    const deptSelect = await screen.findByLabelText(/Phòng ban sở hữu/i);
    await waitFor(() => {
      expect(within(deptSelect).getByRole('option', { name: 'HR - Nhân sự' })).toBeInTheDocument();
    });

    const selectButton = screen.getByRole('button', { name: 'Chọn tệp từ máy tính' });
    const fileInput = screen.getByLabelText('Chọn tệp tải lên');
    expect(selectButton).toBeDisabled();
    expect(fileInput).toBeDisabled();

    await user.selectOptions(deptSelect, 'dept-hr');
    expect(selectButton).toBeEnabled();
    expect(fileInput).toBeEnabled();

    const f1 = new File(['one'], 'first.pdf', { type: 'application/pdf' });
    const f2 = new File(['two'], 'second.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });
    const f3 = new File(['three'], 'third.txt', { type: 'text/plain' });

    const dropZone = screen.getByText(/Kéo và thả tài liệu vào đây/i).closest('div')!;
    fireEvent.drop(dropZone, { dataTransfer: { files: [f1, f2, f3] } });

    await waitFor(() => {
      expect(xhrCalls).toHaveLength(1);
    });
    expect(xhrCalls[0].url).toContain('departmentId=dept-hr');
    expect(xhrCalls[0].url).toContain('accessLevel=DEPARTMENT');

    act(() => {
      xhrCalls[0].emitProgress(60, 100);
    });
    expect(screen.getByRole('progressbar', { name: 'Tiến trình tải lên first.pdf' })).toHaveValue(60);
    expect(screen.getByText('60%')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hủy tải lên first.pdf' }));
    expect(xhrCalls[0].aborted).toBe(true);

    await waitFor(() => {
      expect(xhrCalls).toHaveLength(2);
    });
    expect(xhrCalls[1].fileName).toBe('second.docx');

    act(() => {
      xhrCalls[1].respond(
        429,
        { status: 429, errorCode: 'RATE_LIMIT_EXCEEDED', detail: 'Too many uploads' },
        { 'Retry-After': '2' },
      );
    });

    expect(
      await screen.findByText(/Đạt giới hạn tải lên\. Hàng đợi tự động tiếp tục sau 2s/i),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hủy tải lên second.docx' }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2050);
    });

    await waitFor(() => {
      expect(xhrCalls).toHaveLength(3);
    });
    expect(xhrCalls[2].fileName).toBe('third.txt');

    act(() => {
      xhrCalls[2].respond(
        202,
        buildDoc({
          id: 'doc-third',
          departmentId: 'dept-hr',
          originalName: 'third.txt',
          fileExtension: 'txt',
          status: 'COMPLETED',
        }),
      );
    });

    const tableRegion = screen.getByRole('region', { name: 'Danh sách tài liệu' });
    await waitFor(() => {
      expect(within(tableRegion).getByText('third.txt')).toBeInTheDocument();
    });
  });

  it('supports Vietnamese accent-insensitive search, server filters, conditional 2.5s polling, and stops polling on 404 or COMPLETED', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let visibility: DocumentVisibilityState = 'visible';
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    });

    const pendingDoc = buildDoc({
      id: 'doc-pending',
      originalName: 'Chính sách nghỉ phép.pdf',
      status: 'PENDING',
      chunkCount: 0,
    });
    const deletedPendingDoc = buildDoc({
      id: 'doc-gone',
      originalName: 'Tài liệu đã xóa.pdf',
      status: 'PENDING',
      chunkCount: 0,
    });
    const failedDoc = buildDoc({
      id: 'doc-failed',
      originalName: 'Tài liệu lỗi.docx',
      status: 'FAILED',
      errorMessage: 'Không thể giải mã tệp DOCX.',
    });

    let statusCallCount = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/v1/documents/doc-gone/status')) {
        return new Response(
          JSON.stringify({ status: 404, errorCode: 'RESOURCE_NOT_FOUND', detail: 'Not found' }),
          { status: 404 },
        );
      }
      if (url.includes('/api/v1/documents/doc-pending/status')) {
        statusCallCount += 1;
        if (statusCallCount === 1) {
          return new Response(
            JSON.stringify({
              id: 'doc-pending',
              status: 'PROCESSING',
              chunkCount: 0,
              currentVersion: 1,
              errorMessage: null,
              updatedAt: '2026-11-27T09:05:05Z',
            }),
            { status: 200 },
          );
        }
        return new Response(
          JSON.stringify({
            id: 'doc-pending',
            status: 'COMPLETED',
            chunkCount: 14,
            currentVersion: 1,
            errorMessage: null,
            updatedAt: '2026-11-27T09:05:10Z',
          }),
          { status: 200 },
        );
      }

      if (url.includes('status=FAILED')) {
        return new Response(
          JSON.stringify({
            items: [failedDoc],
            page: 0,
            size: 100,
            totalElements: 1,
            totalPages: 1,
          }),
          { status: 200 },
        );
      }

      return new Response(
        JSON.stringify({
          items: [pendingDoc, deletedPendingDoc, failedDoc],
          page: 0,
          size: 100,
          totalElements: 3,
          totalPages: 1,
        }),
        { status: 200 },
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<DocumentsPage />);

    expect(await screen.findByText('Chính sách nghỉ phép.pdf')).toBeInTheDocument();
    expect(screen.getByText('Tài liệu đã xóa.pdf')).toBeInTheDocument();
    expect(screen.getByText('Không thể giải mã tệp DOCX.')).toBeInTheDocument();
    expect(screen.getByText(/Đang theo dõi 2 tài liệu/i)).toBeInTheDocument();

    act(() => {
      visibility = 'hidden';
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(statusCallCount).toBe(0);

    act(() => {
      visibility = 'visible';
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2550);
    });
    expect(statusCallCount).toBe(1);
    await waitFor(() => {
      expect(screen.queryByText('Tài liệu đã xóa.pdf')).not.toBeInTheDocument();
    });
    const pendingRow = screen.getByText('Chính sách nghỉ phép.pdf').closest('tr')!;
    expect(await within(pendingRow).findByText('Đang xử lý')).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2550);
    });
    expect(statusCallCount).toBe(2);
    expect(await screen.findByText('14 đoạn')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Hỏi AI ngay/i })).toHaveAttribute('href', '/chat');
    expect(screen.queryByText(/Đang theo dõi/i)).not.toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(statusCallCount).toBe(2);

    const searchInput = screen.getByPlaceholderText('Tìm theo tên tài liệu...');
    await user.type(searchInput, 'chinh sach');
    expect(screen.getByText('Chính sách nghỉ phép.pdf')).toBeInTheDocument();
    expect(screen.queryByText('Tài liệu lỗi.docx')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Xóa từ khóa tìm kiếm' }));
    await user.selectOptions(screen.getByLabelText('Lọc theo trạng thái'), 'FAILED');
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('status=FAILED'),
        expect.anything(),
      );
    });
  });

  it('uses list polling fallback when > 5 documents are pending/processing', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const sixPending = Array.from({ length: 6 }, (_, index) =>
      buildDoc({
        id: `doc-p-${index}`,
        originalName: `Batch-${index}.pdf`,
        status: 'PENDING',
        chunkCount: 0,
      }),
    );

    let listCallCount = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/status')) {
        throw new Error('Should not call per-doc /status when > 5 docs are pending');
      }
      listCallCount += 1;
      const items =
        listCallCount === 1
          ? sixPending
          : sixPending.map((doc) => ({ ...doc, status: 'COMPLETED' as const, chunkCount: 5 }));
      return new Response(
        JSON.stringify({ items, page: 0, size: 100, totalElements: 6, totalPages: 1 }),
        { status: 200 },
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<DocumentsPage />);

    expect(await screen.findByText('Batch-0.pdf')).toBeInTheDocument();
    expect(screen.getByText(/Đang theo dõi 6 tài liệu/i)).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2550);
    });

    expect(listCallCount).toBe(2);
    expect(screen.queryByText(/Đang theo dõi/i)).not.toBeInTheDocument();
  });

  it('continues >5 list polling when a concurrent refresh increments mutationSeqRef without changing inFlightKey', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const sixPending = Array.from({ length: 6 }, (_, index) =>
      buildDoc({
        id: `doc-p-${index}`,
        originalName: `Batch-${index}.pdf`,
        status: 'PENDING',
        chunkCount: 0,
      }),
    );

    let resolveInFlightPoll: ((response: Response) => void) | null = null;
    let listCallCount = 0;
    const fetchMock = vi.fn(async () => {
      listCallCount += 1;
      if (listCallCount === 1) {
        return new Response(
          JSON.stringify({ items: sixPending, page: 0, size: 100, totalElements: 6, totalPages: 1 }),
          { status: 200 },
        );
      }
      if (listCallCount === 2) {
        return new Promise<Response>((resolve) => {
          resolveInFlightPoll = resolve;
        });
      }
      if (listCallCount === 3) {
        return new Response(
          JSON.stringify({ items: sixPending, page: 0, size: 100, totalElements: 6, totalPages: 1 }),
          { status: 200 },
        );
      }
      const completed = sixPending.map((doc) => ({
        ...doc,
        status: 'COMPLETED' as const,
        chunkCount: 5,
      }));
      return new Response(
        JSON.stringify({ items: completed, page: 0, size: 100, totalElements: 6, totalPages: 1 }),
        { status: 200 },
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<DocumentsPage />);

    expect(await screen.findByText('Batch-0.pdf')).toBeInTheDocument();
    expect(screen.getByText(/Đang theo dõi 6 tài liệu/i)).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2550);
    });
    expect(listCallCount).toBe(2);
    expect(resolveInFlightPoll).not.toBeNull();

    await user.click(screen.getByRole('button', { name: /Làm mới/i }));
    await waitFor(() => {
      expect(listCallCount).toBe(3);
    });

    const staleItems = sixPending.map((doc, idx) =>
      idx === 0 ? { ...doc, originalName: 'Stale-Poll-Snapshot.pdf' } : doc,
    );
    await act(async () => {
      resolveInFlightPoll?.(
        new Response(
          JSON.stringify({ items: staleItems, page: 0, size: 100, totalElements: 6, totalPages: 1 }),
          { status: 200 },
        ),
      );
      await Promise.resolve();
    });
    expect(screen.queryByText('Stale-Poll-Snapshot.pdf')).not.toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2550);
    });
    expect(listCallCount).toBe(4);
    await waitFor(() => {
      expect(screen.queryByText(/Đang theo dõi/i)).not.toBeInTheDocument();
    });
  });

  it('enforces RBAC row guards, blocks unsafe preview URLs, resumes polling on Replace v1->v2, isolates Replace close/reopen races, validates Edit extension, and deletes cleanly', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const ownDoc = buildDoc({
      id: 'doc-own',
      departmentId: 'dept-it',
      originalName: 'Sổ tay kỹ thuật.pdf',
      accessLevel: 'DEPARTMENT',
      currentVersion: 1,
    });
    const otherDeptPublicDoc = buildDoc({
      id: 'doc-public-hr',
      departmentId: 'dept-hr',
      originalName: 'Quy chế công ty.pdf',
      accessLevel: 'PUBLIC',
      currentVersion: 3,
    });

    const openSpy = vi.fn(() => ({} as Window));
    vi.stubGlobal('open', openSpy);

    let previewMode: 'safe' | 'unsafe' = 'unsafe';
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/api/v1/documents/doc-own/preview-url')) {
        const previewUrl =
          previewMode === 'unsafe'
            ? '/\\evil.com'
            : 'http://localhost:9000/signed/doc-own.pdf';
        return new Response(
          JSON.stringify({
            documentId: 'doc-own',
            originalName: 'Sổ tay kỹ thuật.pdf',
            mimeType: 'application/pdf',
            fileExtension: 'pdf',
            currentVersion: 1,
            pageNumber: null,
            previewUrl,
            expiresAt: '2026-11-27T11:00:00Z',
          }),
          { status: 200 },
        );
      }
      if (url.endsWith('/api/v1/documents/doc-own/status')) {
        return new Response(
          JSON.stringify({
            id: 'doc-own',
            status: 'COMPLETED',
            chunkCount: 19,
            currentVersion: 2,
            errorMessage: null,
            updatedAt: '2026-11-27T09:10:00Z',
          }),
          { status: 200 },
        );
      }
      if (url.endsWith('/api/v1/documents/doc-own') && init?.method === 'PATCH') {
        expect(JSON.parse(String(init.body))).toEqual({
          originalName: 'Sổ tay kỹ thuật mới.pdf',
          accessLevel: 'PUBLIC',
        });
        return new Response(
          JSON.stringify({
            ...ownDoc,
            currentVersion: 2,
            chunkCount: 19,
            originalName: 'Sổ tay kỹ thuật mới.pdf',
            accessLevel: 'PUBLIC',
          }),
          { status: 200 },
        );
      }
      if (url.endsWith('/api/v1/documents/doc-own') && init?.method === 'DELETE') {
        return new Response(null, { status: 204 });
      }
      return new Response(
        JSON.stringify({
          items: [ownDoc, otherDeptPublicDoc],
          page: 0,
          size: 100,
          totalElements: 2,
          totalPages: 1,
        }),
        { status: 200 },
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const xhrCalls = installMockXhr(() => {});

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<DocumentsPage />);

    expect(await screen.findByText('Sổ tay kỹ thuật.pdf')).toBeInTheDocument();
    expect(screen.getByText('Quy chế công ty.pdf')).toBeInTheDocument();

    const readOnlyRow = screen.getByText('Quy chế công ty.pdf').closest('tr')!;
    expect(within(readOnlyRow).getByText('Chỉ đọc')).toBeInTheDocument();
    expect(
      within(readOnlyRow).queryByRole('button', { name: /Cập nhật phiên bản mới/i }),
    ).not.toBeInTheDocument();
    expect(
      within(readOnlyRow).queryByRole('button', { name: /Xóa Quy chế công ty\.pdf/i }),
    ).not.toBeInTheDocument();
    expect(
      within(readOnlyRow).getByRole('button', { name: 'Xem trước Quy chế công ty.pdf' }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Xem trước Sổ tay kỹ thuật.pdf' }));
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/doc-own/preview-url'),
        expect.anything(),
      );
    });
    expect(openSpy).not.toHaveBeenCalled();

    previewMode = 'safe';
    await user.click(screen.getByRole('button', { name: 'Xem trước Sổ tay kỹ thuật.pdf' }));
    await waitFor(() => {
      expect(openSpy).toHaveBeenCalledWith(
        'http://localhost:9000/signed/doc-own.pdf',
        '_blank',
        'noopener,noreferrer',
      );
    });

    await user.click(
      screen.getByRole('button', { name: 'Cập nhật phiên bản mới cho Sổ tay kỹ thuật.pdf' }),
    );
    let dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Chọn tệp mới/i), {
      target: { files: [new File(['v2-abort'], 'abort.pdf', { type: 'application/pdf' })] },
    });
    await user.click(within(dialog).getByRole('button', { name: 'Cập nhật phiên bản' }));
    await waitFor(() => {
      expect(xhrCalls).toHaveLength(1);
    });

    await user.click(within(dialog).getByRole('button', { name: 'Hủy tải lên' }));
    expect(xhrCalls[0].aborted).toBe(true);

    await user.click(
      screen.getByRole('button', { name: 'Cập nhật phiên bản mới cho Sổ tay kỹ thuật.pdf' }),
    );
    dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Chọn tệp mới/i), {
      target: { files: [new File(['v2'], 'manual-v2.pdf', { type: 'application/pdf' })] },
    });
    await user.click(within(dialog).getByRole('button', { name: 'Cập nhật phiên bản' }));

    await waitFor(() => {
      expect(xhrCalls).toHaveLength(2);
      expect(xhrCalls[1].method).toBe('PUT');
    });

    act(() => {
      xhrCalls[1].emitProgress(100, 100);
      xhrCalls[1].respond(202, {
        ...ownDoc,
        currentVersion: 2,
        status: 'PENDING',
        chunkCount: 0,
      });
    });

    expect(await screen.findByText('v2')).toBeInTheDocument();
    expect(screen.getByText(/Đang theo dõi 1 tài liệu/i)).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2550);
    });
    expect(await screen.findByText('19 đoạn')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Sửa thông tin Sổ tay kỹ thuật.pdf' }));
    const editDialog = await screen.findByRole('dialog');
    const nameInput = within(editDialog).getByLabelText('Tên tài liệu');
    await user.clear(nameInput);
    await user.type(nameInput, 'Thiếu đuôi');
    await user.click(within(editDialog).getByRole('button', { name: 'Lưu thay đổi' }));
    expect(
      await within(editDialog).findByText(/phải giữ nguyên phần mở rộng \.pdf/i),
    ).toBeInTheDocument();

    await user.clear(nameInput);
    await user.type(nameInput, 'Sổ tay kỹ thuật mới.pdf');
    await user.selectOptions(within(editDialog).getByLabelText('Mức quyền truy cập'), 'PUBLIC');
    await user.click(within(editDialog).getByRole('button', { name: 'Lưu thay đổi' }));

    expect(await screen.findByText('Sổ tay kỹ thuật mới.pdf')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Xóa Sổ tay kỹ thuật mới.pdf' }));
    const deleteDialog = await screen.findByRole('dialog');
    expect(within(deleteDialog).getByText(/không thể khôi phục/i)).toBeInTheDocument();
    await user.click(within(deleteDialog).getByRole('button', { name: 'Xóa vĩnh viễn' }));

    await waitFor(() => {
      expect(screen.queryByText('Sổ tay kỹ thuật mới.pdf')).not.toBeInTheDocument();
    });
  });

  it('keeps fallback department option in EditDocumentDialog for SYSTEM_ADMIN when department lookup fails', async () => {
    mockUser = buildUser('SYSTEM_ADMIN', null);
    const adminDoc = buildDoc({
      id: 'doc-admin-1',
      departmentId: 'dept-legacy-uuid',
      originalName: 'Bao cao.pdf',
    });

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('/api/v1/admin/departments')) {
          return new Response(JSON.stringify({ status: 500, detail: 'Unavailable' }), {
            status: 500,
          });
        }
        return new Response(
          JSON.stringify({
            items: [adminDoc],
            page: 0,
            size: 100,
            totalElements: 1,
            totalPages: 1,
          }),
          { status: 200 },
        );
      }),
    );

    const user = userEvent.setup();
    render(<DocumentsPage />);

    expect(await screen.findByText('Bao cao.pdf')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Sửa thông tin Bao cao.pdf' }));
    const editDialog = await screen.findByRole('dialog');
    const deptSelect = within(editDialog).getByLabelText('Phòng ban sở hữu') as HTMLSelectElement;
    expect(deptSelect).toBeDisabled();
    expect(deptSelect.value).toBe('dept-legacy-uuid');
  });
});
