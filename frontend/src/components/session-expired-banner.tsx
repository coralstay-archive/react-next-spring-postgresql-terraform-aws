"use client";

import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { useCurrentPath } from "@/hooks/use-current-path";
import { useSessionLostNoticeCount } from "@/components/guards";
import { loginHref, useAuthFlag } from "@/lib/auth";
import { SESSION_EXPIRED_KEY } from "@/lib/query";

/** 세션 만료 플래그(TASK-29#1). 값은 query.ts 의 만료 처리가 setQueryData 로만 바꾼다(서버에서 받지 않는다). */
export function useSessionExpired(): boolean {
  return useAuthFlag(SESSION_EXPIRED_KEY);
}

/**
 * 전역 "세션이 만료되었습니다" 안내(TASK-29#1, FE-5). 로그인 상태였는데 세션이 끝났을 때만 보인다(처음부터 익명이면 안 보인다).
 * 다시 로그인하면 지금 경로로 돌아온다(next 는 로그인 화면에서 safeNext 를 거친다). 다시 로그인하면 저절로 사라진다.
 * 작성 화면(RequireAuth 가 내용을 유지하며 하단 고정 안내를 띄운 동안)에서는 숨는다 — 안내는 하나만(리뷰 결정).
 * `useCurrentPath`(useSearchParams) 때문에 `<Suspense>` 안에서 쓴다.
 */
export function SessionExpiredBanner() {
  const expired = useSessionExpired();
  const client = useQueryClient();
  const current = useCurrentPath();
  const composing = useSessionLostNoticeCount() > 0;
  if (!expired || composing) return null;
  return (
    <div className="mx-auto w-full max-w-5xl px-4 pt-4" data-testid="session-expired-banner">
      <Alert variant="destructive">
        <AlertTitle>세션이 만료되었습니다</AlertTitle>
        <AlertDescription>
          <p>다시 로그인하세요. 작성 중이던 글·댓글은 이 탭에 임시 저장되어 로그인한 뒤 이어서 쓸 수 있습니다.</p>
          <div className="flex items-center gap-3">
            <Link href={loginHref(current)} className="font-medium underline underline-offset-4">
              다시 로그인
            </Link>
            <Button variant="ghost" size="xs" onClick={() => client.setQueryData(SESSION_EXPIRED_KEY, false)}>
              안내 닫기
            </Button>
          </div>
        </AlertDescription>
      </Alert>
    </div>
  );
}
