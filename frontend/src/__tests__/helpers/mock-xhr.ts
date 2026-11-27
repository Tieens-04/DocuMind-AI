import { vi } from 'vitest';

export interface MockXhrRecord {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: FormData | null;
  fileName: string;
  status: number;
  responseText: string;
  responseHeaders: Record<string, string>;
  aborted: boolean;
  abort: () => void;
  emitProgress: (loaded: number, total: number) => void;
  respond: (status: number, body: unknown, headers?: Record<string, string>) => void;
  failNetwork: () => void;
}

export function installMockXhr(onSend: (record: MockXhrRecord) => void): MockXhrRecord[] {
  const records: MockXhrRecord[] = [];

  class FakeXmlHttpRequest {
    method = '';
    url = '';
    headers: Record<string, string> = {};
    body: FormData | null = null;
    status = 0;
    responseText = '';
    responseHeaders: Record<string, string> = {};
    aborted = false;
    upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null };
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    onabort: (() => void) | null = null;

    open(method: string, url: string) {
      this.method = method;
      this.url = url;
    }

    setRequestHeader(key: string, value: string) {
      this.headers[key] = value;
    }

    getResponseHeader(key: string): string | null {
      const lower = key.toLowerCase();
      for (const [k, v] of Object.entries(this.responseHeaders)) {
        if (k.toLowerCase() === lower) {
          return v;
        }
      }
      return null;
    }

    send(body: FormData) {
      this.body = body;
      const file = body.get('file') as File | null;
      // eslint-disable-next-line @typescript-eslint/no-this-alias
      const fake = this;
      const record: MockXhrRecord = {
        method: this.method,
        url: this.url,
        headers: this.headers,
        body: this.body,
        fileName: file?.name ?? '',
        get status() {
          return fake.status;
        },
        get responseText() {
          return fake.responseText;
        },
        responseHeaders: this.responseHeaders,
        get aborted() {
          return fake.aborted;
        },
        abort: () => fake.abort(),
        emitProgress: (loaded: number, total: number) => {
          fake.upload.onprogress?.({
            lengthComputable: true,
            loaded,
            total,
          } as ProgressEvent);
        },
        respond: (status: number, payload: unknown, headers: Record<string, string> = {}) => {
          fake.status = status;
          fake.responseHeaders = headers;
          fake.responseText = typeof payload === 'string' ? payload : JSON.stringify(payload);
          fake.onload?.();
        },
        failNetwork: () => {
          fake.onerror?.();
        },
      };
      records.push(record);
      onSend(record);
    }

    abort() {
      this.aborted = true;
      this.onabort?.();
    }
  }

  vi.stubGlobal('XMLHttpRequest', FakeXmlHttpRequest as unknown as typeof XMLHttpRequest);
  return records;
}
