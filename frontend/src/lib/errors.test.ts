import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { api, ApiError } from "@/lib/api";
import { ERROR_MESSAGES, errorMessage, OAUTH_LINK_MESSAGES, oauthErrorMessage } from "@/lib/errors";
import { server } from "@/test/server";

const HANGUL = /[가-힣]/;

async function failure(path: string): Promise<ApiError> {
  const error = await api(path).catch((e: unknown) => e);
  expect(error).toBeInstanceOf(ApiError);
  return error as ApiError;
}

/** 사용자에게 보이는 문장이 한국어이고 원문(영어·HTML·내부 코드)을 담지 않는지. */
function expectKoreanOnly(message: string, ...raw: string[]) {
  expect(message).toMatch(HANGUL);
  expect(message).not.toMatch(/<|>/);
  for (const r of raw) expect(message).not.toContain(r);
}

// TASK-28#2 FE-2: 비JSON·Spring 기본 오류는 상태 범위별 한국어 문구로, 코드는 ^[a-z0-9_]+$ 일 때만 쓴다.
// TASK-28#B2 사용자에게 영어 원문이나 내부 코드가 그대로 보이지 않는다.
describe("TASK-28#2 비JSON·Spring 기본 오류 → 상태 범위별 한국어", () => {
  it("TASK-28#2 TASK-28#B2 HTML 502(프록시 오류 페이지)는 http_502 이고 화면에는 한국어만", async () => {
    server.use(
      http.get("*/api/gw", () =>
        HttpResponse.text("<html><body>502 Bad Gateway</body></html>", { status: 502, statusText: "Bad Gateway" }),
      ),
    );
    const error = await failure("/gw");
    expect(error.code).toBe("http_502");
    expectKoreanOnly(error.message, "Bad Gateway", "http_502");
    expectKoreanOnly(errorMessage(error), "Bad Gateway", "http_502");
  });

  it("TASK-28#2 TASK-28#B2 Spring 기본 오류 본문({timestamp,status,error:'Internal Server Error',path})은 코드로 쓰지 않는다", async () => {
    server.use(
      http.get("*/api/spring", () =>
        HttpResponse.json(
          { timestamp: "2026-10-23T00:00:00Z", status: 500, error: "Internal Server Error", path: "/api/spring" },
          { status: 500, statusText: "Internal Server Error" },
        ),
      ),
    );
    const error = await failure("/spring");
    expect(error.code).toBe("http_500");
    expectKoreanOnly(errorMessage(error), "Internal Server Error", "http_500");
  });

  it.each([
    ["Bad-Code", 400],
    ["NotFound", 404],
    ["weird code", 409],
    ["코드", 400],
    ["", 403],
  ])("TASK-28#2 TASK-28#B2 형식이 ^[a-z0-9_]+$ 가 아닌 error %j 는 http_<상태>", async (code, status) => {
    server.use(
      http.get("*/api/odd", () => HttpResponse.json({ status, error: code, message: "Some English detail" }, { status })),
    );
    const error = await failure("/odd");
    expect(error.code).toBe(`http_${status}`);
    expectKoreanOnly(errorMessage(error), "Some English detail");
  });

  it("TASK-28#2 TASK-28#B2 형식은 맞지만 모르는 코드: 서버 message 가 한국어면 그 문장, 영어면 상태 범위 문구", () => {
    expect(errorMessage(new ApiError(400, "brand_new_code", "새 규칙에 맞지 않습니다"))).toBe("새 규칙에 맞지 않습니다");
    const english = errorMessage(new ApiError(400, "brand_new_code", "Something went wrong"));
    expectKoreanOnly(english, "Something", "brand_new_code");
  });

  it.each([
    [0, "서버에 연결할 수 없습니다"],
    [400, "요청"],
    [401, "로그인"],
    [403, "권한"],
    [404, "찾을 수 없습니다"],
    [405, "요청"],
    [408, "시간"],
    [409, "다른 변경"],
    [413, "너무 큽니다"],
    [418, "요청"],
    [429, "요청이 너무 많습니다"],
    [500, "서버"],
    [502, "서버"],
    [503, "서버"],
    [504, "시간"],
    [599, "서버"],
  ])("TASK-28#2 TASK-28#B2 상태 %i 의 알 수 없는 오류는 %j 를 담은 한국어", (status, text) => {
    const message = errorMessage(new ApiError(status, `http_${status}`, "Raw English statusText"));
    expect(message).toContain(text);
    expectKoreanOnly(message, "Raw English", `http_${status}`);
  });

  it("TASK-28#2 TASK-28#B2 ?error= 문자열도 형식이 틀리거나 모르면 일반 한국어 문장", () => {
    expectKoreanOnly(errorMessage("<script>alert(1)</script>"), "script");
    expectKoreanOnly(errorMessage("Internal Server Error"), "Internal");
  });
});

// docs/api.md(TASK-20~27)에 적힌, 프론트가 받을 수 있는 오류 코드 전부. 새 코드가 생기면 여기에 더한다.
const DOCUMENTED_CODES = [
  // 공통(TASK-20·22·23)
  "validation",
  "bad_request",
  "conflict",
  "unauthorized",
  "forbidden",
  "csrf",
  "suspended",
  "step_up_required",
  "not_found",
  "method_not_allowed",
  "not_acceptable",
  "payload_too_large",
  "unsupported_media_type",
  "lock_timeout",
  "rate_limited",
  "internal_error",
  "db_unavailable",
  "db_timeout",
  "service_unavailable",
  // 인증·OTP·계정 연결
  "invalid_credentials",
  "email_taken",
  "invalid_phone",
  "phone_not_allowed",
  "invalid_otp",
  "sms_unavailable",
  "step_up_unavailable",
  "identity_in_use",
  "identity_not_found",
  "last_identity",
  "link_expired",
  "invalid_refresh_token",
  // 게시판·관리자
  "post_not_found",
  "comment_not_found",
  "unknown_category",
  "category_admin_only",
  "too_many_tags",
  "invalid_tag",
  "user_not_found",
  "cannot_suspend_self",
  "cannot_suspend_admin",
  "slug_taken",
  "category_not_found",
  // 첨부(TASK-11·22·26)
  "attachment_type_not_allowed",
  "attachment_too_large",
  "too_many_pending_attachments",
  "uploads_unavailable",
  "storage_unavailable",
  "attachment_not_found",
  "attachment_expired",
  "attachment_forbidden",
  "attachment_not_uploaded",
  "attachment_mismatch",
  "unknown_attachment",
  "attachment_not_confirmed",
  "attachment_in_use",
  "upload_failed",
];

describe("TASK-28#2 문서화된 백엔드 오류 코드 전수 매핑", () => {
  it.each(DOCUMENTED_CODES)("TASK-28#2 TASK-28#B2 %s 는 매핑표의 한국어 문장이다", (code) => {
    expect(ERROR_MESSAGES[code]).toBeDefined();
    const message = errorMessage(new ApiError(400, code, "English server message"));
    expect(message).toBe(ERROR_MESSAGES[code]);
    expectKoreanOnly(message, code, "English");
  });

  it("TASK-28#2 TASK-28#B2 매핑표의 모든 문장은 한국어이고 코드를 그대로 담지 않는다", () => {
    for (const [code, message] of Object.entries(ERROR_MESSAGES)) {
      expectKoreanOnly(message, code);
    }
  });

  it("TASK-28#2 409 conflict 는 다른 사람이 먼저 고쳤으니 새로 고치라고 안내한다", () => {
    expect(errorMessage(new ApiError(409, "conflict", "x"))).toContain("새로 고");
  });

  it("TASK-28#2 429 rate_limited 는 Retry-After 초를, 없으면 일반 문구를 쓴다", () => {
    expect(errorMessage(new ApiError(429, "rate_limited", "x", { retryAfter: 12 }))).toContain("12초");
    expect(errorMessage(new ApiError(429, "rate_limited", "x"))).toBe(ERROR_MESSAGES.rate_limited);
  });
});

// TASK-28#4 FE-12: 계정 연결 콜백(/account/?error=)은 같은 코드라도 "로그인" 대신 "계정 연결" 문구를 쓴다.
const OAUTH_CALLBACK_CODES = [
  "access_denied",
  "authorization_request_not_found",
  "invalid_id_token",
  "invalid_nonce",
  "invalid_grant",
  "invalid_token_response",
  "invalid_user_info_response",
  "invalid_user_info",
  "unknown_provider",
  "oauth2_failed",
  "server_error",
  "temporarily_unavailable",
  "invalid_request",
  "unauthorized_client",
  "unsupported_response_type",
  "invalid_client",
  "unsupported_grant_type",
  "invalid_scope",
  "invalid_state_parameter",
  "client_registration_not_found",
  "login_required",
  "consent_required",
  "interaction_required",
  "missing_signature_verifier",
];

describe("TASK-28#4 계정 연결 모드 OAuth 오류 문구", () => {
  it.each(["oauth2_failed", "server_error", "invalid_request", "access_denied", "temporarily_unavailable"])(
    "TASK-28#4 link 모드 %s 는 계정 연결 전용 문구다",
    (code) => {
      const message = oauthErrorMessage(code, "link");
      expect(message).toBe(OAUTH_LINK_MESSAGES[code]);
      expect(message).toContain("연결");
      expect(message).not.toContain("로그인");
      expectKoreanOnly(message, code);
    },
  );

  it.each(OAUTH_CALLBACK_CODES)("TASK-28#4 link 모드 %s 는 로그인 문구를 쓰지 않는다('로그인 수단' 명사만 허용)", (code) => {
    const message = oauthErrorMessage(code, "link");
    expect(message.replaceAll("로그인 수단", "")).not.toContain("로그인");
    expectKoreanOnly(message, code);
  });

  it("TASK-28#4 login 모드는 기존 표 문구 그대로, 연결 전용 표는 표에 있는 코드만 덮는다", () => {
    expect(oauthErrorMessage("oauth2_failed")).toBe(ERROR_MESSAGES.oauth2_failed);
    for (const code of Object.keys(OAUTH_LINK_MESSAGES)) {
      expect(ERROR_MESSAGES[code]).toBeDefined();
    }
    expect(oauthErrorMessage("identity_in_use", "link")).toBe(ERROR_MESSAGES.identity_in_use);
  });
});
