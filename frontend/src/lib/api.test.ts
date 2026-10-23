import { QueryClient } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { api, ApiError, readCookie } from "@/lib/api";
import { ERROR_MESSAGES, errorMessage } from "@/lib/errors";
import { createQueryClient, queryKeys } from "@/lib/query";
import { csrfHandler, server, setCookie } from "@/test/server";

// TASK-13#1 fetch 래퍼(XSRF 헤더, 오류 변환, 401 처리) + TanStack Query.
describe("TASK-13#1 api(): 쿠키 세션 + CSRF", () => {
  it("TASK-13#1 GET 은 credentials:'include' 로 보내고 CSRF 토큰을 받으러 가지 않는다", async () => {
    let csrfCalls = 0;
    let credentials: RequestCredentials | undefined;
    server.use(
      http.get("*/api/auth/csrf", () => {
        csrfCalls += 1;
        return HttpResponse.json({});
      }),
      http.get("*/api/posts", ({ request }) => {
        credentials = request.credentials;
        return HttpResponse.json({ items: [] });
      }),
    );

    await expect(api("/posts")).resolves.toEqual({ items: [] });
    expect(credentials).toBe("include");
    expect(csrfCalls).toBe(0);
  });

  it("TASK-13#1 XSRF-TOKEN 쿠키가 없으면 /api/auth/csrf 로 먼저 받고 X-XSRF-TOKEN 헤더를 붙인다", async () => {
    const calls: string[] = [];
    let header: string | null = null;
    server.use(
      csrfHandler("token-1", () => calls.push("csrf")),
      http.post("*/api/auth/logout", ({ request }) => {
        calls.push("logout");
        header = request.headers.get("X-XSRF-TOKEN");
        return new HttpResponse(null, { status: 204 });
      }),
    );

    expect(readCookie("XSRF-TOKEN")).toBeNull();
    await expect(api("/auth/logout", { method: "POST" })).resolves.toBeUndefined();
    expect(calls).toEqual(["csrf", "logout"]);
    expect(header).toBe("token-1");
  });

  it("TASK-13#1 쿠키가 있으면 그 값을 쓰고, 응답이 토큰을 회전하면 다음 요청은 새 값을 읽는다", async () => {
    setCookie("XSRF-TOKEN", "before-login");
    const headers: (string | null)[] = [];
    let csrfCalls = 0;
    server.use(
      http.get("*/api/auth/csrf", () => {
        csrfCalls += 1;
        return HttpResponse.json({});
      }),
      http.post("*/api/auth/login", ({ request }) => {
        headers.push(request.headers.get("X-XSRF-TOKEN"));
        // 로그인 응답의 Set-Cookie: XSRF-TOKEN=after-login (브라우저가 쿠키를 바꾼다)
        setCookie("XSRF-TOKEN", "after-login");
        return HttpResponse.json({ id: 1 });
      }),
      http.post("*/api/posts", ({ request }) => {
        headers.push(request.headers.get("X-XSRF-TOKEN"));
        return HttpResponse.json({ id: 10 }, { status: 201 });
      }),
    );

    await api("/auth/login", { method: "POST", body: { email: "a@b.c", password: "x" } });
    await api("/posts", { method: "POST", body: { title: "t" } });
    expect(headers).toEqual(["before-login", "after-login"]);
    expect(csrfCalls).toBe(0);
  });

  it("TASK-13#1 JSON 본문을 보내면 Content-Type 을 붙이고, 쿼리 파라미터를 붙인다", async () => {
    setCookie("XSRF-TOKEN", "t");
    let contentType: string | null = null;
    let body: unknown;
    let url = "";
    server.use(
      http.post("*/api/auth/phone/verify", async ({ request }) => {
        contentType = request.headers.get("Content-Type");
        body = await request.json();
        url = request.url;
        return HttpResponse.json({ id: 1 });
      }),
    );

    await api("/auth/phone/verify", {
      method: "POST",
      query: { link: true },
      body: { challengeId: "c", code: "123456" },
    });
    expect(contentType).toBe("application/json");
    expect(body).toEqual({ challengeId: "c", code: "123456" });
    expect(new URL(url).searchParams.get("link")).toBe("true");
  });

  it("TASK-13#1 403 csrf 면 토큰을 다시 받아 한 번만 재시도한다", async () => {
    setCookie("XSRF-TOKEN", "stale");
    const seen: (string | null)[] = [];
    server.use(
      csrfHandler("fresh"),
      http.delete("*/api/me/identities/3", ({ request }) => {
        const token = request.headers.get("X-XSRF-TOKEN");
        seen.push(token);
        if (token !== "fresh") {
          return HttpResponse.json(
            { status: 403, error: "csrf", message: "CSRF 토큰이 없거나 올바르지 않습니다" },
            { status: 403 },
          );
        }
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(api("/me/identities/3", { method: "DELETE" })).resolves.toBeUndefined();
    expect(seen).toEqual(["stale", "fresh"]);
  });
});

describe("TASK-13#1 ApiError: 오류 JSON 변환", () => {
  it("TASK-13#1 {status,error,message,fields} 를 ApiError(code, fields)로 바꾼다", async () => {
    setCookie("XSRF-TOKEN", "t");
    server.use(
      http.post("*/api/auth/signup", () =>
        HttpResponse.json(
          {
            status: 400,
            error: "validation",
            message: "입력값이 올바르지 않습니다",
            fields: { nickname: "닉네임은 2~20자입니다" },
          },
          { status: 400 },
        ),
      ),
    );

    const error = await api("/auth/signup", { method: "POST", body: {} }).catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 400,
      code: "validation",
      message: "입력값이 올바르지 않습니다",
      fields: { nickname: "닉네임은 2~20자입니다" },
    });
  });

  it("TASK-13#1 429 는 Retry-After(초)를 retryAfter 로 담는다", async () => {
    setCookie("XSRF-TOKEN", "t");
    server.use(
      http.post("*/api/auth/phone/otp", () =>
        HttpResponse.json(
          { status: 429, error: "rate_limited", message: "잠시 후 다시 시도하세요" },
          { status: 429, headers: { "Retry-After": "42" } },
        ),
      ),
    );

    const error = await api("/auth/phone/otp", { method: "POST", body: { phone: "010" } }).catch((e) => e);
    expect(error).toMatchObject({ status: 429, code: "rate_limited", retryAfter: 42 });
  });

  it("TASK-13#1 403 step_up_required 는 isStepUpRequired 로 구분된다", async () => {
    setCookie("XSRF-TOKEN", "t");
    server.use(
      http.post("*/api/posts", () =>
        HttpResponse.json(
          { status: 403, error: "step_up_required", message: "다시 인증하세요" },
          { status: 403 },
        ),
      ),
    );

    const error = (await api("/posts", { method: "POST", body: {} }).catch((e) => e)) as ApiError;
    expect(error.isStepUpRequired).toBe(true);
    expect(error.isUnauthorized).toBe(false);
  });

  it("TASK-13#1 JSON 이 아닌 오류 응답과 네트워크 오류도 ApiError 다", async () => {
    server.use(
      http.get("*/api/broken", () => new HttpResponse("<html>bad gateway</html>", { status: 502 })),
      http.get("*/api/offline", () => HttpResponse.error()),
    );

    await expect(api("/broken")).rejects.toMatchObject({ status: 502, code: "http_502" });
    await expect(api("/offline")).rejects.toMatchObject({ status: 0, code: "network" });
  });
});

describe("TASK-13#1 TanStack Query: 401 처리와 재시도 규칙", () => {
  it("TASK-13#1 쿼리가 401 이면 me 캐시를 null(로그아웃 상태)로 바꾼다", async () => {
    server.use(
      http.get("*/api/me/identities", () =>
        HttpResponse.json({ status: 401, error: "unauthorized", message: "인증이 필요합니다" }, { status: 401 }),
      ),
    );
    const client: QueryClient = createQueryClient();
    client.setQueryData(queryKeys.me, { id: 1, nickname: "n" });

    await client
      .fetchQuery({ queryKey: queryKeys.identities, queryFn: () => api("/me/identities") })
      .catch(() => undefined);
    expect(client.getQueryData(queryKeys.me)).toBeNull();
  });

  it("TASK-13#1 401 invalid_credentials(틀린 비밀번호)는 로그인 상태를 지우지 않는다", async () => {
    setCookie("XSRF-TOKEN", "t");
    server.use(
      http.post("*/api/auth/step-up/local", () =>
        HttpResponse.json(
          { status: 401, error: "invalid_credentials", message: "이메일 또는 비밀번호가 올바르지 않습니다" },
          { status: 401 },
        ),
      ),
    );
    const client = createQueryClient();
    const me = { id: 1, nickname: "n" };
    client.setQueryData(queryKeys.me, me);

    await client
      .getMutationCache()
      .build(client, {
        mutationFn: () => api("/auth/step-up/local", { method: "POST", body: { password: "x" } }),
      })
      .execute(undefined)
      .catch(() => undefined);
    expect(client.getQueryData(queryKeys.me)).toEqual(me);
  });

  it("TASK-13#1 4xx 는 재시도하지 않고 네트워크·5xx 는 재시도한다", () => {
    const client = createQueryClient();
    const retry = client.getDefaultOptions().queries?.retry as (count: number, error: unknown) => boolean;
    expect(retry(0, new ApiError(404, "post_not_found", "없음"))).toBe(false);
    expect(retry(0, new ApiError(401, "unauthorized", "인증"))).toBe(false);
    expect(retry(0, new ApiError(503, "http_503", "x"))).toBe(true);
    expect(retry(0, new ApiError(0, "network", "x"))).toBe(true);
    expect(retry(2, new ApiError(0, "network", "x"))).toBe(false);
  });
});

// TASK-28#3 FE-3: JSON 을 기대한 2xx 인데 본문이 JSON 이 아니면(CloudFront SPA 폴백의 index.html 등) 데이터로 넘기지 않는다.
describe("TASK-28#3 2xx 비JSON 응답", () => {
  it("TASK-28#3 200 HTML 본문은 ApiError bad_response(한국어 문장)", async () => {
    server.use(
      http.get("*/api/posts", () =>
        HttpResponse.html("<!doctype html><html><body>index</body></html>", { status: 200 }),
      ),
    );

    const error = (await api("/posts").catch((e) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 200, code: "bad_response" });
    expect(error.message).toMatch(/[가-힣]/);
    expect(errorMessage(error)).toBe(ERROR_MESSAGES.bad_response);
    expect(errorMessage(error)).not.toContain("bad_response");
  });

  it("TASK-28#3 CSRF 재시도 뒤의 2xx 비JSON 도 bad_response", async () => {
    setCookie("XSRF-TOKEN", "old");
    let calls = 0;
    server.use(
      csrfHandler("new"),
      http.post("*/api/posts", () => {
        calls += 1;
        if (calls === 1) return HttpResponse.json({ status: 403, error: "csrf", message: "x" }, { status: 403 });
        return HttpResponse.text("OK", { status: 201 });
      }),
    );

    await expect(api("/posts", { method: "POST", body: {} })).rejects.toMatchObject({ code: "bad_response" });
  });

  it("TASK-28#3 204·빈 본문은 undefined, JSON 은 그대로", async () => {
    server.use(
      http.delete("*/api/a", () => new HttpResponse(null, { status: 204 })),
      http.get("*/api/b", () => new HttpResponse("", { status: 200 })),
      http.get("*/api/c", () => HttpResponse.json([1, 2])),
      http.get("*/api/d", () => HttpResponse.json(null)),
    );
    setCookie("XSRF-TOKEN", "t");

    await expect(api("/a", { method: "DELETE" })).resolves.toBeUndefined();
    await expect(api("/b")).resolves.toBeUndefined();
    await expect(api("/c")).resolves.toEqual([1, 2]);
    await expect(api("/d")).resolves.toBeNull();
  });
});
