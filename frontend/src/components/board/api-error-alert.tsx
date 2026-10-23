"use client";

import Link from "next/link";

import { useSessionLostNoticeShown } from "@/components/guards";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { useCurrentPath } from "@/hooks/use-current-path";
import { isApiError } from "@/lib/api";
import { loginHref } from "@/lib/auth";
import { errorMessage } from "@/lib/errors";

/**
 * API 오류 한 줄(role="alert"). 제한 세션(403 `step_up_required`)이면 내 계정의 다시 인증으로, 세션이 끝났으면(401) 로그인으로
 * 가는 링크를 붙인다. 작성 화면이 하단 재로그인 안내를 띄우고 있으면 로그인 링크는 겹치지 않게 뺀다(TASK-29#3).
 */
export function ApiErrorAlert({ error, testId }: { error: unknown; testId?: string }) {
  const current = useCurrentPath();
  const noticeShown = useSessionLostNoticeShown();
  if (!error) return null;
  return (
    <Alert variant="destructive" data-testid={testId}>
      <AlertDescription>
        <p>{errorMessage(error)}</p>
        {isApiError(error) && error.isStepUpRequired && (
          <Link href="/account/#step-up" className="font-medium underline underline-offset-4">
            다시 인증하기
          </Link>
        )}
        {isApiError(error) && error.isSessionExpired && !noticeShown && (
          <Link href={loginHref(current)} className="font-medium underline underline-offset-4">
            로그인하기
          </Link>
        )}
      </AlertDescription>
    </Alert>
  );
}
