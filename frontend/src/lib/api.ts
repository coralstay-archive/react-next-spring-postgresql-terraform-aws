/**
 * 백엔드 API fetch 래퍼(decision-5: 브라우저는 SESSION 쿠키 + CSRF).
 *
 * - 모든 요청은 같은 오리진의 `/api` 아래로 간다(운영은 CloudFront `/api/*`, 개발은 next dev rewrite).
 * - `credentials: 'include'` 로 SESSION 쿠키를 보낸다.
 * - 상태를 바꾸는 메서드(POST/PUT/PATCH/DELETE)는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보낸다. 쿠키가 없으면
 *   `GET /api/auth/csrf` 로 먼저 받는다. 토큰은 로그인·OTP 검증·step-up·OAuth 콜백 뒤 회전하고 로그아웃 뒤 지워지므로
 *   **요청마다 쿠키를 다시 읽는다**(캐시하지 않는다). 그래도 403 `csrf` 가 오면 토큰을 다시 받아 한 번만 재시도한다.
 * - CSRF 토큰을 받은 뒤에도 쿠키가 없으면(쿠키 차단) 보내지 않고 `cookies_blocked`(TASK-30#2).
 * - 오류 응답 `{status, error, message, fields?}` 는 {@link ApiError} 로 바꾼다. 429 는 `Retry-After`(초)를 담는다.
 *   `error` 가 계약 형식(`^[a-z0-9_]+$`)이 아니거나 본문이 JSON 이 아니면(프록시 HTML, Spring 기본 오류) 코드는 `http_<상태>`,
 *   문장은 상태 범위별 한국어다 — 영어 `statusText`·HTML 원문을 화면에 올리지 않는다(TASK-28#2).
 */

export const API_BASE = "/api";

export const CSRF_COOKIE = "XSRF-TOKEN";
export const CSRF_HEADER = "X-XSRF-TOKEN";

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** 백엔드 오류 코드 형식(snake_case). 이 형식이 아니면 코드로 쓰지 않는다. */
export const ERROR_CODE_PATTERN = /^[a-z0-9_]+$/;

/** 코드로 문장을 정하지 못할 때 쓰는 상태 범위별 한국어(TASK-28#2). */
export function statusMessage(status: number): string {
  if (status === 0) return "서버에 연결할 수 없습니다. 네트워크를 확인하세요.";
  if (status === 401) return "로그인이 필요합니다. 다시 로그인하세요.";
  if (status === 403) return "권한이 없습니다.";
  if (status === 404) return "요청한 내용을 찾을 수 없습니다.";
  if (status === 408) return "요청 시간이 초과되었습니다. 잠시 후 다시 시도하세요.";
  if (status === 409) return "다른 변경과 충돌했습니다. 페이지를 새로 고친 뒤 다시 시도하세요.";
  if (status === 413) return "보낸 내용이 너무 큽니다. 줄여서 다시 시도하세요.";
  if (status === 429) return "요청이 너무 많습니다. 잠시 후 다시 시도하세요.";
  if (status >= 400 && status < 500) return "요청을 처리하지 못했습니다. 입력을 확인한 뒤 다시 시도하세요.";
  if (status === 504) return "서버 응답 시간이 초과되었습니다. 잠시 후 다시 시도하세요.";
  if (status === 502 || status === 503) return "서버가 잠시 응답하지 않습니다. 잠시 후 다시 시도하세요.";
  if (status >= 500 && status < 600) return "서버에서 오류가 났습니다. 잠시 후 다시 시도하세요.";
  return "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
}

export class ApiError extends Error {
  readonly status: number;
  /**
   * 백엔드 `error` 코드(예: `email_taken`). 오류 본문이 JSON·계약 형식이 아니면 `http_<status>`, 네트워크 오류는 `network`,
   * 2xx 인데 본문이 JSON 이 아니면 `bad_response`, CSRF 쿠키가 저장되지 않으면 `cookies_blocked`.
   */
  readonly code: string;
  readonly fields?: Record<string, string>;
  /** 429 의 `Retry-After`(초). */
  readonly retryAfter?: number;

  constructor(
    status: number,
    code: string,
    message: string,
    options: { fields?: Record<string, string>; retryAfter?: number } = {},
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fields = options.fields;
    this.retryAfter = options.retryAfter;
  }

  get isUnauthorized() {
    return this.status === 401;
  }

  /** 로그인이 필요하다는 401(`unauthorized`). 틀린 비밀번호·인증번호(401 `invalid_*`)와 구분한다. */
  get isSessionExpired() {
    return this.status === 401 && this.code === "unauthorized";
  }

  /** 제한 세션(재활용 번호 보호)의 쓰기 거부. 기존 수단으로 다시 인증해야 한다. */
  get isStepUpRequired() {
    return this.status === 403 && this.code === "step_up_required";
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  for (const part of document.cookie.split(";")) {
    const [key, ...rest] = part.trim().split("=");
    if (key === name) {
      const value = rest.join("=");
      return value ? decodeURIComponent(value) : null;
    }
  }
  return null;
}

/** `/api` 아래 경로를 절대 URL 로(브라우저에서는 현재 오리진). */
export function apiUrl(path: string, query?: Query): string {
  const origin = typeof window === "undefined" ? "http://localhost" : window.location.origin;
  const url = new URL(`${API_BASE}${path.startsWith("/") ? path : `/${path}`}`, origin);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== "") url.searchParams.set(key, String(value));
    }
  }
  return url.toString();
}

/** 페이지 이동용(소셜 로그인 시작·연결 시작). fetch 가 아니라 location 으로 간다. */
export function apiPath(path: string): string {
  return `${API_BASE}${path.startsWith("/") ? path : `/${path}`}`;
}

type Query = Record<string, string | number | boolean | null | undefined>;

export interface ApiOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  query?: Query;
  signal?: AbortSignal;
}

let csrfInFlight: Promise<void> | null = null;

/** XSRF-TOKEN 쿠키를 받는다(동시에 여러 번 불려도 요청은 하나). */
export function fetchCsrfToken(): Promise<void> {
  if (!csrfInFlight) {
    csrfInFlight = send(apiUrl("/auth/csrf"), { method: "GET", credentials: "include" })
      .then(async (response) => {
        if (!response.ok) throw await toApiError(response);
      })
      .finally(() => {
        csrfInFlight = null;
      });
  }
  return csrfInFlight;
}

/** 쿠키가 막힌 브라우저(TASK-30#2, FE-7): CSRF 토큰을 받아도 쿠키가 저장되지 않는다. */
export const COOKIES_BLOCKED_MESSAGE =
  "브라우저가 쿠키를 막고 있어 요청을 보낼 수 없습니다. 이 사이트의 쿠키를 허용한 뒤 다시 시도하세요.";

/**
 * 쓰기 요청의 CSRF 토큰. 쿠키가 없으면 받고, 받은 뒤에도 없으면 쿠키가 막힌 것이다 — 토큰 없이 보내면 403 `csrf` 가 되고
 * 다시 받아도 또 없어 "보안 토큰이 만료" 안내만 되풀이되므로, 보내지 않고 ApiError 403 `cookies_blocked` 로 끝낸다.
 */
async function csrfToken(): Promise<string> {
  let token = readCookie(CSRF_COOKIE);
  if (!token) {
    await fetchCsrfToken();
    token = readCookie(CSRF_COOKIE);
  }
  if (!token) throw new ApiError(403, "cookies_blocked", COOKIES_BLOCKED_MESSAGE);
  return token;
}

export async function api<T = unknown>(path: string, options: ApiOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const unsafe = UNSAFE_METHODS.has(method);

  const attempt = async (): Promise<Response> => {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (unsafe) {
      headers[CSRF_HEADER] = await csrfToken();
    }
    return send(apiUrl(path, options.query), {
      method,
      headers,
      credentials: "include",
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  };

  let response = await attempt();
  if (!response.ok) {
    let error = await toApiError(response);
    if (unsafe && error.status === 403 && error.code === "csrf") {
      await fetchCsrfToken();
      response = await attempt();
      if (response.ok) return parseBody<T>(response);
      error = await toApiError(response);
    }
    throw error;
  }
  return parseBody<T>(response);
}

async function send(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
    throw new ApiError(0, "network", statusMessage(0));
  }
}

/**
 * 2xx 본문. 204·빈 본문은 undefined. JSON 이 아니면(CloudFront SPA 폴백의 index.html, 프록시 문구 등) 텍스트를 데이터로
 * 넘기지 않고 ApiError `bad_response` 로 실패시킨다(TASK-28#3) — 화면이 문자열을 객체로 쓰다 TypeError 가 나지 않게.
 */
async function parseBody<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(response.status, "bad_response", BAD_RESPONSE_MESSAGE);
  }
}

const BAD_RESPONSE_MESSAGE = "서버 응답을 해석하지 못했습니다. 잠시 후 다시 시도하세요.";

async function toApiError(response: Response): Promise<ApiError> {
  const retryHeader = response.headers.get("Retry-After");
  const retryAfter = retryHeader && /^\d+$/.test(retryHeader.trim()) ? Number(retryHeader.trim()) : undefined;
  let body: { error?: unknown; message?: unknown; fields?: unknown } | null = null;
  try {
    const parsed: unknown = JSON.parse(await response.text());
    body = parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    body = null;
  }
  const serverCode = typeof body?.error === "string" && ERROR_CODE_PATTERN.test(body.error) ? body.error : null;
  const contract = serverCode !== null;
  const code = serverCode ?? `http_${response.status}`;
  const message =
    contract && typeof body?.message === "string" && body.message ? body.message : statusMessage(response.status);
  const fields =
    contract && body?.fields && typeof body.fields === "object" ? (body.fields as Record<string, string>) : undefined;
  return new ApiError(response.status, code, message, { fields, retryAfter });
}
