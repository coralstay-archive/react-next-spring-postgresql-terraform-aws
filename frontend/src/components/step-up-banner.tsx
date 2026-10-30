"use client";

import { StepUpNotice } from "@/components/step-up-notice";
import { useMe } from "@/lib/auth";

/**
 * 제한 세션 안내(TASK-7 B4: 다른 수단도 있는 계정에 휴대폰으로 로그인). 읽기는 되지만 쓰기는 403 step_up_required 이므로
 * 내 계정의 다시 인증(step-up) 영역으로 안내한다. 표시는 StepUpNotice(TASK-32#3)다.
 */
export function StepUpBanner() {
  const { data: me } = useMe();
  if (!me?.stepUpRequired) return null;
  return (
    <div className="mx-auto w-full max-w-5xl px-4 pt-4">
      <StepUpNotice testId="step-up-banner">글쓰기·댓글·좋아요 같은 기능은 다시 인증한 뒤에 쓸 수 있습니다.</StepUpNotice>
    </div>
  );
}
