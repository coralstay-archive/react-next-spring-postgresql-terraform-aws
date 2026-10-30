"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";

import { api, ApiError } from "@/lib/api";
import { queryKeys } from "@/lib/query";

export type Provider = "LOCAL" | "GOOGLE" | "GITHUB" | "NAVER" | "KAKAO" | "COGNITO" | "PHONE";

/** `GET /api/auth/me` 와 로그인·가입·OTP 검증 응답(UserSummary). */
export interface Me {
  id: number;
  nickname: string;
  email: string | null;
  role: "USER" | "ADMIN";
  status: "ACTIVE" | "SUSPENDED";
  providers: Provider[];
  /** 제한 세션(재활용 번호 보호). 쓰기는 403 step_up_required. */
  stepUpRequired: boolean;
  /** 다시 인증할 수 있는 수단(PHONE 제외). */
  stepUpProviders: Provider[];
}

/** 익명이면 null(401 은 오류가 아니라 "로그인 안 함"). */
export async function fetchMe(): Promise<Me | null> {
  try {
    return await api<Me>("/auth/me");
  } catch (error) {
    if (error instanceof ApiError && error.isUnauthorized) return null;
    throw error;
  }
}

/** 현재 사용자. `data` 가 undefined 면 아직 모름(로딩), null 이면 익명. */
export function useMe() {
  return useQuery({ queryKey: queryKeys.me, queryFn: fetchMe });
}

/**
 * 로그인 계열(LOCAL 로그인, OTP 검증, step-up) 성공 뒤 호출: 세션이 바뀌었으니 me 를 응답으로 채우고 다른 캐시는 초기화한다.
 * CSRF 토큰은 응답 쿠키로 회전했고, api() 가 요청마다 쿠키를 다시 읽는다.
 */
export function useSessionChanged() {
  const client = useQueryClient();
  return useCallback(
    (me: Me | null) => {
      client.setQueryData(queryKeys.me, me);
      // 사용자·권한이 바뀌었을 수 있다: 다른 쿼리는 데이터를 버리고(화면에 떠 있는 것은 바로) 다시 받는다.
      void client.resetQueries({ predicate: (query) => query.queryKey[0] !== queryKeys.me[0] });
    },
    [client],
  );
}

export function useLogout() {
  const sessionChanged = useSessionChanged();
  return useMutation({
    mutationFn: () => api<void>("/auth/logout", { method: "POST" }),
    onSettled: () => sessionChanged(null),
  });
}

/** 제어 문자(탭·개행 포함, URL 파서가 지워 버린다)·역슬래시(브라우저가 `/` 로 바꾼다)·공백. */
const UNSAFE_NEXT_CHARS = /[\u0000- \u007F\\]/;

/**
 * `?next=` 로 받은 이동 경로(오픈 리다이렉트 방지, TASK-28#1). 로그인 후 이동은 언제나 같은 오리진이다(B1).
 *
 * - 원문에 제어 문자·역슬래시·공백이 있으면 거부한다(`/\t/evil.com`, `/\evil.com` 처럼 파서마다 해석이 갈리는 값).
 * - `/` 로 시작하는 경로이거나 현재 오리진의 절대 URL 이어야 한다. `new URL(next, origin)` 으로 해석해 오리진을 비교한다.
 * - 쓰는 값은 해석한 `pathname + search + hash` 뿐이다(사용자 정보·호스트는 버린다).
 * - 결과가 `//` 로 시작하거나, 경로를 한 번 디코드했을 때 역슬래시·제어 문자·`//` 시작이 나오면(`/%2F/evil.com`,
 *   `/%5Cevil.com`) 거부한다. 같은 오리진이라도 다음 단계(라우터·프록시)가 디코드하면 외부 주소가 될 수 있다.
 */
export function safeNext(next: string | null | undefined, fallback = "/"): string {
  if (!next || UNSAFE_NEXT_CHARS.test(next)) return fallback;
  const origin = typeof window === "undefined" ? "http://localhost" : window.location.origin;
  if (!next.startsWith("/") && !next.startsWith(`${origin}/`)) return fallback;
  let url: URL;
  try {
    url = new URL(next, origin);
  } catch {
    return fallback;
  }
  if (url.origin !== origin) return fallback;
  const path = `${url.pathname}${url.search}${url.hash}`;
  if (!path.startsWith("/") || path.startsWith("//")) return fallback;
  let decoded: string;
  try {
    decoded = decodeURIComponent(url.pathname);
  } catch {
    return fallback;
  }
  if (UNSAFE_NEXT_CHARS.test(decoded.replaceAll(" ", "")) || decoded.startsWith("//")) return fallback;
  return path;
}

export function loginHref(next?: string): string {
  return next ? `/login/?next=${encodeURIComponent(next)}` : "/login/";
}
