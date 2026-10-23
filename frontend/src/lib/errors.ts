import { ApiError, COOKIES_BLOCKED_MESSAGE, ERROR_CODE_PATTERN, statusMessage, TIMEOUT_MESSAGE } from "@/lib/api";

/**
 * 백엔드 오류 코드 → 화면에 보일 한국어 문장. API 오류 JSON 의 `error` 와 OAuth 콜백 `?error=` 가 같은 표를 쓴다.
 * 표에 없는 코드는 서버 `message` 가 한국어일 때만 그 문장을, 아니면 상태 범위별 한국어를 쓴다(TASK-28#2 B2:
 * 영어 원문·내부 코드를 화면에 올리지 않는다). docs/api.md 의 코드는 모두 이 표에 있다(errors.test.ts 가 확인).
 */
export const ERROR_MESSAGES: Record<string, string> = {
  // 공통
  network: "서버에 연결할 수 없습니다. 네트워크를 확인하세요.",
  unauthorized: "로그인이 필요합니다.",
  forbidden: "권한이 없습니다.",
  csrf: "보안 토큰이 만료되었습니다. 페이지를 새로 고친 뒤 다시 시도하세요.",
  // TASK-30#2: 클라이언트가 정하는 코드(서버는 보내지 않는다)
  cookies_blocked: COOKIES_BLOCKED_MESSAGE,
  timeout: TIMEOUT_MESSAGE,
  suspended: "정지된 계정입니다. 관리자에게 문의하세요.",
  step_up_required: "휴대폰으로 로그인한 세션은 읽기만 할 수 있습니다. 기존 로그인 수단으로 다시 인증하세요.",
  validation: "입력값을 확인하세요.",
  bad_request: "잘못된 요청입니다.",
  bad_response: "서버 응답을 해석하지 못했습니다. 잠시 후 다시 시도하세요.",
  // 공통(TASK-20·23): 필터·컨테이너·DB 오류
  not_found: "요청한 내용을 찾을 수 없습니다.",
  method_not_allowed: "지원하지 않는 요청입니다. 페이지를 새로 고친 뒤 다시 시도하세요.",
  not_acceptable: "지원하지 않는 응답 형식입니다. 페이지를 새로 고친 뒤 다시 시도하세요.",
  unsupported_media_type: "지원하지 않는 요청 형식입니다. 페이지를 새로 고친 뒤 다시 시도하세요.",
  payload_too_large: "보낸 내용이 너무 큽니다. 본문을 줄여서 다시 시도하세요.",
  conflict: "다른 곳에서 먼저 변경되어 저장하지 못했습니다. 페이지를 새로 고친 뒤 다시 시도하세요.",
  lock_timeout: "다른 요청을 처리하는 중입니다. 잠시 후 다시 시도하세요.",
  internal_error: "서버에서 오류가 났습니다. 잠시 후 다시 시도하세요.",
  db_unavailable: "서비스에 일시적인 장애가 있습니다. 잠시 후 다시 시도하세요.",
  db_timeout: "서버가 바빠 요청을 끝내지 못했습니다. 잠시 후 다시 시도하세요.",
  service_unavailable: "서비스를 잠시 이용할 수 없습니다. 잠시 후 다시 시도하세요.",
  // LOCAL
  invalid_credentials: "이메일 또는 비밀번호가 올바르지 않습니다.",
  email_taken: "이미 가입된 이메일입니다.",
  invalid_refresh_token: "로그인이 만료되었습니다. 다시 로그인하세요.",
  // 휴대폰 OTP
  invalid_phone: "휴대폰 번호 형식이 올바르지 않습니다.",
  phone_not_allowed: "인증번호를 받을 수 없는 번호입니다. 국내 휴대폰 번호를 입력하세요.",
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
  // OAuth 콜백(TASK-25·28#4): 백엔드 일반 실패, 우리 쪽 계정 처리 실패, 제공자가 돌려준 표준 OAuth 2.0·OIDC 오류,
  // Spring Security OAuth2 클라이언트 내부 코드. 모르는 코드는 oauthErrorMessage 의 콜백 전용 fallback.
  oauth2_failed: "소셜 로그인을 완료하지 못했습니다. 처음부터 다시 시도하세요.",
  server_error: "로그인을 처리하는 중 오류가 났습니다. 잠시 후 다시 시도하세요.",
  temporarily_unavailable: "로그인 제공자가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요.",
  invalid_request: "로그인 요청이 올바르지 않습니다. 처음부터 다시 시도하세요.",
  unauthorized_client: "이 로그인 수단의 설정에 문제가 있습니다. 다른 수단으로 로그인하거나 관리자에게 문의하세요.",
  unsupported_response_type: "이 로그인 수단의 설정에 문제가 있습니다. 다른 수단으로 로그인하거나 관리자에게 문의하세요.",
  invalid_client: "이 로그인 수단의 설정에 문제가 있습니다. 다른 수단으로 로그인하거나 관리자에게 문의하세요.",
  unsupported_grant_type: "이 로그인 수단의 설정에 문제가 있습니다. 다른 수단으로 로그인하거나 관리자에게 문의하세요.",
  invalid_scope: "요청한 정보 제공 권한을 받지 못했습니다. 다시 시도하세요.",
  invalid_state_parameter: "로그인 요청이 만료되었습니다. 처음부터 다시 시도하세요.",
  client_registration_not_found: "지원하지 않거나 설정되지 않은 로그인 수단입니다.",
  login_required: "제공자에 로그인한 뒤 다시 시도하세요.",
  consent_required: "정보 제공에 동의해야 로그인할 수 있습니다. 다시 시도하세요.",
  interaction_required: "제공자 화면에서 로그인을 마친 뒤 다시 시도하세요.",
  missing_signature_verifier: "제공자 인증 정보를 확인하지 못했습니다. 다시 시도하세요.",
  // 게시판(TASK-14)
  post_not_found: "글을 찾을 수 없습니다. 삭제되었거나 볼 수 없는 글입니다.",
  comment_not_found: "댓글을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.",
  unknown_category: "없는 카테고리입니다.",
  category_admin_only: "이 카테고리에는 관리자만 글을 쓸 수 있습니다.",
  too_many_tags: "태그는 5개까지 붙일 수 있습니다.",
  invalid_tag: "태그는 1~30자여야 합니다.",
  // 관리자(TASK-12·15)
  user_not_found: "사용자를 찾을 수 없습니다.",
  cannot_suspend_self: "자기 자신은 정지할 수 없습니다.",
  cannot_suspend_admin: "다른 관리자는 정지할 수 없습니다.",
  slug_taken: "이미 쓰고 있는 slug 입니다. 다른 값을 입력하세요.",
  category_not_found: "카테고리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.",
  // 첨부(TASK-11)
  attachment_type_not_allowed: "PNG·JPEG·WebP·GIF 이미지만 올릴 수 있습니다.",
  attachment_too_large: "이미지는 5MB 이하만 올릴 수 있습니다.",
  attachment_not_found: "첨부를 찾을 수 없습니다. 다시 올려 주세요.",
  attachment_forbidden: "본인이 올린 첨부만 붙일 수 있습니다.",
  attachment_not_uploaded: "업로드가 끝나지 않았습니다. 다시 올려 주세요.",
  attachment_mismatch: "올린 파일이 요청한 크기·형식과 다릅니다. 다시 올려 주세요.",
  unknown_attachment: "없는 첨부입니다. 다시 올려 주세요.",
  attachment_not_confirmed: "확인되지 않은 첨부입니다. 다시 올려 주세요.",
  attachment_in_use: "이미 다른 글에 붙은 첨부입니다.",
  uploads_unavailable: "지금은 이미지를 올릴 수 없습니다(저장소 미설정).",
  upload_failed: "이미지를 저장소에 올리지 못했습니다. 다시 시도하세요.",
  // 첨부(TASK-22·26)
  too_many_pending_attachments: "업로드를 마치지 않은 이미지가 너무 많습니다. 올린 이미지로 글을 등록한 뒤 다시 시도하세요.",
  storage_unavailable: "이미지 저장소에 일시적인 문제가 있습니다. 잠시 후 다시 시도하세요.",
  attachment_expired: "올린 지 오래되어 만료된 이미지입니다. 다시 올려 주세요.",
};

const FALLBACK = "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";

/** OAuth 콜백 `?error=` 가 모르는 코드일 때(로그인 콜백 `/auth/callback/`). */
export const OAUTH_LOGIN_FALLBACK = "소셜 로그인을 완료하지 못했습니다. 처음부터 다시 시도하세요.";
/** 계정 연결 콜백(`/account/?error=`)이 모르는 코드일 때. */
export const OAUTH_LINK_FALLBACK = "계정을 연결하지 못했습니다. 다시 시도하세요.";

/**
 * 계정 연결 콜백(`/account/?error=`) 전용 문장(TASK-28#4). 같은 OAuth 코드라도 로그인 문구("로그인을 …")가 아니라
 * 계정 연결 문구로 보인다. 여기 없는 코드는 ERROR_MESSAGES 문장을 그대로 쓴다(로그인을 말하지 않는 문장들).
 */
export const OAUTH_LINK_MESSAGES: Record<string, string> = {
  access_denied: "계정 연결을 취소했습니다.",
  authorization_request_not_found: "계정 연결 요청이 만료되었습니다. 처음부터 다시 시도하세요.",
  invalid_state_parameter: "계정 연결 요청이 만료되었습니다. 처음부터 다시 시도하세요.",
  oauth2_failed: "계정 연결을 완료하지 못했습니다. 처음부터 다시 시도하세요.",
  server_error: "계정 연결을 처리하는 중 오류가 났습니다. 잠시 후 다시 시도하세요.",
  temporarily_unavailable: "연결할 제공자가 일시적으로 응답하지 않습니다. 잠시 후 다시 연결하세요.",
  invalid_request: "계정 연결 요청이 올바르지 않습니다. 처음부터 다시 시도하세요.",
  unauthorized_client: "이 로그인 수단의 설정에 문제가 있어 연결할 수 없습니다. 관리자에게 문의하세요.",
  unsupported_response_type: "이 로그인 수단의 설정에 문제가 있어 연결할 수 없습니다. 관리자에게 문의하세요.",
  invalid_client: "이 로그인 수단의 설정에 문제가 있어 연결할 수 없습니다. 관리자에게 문의하세요.",
  unsupported_grant_type: "이 로그인 수단의 설정에 문제가 있어 연결할 수 없습니다. 관리자에게 문의하세요.",
  login_required: "제공자 화면에서 인증을 마친 뒤 다시 연결하세요.",
  consent_required: "정보 제공에 동의해야 계정을 연결할 수 있습니다. 다시 시도하세요.",
  interaction_required: "제공자 화면에서 인증을 마친 뒤 다시 연결하세요.",
};

/** 소셜 로그인 시작 전에 돌아올 경로를 기억하는 sessionStorage 키(OAuth 왕복은 next 를 실어 나르지 못한다). */
export const NEXT_STORAGE_KEY = "auth.next";

const HANGUL = /[가-힣]/;

/** 형식(`^[a-z0-9_]+$`)이 맞고 표에 있는 코드의 문장. 아니면 null. */
function mapped(code: string): string | null {
  if (!ERROR_CODE_PATTERN.test(code) || !Object.hasOwn(ERROR_MESSAGES, code)) return null;
  return ERROR_MESSAGES[code];
}

/**
 * OAuth 콜백 `?error=<코드>`(TASK-28#4). 형식이 맞고 표에 있으면 표 문구, 아니면 콜백 전용 fallback.
 * 연결 모드(`link`)는 OAUTH_LINK_MESSAGES 가 로그인 문구를 덮는다. 코드는 URL 에서 온 값이라 그대로 보이지 않는다.
 */
export function oauthErrorMessage(code: string, mode: "login" | "link" = "login"): string {
  const known = mapped(code);
  if (mode === "login") return known ?? OAUTH_LOGIN_FALLBACK;
  if (known === null) return OAUTH_LINK_FALLBACK;
  return Object.hasOwn(OAUTH_LINK_MESSAGES, code) ? OAUTH_LINK_MESSAGES[code] : known;
}

export function errorMessage(error: unknown): string {
  if (typeof error === "string") return mapped(error) ?? FALLBACK;
  if (error instanceof ApiError) {
    if (error.code === "rate_limited" && error.retryAfter) {
      return `요청이 너무 많습니다. ${error.retryAfter}초 후 다시 시도하세요.`;
    }
    const known = mapped(error.code);
    if (known) return known;
    // 표에 없는 계약 코드: 서버 message 는 한국어 문장이 계약이지만, 영어·HTML 이 섞여 오면 쓰지 않는다.
    if (HANGUL.test(error.message) && !/[<>]/.test(error.message)) return error.message;
    return statusMessage(error.status);
  }
  return FALLBACK;
}
