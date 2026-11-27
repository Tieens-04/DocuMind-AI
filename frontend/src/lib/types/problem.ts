export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errorCode?: string;
  retryAfterSeconds?: number;
}

export class ApiError extends Error {
  readonly status: number;
  readonly errorCode: string;
  readonly title: string;
  readonly detail: string;
  readonly retryAfterSeconds?: number;

  constructor(params: {
    status: number;
    errorCode?: string;
    title?: string;
    detail?: string;
    retryAfterSeconds?: number;
  }) {
    const detail = params.detail || params.title || `Request failed with status ${params.status}`;
    super(detail);
    this.name = 'ApiError';
    this.status = params.status;
    this.errorCode = params.errorCode || 'REQUEST_ERROR';
    this.title = params.title || 'Request Failed';
    this.detail = detail;
    this.retryAfterSeconds = params.retryAfterSeconds;
  }
}

export function formatApiErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.errorCode) {
      case 'INVALID_CREDENTIALS':
        return 'Email hoặc mật khẩu không chính xác.';
      case 'ACCOUNT_DISABLED':
        return 'Tài khoản của bạn đã bị vô hiệu hóa. Vui lòng liên hệ quản trị viên.';
      case 'AUTH_STATE_STORE_UNAVAILABLE':
        return 'Dịch vụ xác thực tạm thời gián đoạn. Vui lòng thử lại sau.';
      case 'RATE_LIMIT_EXCEEDED':
        return error.retryAfterSeconds
          ? `Bạn thao tác quá nhanh. Vui lòng thử lại sau ${error.retryAfterSeconds} giây.`
          : 'Bạn thao tác quá nhanh. Vui lòng thử lại sau ít phút.';
      case 'ACCESS_DENIED':
      case 'DOCUMENT_ACCESS_DENIED':
        return 'Bạn không có quyền thực hiện thao tác này.';
      case 'DUPLICATE_EMAIL':
        return 'Email này đã được sử dụng cho một tài khoản khác.';
      case 'DUPLICATE_DEPARTMENT_CODE':
        return 'Mã phòng ban này đã tồn tại trong hệ thống.';
      case 'DEPARTMENT_IN_USE':
        return 'Không thể xóa phòng ban đang có nhân sự hoặc tài liệu trực thuộc.';
      case 'ADMIN_SELF_MODIFICATION_FORBIDDEN':
        return 'Quản trị viên không thể tự vô hiệu hóa hoặc tự hạ quyền tài khoản của chính mình.';
      case 'LAST_ACTIVE_ADMIN':
        return 'Không thể khóa hoặc hạ quyền tài khoản SYSTEM_ADMIN đang hoạt động cuối cùng.';
      case 'REFRESH_TOKEN_REUSED':
      case 'TOKEN_REVOKED':
      case 'TOKEN_EXPIRED':
      case 'INVALID_TOKEN':
      case 'AUTHENTICATION_REQUIRED':
        return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
      default:
        return error.detail;
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return 'Không thể kết nối tới máy chủ DocuMind AI.';
}

export function parseProblemDetailPayload(
  status: number,
  bodyText: string,
  retryAfterHeader: string | null,
): ApiError {
  let payload: ProblemDetail | null = null;
  try {
    if (bodyText) {
      payload = JSON.parse(bodyText) as ProblemDetail;
    }
  } catch {
    payload = null;
  }

  const retryAfterSeconds =
    payload?.retryAfterSeconds ??
    (retryAfterHeader && /^\d+$/.test(retryAfterHeader.trim())
      ? Number(retryAfterHeader.trim())
      : undefined);

  return new ApiError({
    status,
    errorCode: payload?.errorCode,
    title: payload?.title,
    detail: payload?.detail,
    retryAfterSeconds,
  });
}

export async function parseApiErrorResponse(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => '');
  return parseProblemDetailPayload(
    response.status,
    text,
    response.headers.get('Retry-After'),
  );
}
