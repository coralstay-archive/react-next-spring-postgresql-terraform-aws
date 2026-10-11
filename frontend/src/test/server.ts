import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

/** 테스트 공용 MSW 서버. 수명 주기는 vitest.setup.ts 가 관리하고, 테스트마다 server.use(...)로 핸들러를 더한다. */
export const server = setupServer();

/** 브라우저가 Set-Cookie 를 반영한 것처럼 jsdom 쿠키를 바꾼다(MSW 응답의 Set-Cookie 는 document.cookie 에 반영되지 않는다). */
export function setCookie(name: string, value: string) {
  document.cookie = `${name}=${encodeURIComponent(value)}; path=/`;
}

export function clearCookies() {
  for (const part of document.cookie.split(";")) {
    const name = part.split("=")[0]?.trim();
    if (name) document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`;
  }
}

/** GET /api/auth/csrf: XSRF-TOKEN 쿠키를 준다. */
export function csrfHandler(token = "csrf-token", onCall?: () => void) {
  return http.get("*/api/auth/csrf", () => {
    onCall?.();
    setCookie("XSRF-TOKEN", token);
    return HttpResponse.json({ headerName: "X-XSRF-TOKEN", cookieName: "XSRF-TOKEN" });
  });
}

export function apiError(status: number, error: string, message = error, extra: Record<string, unknown> = {}) {
  return HttpResponse.json({ status, error, message, ...extra }, { status });
}
