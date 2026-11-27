import type { AdminDepartmentResponse } from '@/lib/types/admin';

export interface ChatSessionResponse {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export const MAX_CHAT_QUESTION_CHARS = 4000;
export const SAFE_REFUSAL_MESSAGE = 'Không tìm thấy thông tin trong tài liệu nội bộ';
export const REDACTED_ASSISTANT_CONTENT =
  '[Nội dung câu trả lời đã được ẩn do quyền truy cập hoặc trạng thái tài liệu nguồn đã thay đổi]';

export type MessageRole = 'USER' | 'ASSISTANT';
export type CacheTier = 'NONE' | 'L1' | 'L2';

export interface CitationDto {
  documentId: string;
  docVersion: number | null;
  fileName: string;
  pageNumber: number | null;
  sectionHeading: string | null;
  similarityScore: number;
  excerpt: string;
  presignedUrl: string | null;
}

export interface ChatTokenUsageDto {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface ChatMessageResponse {
  id: string;
  sessionId: string;
  role: MessageRole;
  content: string;
  rewrittenQuery: string | null;
  citations: CitationDto[];
  abstained: boolean;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number | null;
  modelProvider: string | null;
  createdAt: string;
}

export interface ChatAskRequest {
  question: string;
  sessionId?: string | null;
  departmentId?: string | null;
}

export interface ChatStreamCitationsEvent {
  citations: CitationDto[];
  rewrittenQuery: string | null;
}

export interface ChatStreamTokenEvent {
  delta: string;
}

export interface ChatStreamDoneEvent {
  sessionId: string;
  messageId: string;
  abstained: boolean;
  modelProvider: string | null;
  cacheHit: boolean;
  cacheTier: CacheTier;
  tokenUsage: ChatTokenUsageDto;
  latencyMs: number;
}

export interface ChatStreamErrorEvent {
  errorCode: string;
  message: string;
  provider: string | null;
  retryable: boolean;
}

export interface DocumentPreviewUrlResponse {
  documentId: string;
  originalName: string;
  mimeType: string;
  fileExtension: string;
  currentVersion: number;
  pageNumber: number | null;
  previewUrl: string;
  expiresAt: string;
}

export type AdminDepartmentOption = AdminDepartmentResponse;

export type ChatMessageStatus = 'searching' | 'streaming' | 'completed' | 'stopped' | 'error';

export interface ChatUiMessage {
  id: string;
  role: MessageRole;
  content: string;
  rewrittenQuery?: string | null;
  citations: CitationDto[];
  abstained?: boolean;
  redacted?: boolean;
  cacheTier?: CacheTier;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  latencyMs?: number | null;
  status?: ChatMessageStatus;
  errorMessage?: string;
  retryable?: boolean;
  sourceQuestion?: string;
}
