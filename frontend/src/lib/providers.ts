import { apiPath } from "@/lib/api";
import type { Provider } from "@/lib/auth";

/** 소셜·OIDC 제공자(백엔드 registrationId). */
export type SocialProviderId = "google" | "github" | "naver" | "kakao" | "cognito";

export const SOCIAL_PROVIDERS: { id: SocialProviderId; provider: Provider; label: string }[] = [
  { id: "google", provider: "GOOGLE", label: "Google" },
  { id: "github", provider: "GITHUB", label: "GitHub" },
  { id: "naver", provider: "NAVER", label: "네이버" },
  { id: "kakao", provider: "KAKAO", label: "카카오" },
  { id: "cognito", provider: "COGNITO", label: "Cognito" },
];

export const PROVIDER_LABELS: Record<Provider, string> = {
  LOCAL: "이메일·비밀번호",
  GOOGLE: "Google",
  GITHUB: "GitHub",
  NAVER: "네이버",
  KAKAO: "카카오",
  COGNITO: "Cognito",
  PHONE: "휴대폰",
};

/** 소셜 로그인 시작(페이지 이동). 성공하면 백엔드가 /auth/callback/?status=ok 로 돌려보낸다. */
export function socialLoginHref(id: SocialProviderId): string {
  return apiPath(`/oauth2/authorization/${id}`);
}

/** 현재 계정에 소셜 수단 연결 시작(페이지 이동). 끝나면 /account/?linked=<p> 또는 ?error=<code>. */
export function socialLinkHref(id: SocialProviderId): string {
  return apiPath(`/me/identities/link/${id}`);
}

export function socialIdOf(provider: Provider): SocialProviderId | null {
  return SOCIAL_PROVIDERS.find((p) => p.provider === provider)?.id ?? null;
}
