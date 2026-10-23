import Link from "next/link";
import type { ReactNode } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ERROR_MESSAGES } from "@/lib/errors";

/** 내 계정의 다시 인증 영역. */
export const STEP_UP_HREF = "/account/#step-up";

/**
 * 제한 세션(step-up) 안내의 유일한 표시(TASK-32#3, FE-31). 레이아웃 배너, 403 `step_up_required` 오류(ApiErrorAlert), 제한 세션에서 미리
 * 막은 작성 컨트롤(글 편집기·댓글 폼)이 모두 이것을 쓴다 — 제목·표준 문구(`ERROR_MESSAGES.step_up_required`)·다시 인증 링크가 어디서나 같다.
 * `children` 은 그 자리에 맞는 한 줄(예: "입력은 임시 저장됩니다")이다. 오류로 받았으면 `error`(destructive 스타일).
 */
export function StepUpNotice({
  error = false,
  testId,
  children,
}: {
  error?: boolean;
  testId?: string;
  children?: ReactNode;
}) {
  return (
    <Alert variant={error ? "destructive" : "default"} data-testid={testId} data-step-up-notice="">
      <AlertTitle>다시 인증이 필요합니다</AlertTitle>
      <AlertDescription>
        <p>
          {ERROR_MESSAGES.step_up_required}
          {children ? <> {children}</> : null}
        </p>
        <Link href={STEP_UP_HREF} className="font-medium underline underline-offset-4">
          다시 인증하기
        </Link>
      </AlertDescription>
    </Alert>
  );
}
