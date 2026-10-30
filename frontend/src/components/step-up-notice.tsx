import Link from "next/link";
import type { ReactNode } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ERROR_MESSAGES } from "@/lib/errors";

/** 내 계정의 다시 인증 영역. */
export const STEP_UP_HREF = "/account/#step-up";

/**
 * 제한 세션(step-up) 안내의 유일한 표시(TASK-32#3, FE-31). 레이아웃 배너, 403 `step_up_required` 오류(ApiErrorAlert), 제한 세션에서 미리
 * 막은 작성 컨트롤(글 편집기·댓글 폼)·삭제가 모두 이것을 쓴다 — 다시 인증 링크가 어디서나 같다.
 *
 * - 기본: 제목·표준 문구(`ERROR_MESSAGES.step_up_required`)가 있는 안내. `children` 은 그 자리에 맞는 한 줄이다.
 *   상태 안내(상단 배너)는 `role="status"`, 403 오류로 받았으면 `error`(destructive 스타일, `role="alert"`) — alert 는 오류에만(TASK-34#3).
 * - `inline`: 화면 안에 처음부터 떠 있는 조용한 간략형(`role="status"`). 상단 배너가 이미 알리므로 같은 내용을 다시 크게
 *   읽히지 않게 한다. `children` 이 짧은 문구다(예: "등록하려면 본인 확인이 필요합니다 · 작성 내용은 임시 저장됩니다").
 */
export function StepUpNotice({
  error = false,
  inline = false,
  testId,
  children,
}: {
  error?: boolean;
  inline?: boolean;
  testId?: string;
  children?: ReactNode;
}) {
  const link = (
    <Link href={STEP_UP_HREF} className="font-medium underline underline-offset-4">
      다시 인증하기
    </Link>
  );
  if (inline) {
    return (
      <p role="status" className="text-muted-foreground text-sm" data-testid={testId} data-step-up-notice="">
        {children} {link}
      </p>
    );
  }
  return (
    <Alert variant={error ? "destructive" : "default"} data-testid={testId} data-step-up-notice="">
      <AlertTitle>다시 인증이 필요합니다</AlertTitle>
      <AlertDescription>
        <p>
          {ERROR_MESSAGES.step_up_required}
          {children ? <> {children}</> : null}
        </p>
        {link}
      </AlertDescription>
    </Alert>
  );
}
