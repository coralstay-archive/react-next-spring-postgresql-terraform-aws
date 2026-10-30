"use client";

import Link from "next/link";

import { useSessionLostNoticeShown } from "@/components/guards";
import { StepUpNotice } from "@/components/step-up-notice";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useCurrentPath } from "@/hooks/use-current-path";
import { isApiError } from "@/lib/api";
import { loginHref } from "@/lib/auth";
import { errorMessage } from "@/lib/errors";

/**
 * API 오류 한 줄(role="alert"). 제한 세션(403 `step_up_required`)이면 다른 곳과 같은 {@link StepUpNotice}(TASK-32#3)를, 세션이 끝났으면(401)
 * 로그인으로 가는 링크를 붙인다. 작성 화면이 하단 재로그인 안내를 띄우고 있으면 로그인 링크는 겹치지 않게 뺀다(TASK-29#3).
 */
export function ApiErrorAlert({ error, testId }: { error: unknown; testId?: string }) {
  const current = useCurrentPath();
  const noticeShown = useSessionLostNoticeShown();
  if (!error) return null;
  if (isApiError(error) && error.isStepUpRequired) return <StepUpNotice error testId={testId} />;
  return (
    <Alert variant="destructive" data-testid={testId}>
      <AlertDescription>
        <p>{errorMessage(error)}</p>
        {isApiError(error) && error.isSessionExpired && !noticeShown && (
          <Link href={loginHref(current)} className="font-medium underline underline-offset-4">
            로그인하기
          </Link>
        )}
      </AlertDescription>
    </Alert>
  );
}

/**
 * 재조회 실패 배너(TASK-30#4, FE-23). 이미 받은 데이터가 있을 때 백그라운드 재조회가 실패하면 화면을 오류로 바꾸지 않고
 * 기존 데이터 위에 이 작은 안내와 "다시 시도"만 붙인다(목록·상세·댓글·수정·관리자 탭·로그인 수단). 데이터가 없을 때만
 * {@link ApiErrorAlert} 오류 화면이다.
 */
export function RefetchErrorAlert({
  error,
  onRetry,
  retrying = false,
  testId,
}: {
  error: unknown;
  onRetry: () => void;
  retrying?: boolean;
  testId?: string;
}) {
  return (
    <Alert data-testid={testId}>
      <AlertDescription>
        <p>최신 내용을 불러오지 못했습니다. 이전에 받은 내용을 보여 줍니다. {errorMessage(error)}</p>
        <Button variant="outline" size="xs" disabled={retrying} onClick={onRetry}>
          다시 시도
        </Button>
      </AlertDescription>
    </Alert>
  );
}
