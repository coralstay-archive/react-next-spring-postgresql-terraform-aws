import { vi } from "vitest";

/**
 * S3 직접 업로드(XHR PUT) 대역(TASK-31#5). MSW 는 업로드 진행 이벤트를 마음대로 낼 수 없어서, 진행·응답·abort 를 테스트가 직접 다룬다.
 * presign·confirm 은 fetch 라 그대로 MSW 가 받는다. `vi.unstubAllGlobals()` 로 되돌린다.
 */
export class FakeXhr {
  static instances: FakeXhr[] = [];

  method = "";
  url = "";
  headers: Record<string, string> = {};
  body: unknown = undefined;
  status = 0;
  aborted = false;
  upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(name: string, value: string) {
    this.headers[name.toLowerCase()] = value;
  }

  send(body: unknown) {
    this.body = body;
    FakeXhr.instances.push(this);
  }

  abort() {
    if (this.aborted) return;
    this.aborted = true;
    this.onabort?.();
  }

  /** 업로드 진행 이벤트를 낸다. */
  progress(loaded: number, total: number) {
    this.upload.onprogress?.({ lengthComputable: true, loaded, total } as ProgressEvent);
  }

  /** S3 응답. */
  respond(status: number) {
    this.status = status;
    this.onload?.();
  }

  /** 네트워크 오류. */
  fail() {
    this.onerror?.();
  }
}

export function installFakeXhr(): typeof FakeXhr {
  FakeXhr.instances = [];
  vi.stubGlobal("XMLHttpRequest", FakeXhr);
  return FakeXhr;
}
