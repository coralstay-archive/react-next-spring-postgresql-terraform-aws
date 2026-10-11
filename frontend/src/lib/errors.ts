import { ApiError } from "@/lib/api";

/**
 * 백엔드 오류 코드 → 화면에 보일 한국어 문장. API 오류 JSON 의 `error` 와 OAuth 콜백 `?error=` 가 같은 표를 쓴다.
 * 표에 없는 코드는 서버 `message`(이미 한국어)를 쓴다.
 */
export const ERROR_MESSAGES: Record<string, string> = {
  // 공통
  network: "서버에 연결할 수 없습니다. 네트워크를 확인하세요.",
  unauthorized: "로그인이 필요합니다.",
  forbidden: "권한이 없습니다.",
  csrf: "보안 토큰이 만료되었습니다. 페이지를 새로 고친 뒤 다시 시도하세요.",
  suspended: "정지된 계정입니다. 관리자에게 문의하세요.",
  step_up_required: "휴대폰으로 로그인한 세션은 읽기만 할 수 있습니다. 기존 로그인 수단으로 다시 인증하세요.",
  validation: "입력값을 확인하세요.",
  bad_request: "잘못된 요청입니다.",
  // LOCAL
  invalid_credentials: "이메일 또는 비밀번호가 올바르지 않습니다.",
  email_taken: "이미 가입된 이메일입니다.",
  // 휴대폰 OTP
  invalid_phone: "휴대폰 번호 형식이 올바르지 않습니다.",
  invalid_otp: "인증번호가 올바르지 않거나 만료되었습니다.",
  rate_limited: "요청이 너무 많습니다. 잠시 후 다시 시도하세요.",
  sms_unavailable: "문자를 보내지 못했습니다. 잠시 후 다시 시도하세요.",
  // step-up · 계정 연결
  step_up_unavailable: "비밀번호가 없는 계정입니다. 연결된 소셜 계정으로 다시 로그인하세요.",
  identity_in_use: "이미 다른 계정에 연결된 로그인 수단입니다.",
  identity_not_found: "로그인 수단을 찾을 수 없습니다.",
  last_identity: "마지막 로그인 수단은 해제할 수 없습니다. 다른 수단을 먼저 연결하세요.",
  link_expired: "연결 시간이 지났거나 로그인 계정이 바뀌었습니다. 다시 시도하세요.",
  // OAuth 콜백
  access_denied: "로그인을 취소했습니다.",
  authorization_request_not_found: "로그인 요청이 만료되었습니다. 처음부터 다시 시도하세요.",
  invalid_id_token: "제공자 인증 정보를 확인하지 못했습니다. 다시 시도하세요.",
  invalid_nonce: "제공자 인증 정보를 확인하지 못했습니다. 다시 시도하세요.",
  invalid_grant: "인가 코드가 만료되었습니다. 다시 시도하세요.",
  invalid_token_response: "제공자에게서 토큰을 받지 못했습니다. 다시 시도하세요.",
  invalid_user_info_response: "제공자에게서 사용자 정보를 받지 못했습니다. 다시 시도하세요.",
  invalid_user_info: "제공자 사용자 정보가 올바르지 않습니다.",
  unknown_provider: "지원하지 않거나 설정되지 않은 로그인 수단입니다.",
};

const FALLBACK = "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";

/** 소셜 로그인 시작 전에 돌아올 경로를 기억하는 sessionStorage 키(OAuth 왕복은 next 를 실어 나르지 못한다). */
export const NEXT_STORAGE_KEY = "auth.next";

export function errorMessage(error: unknown): string {
  if (typeof error === "string") return ERROR_MESSAGES[error] ?? FALLBACK;
  if (error instanceof ApiError) {
    if (error.code === "rate_limited" && error.retryAfter) {
      return `요청이 너무 많습니다. ${error.retryAfter}초 후 다시 시도하세요.`;
    }
    return ERROR_MESSAGES[error.code] ?? (error.message || FALLBACK);
  }
  return FALLBACK;
}
