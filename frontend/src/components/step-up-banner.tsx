"use client";

import Link from "next/link";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { useMe } from "@/lib/auth";

/**
 * 제한 세션 안내(TASK-7 B4: 다른 수단도 있는 계정에 휴대폰으로 로그인). 읽기는 되지만 쓰기는 403 step_up_required 이므로
 * 내 계정의 다시 인증(step-up) 영역으로 안내한다.
 */
export function StepUpBanner() {
  const { data: me } = useMe();
  if (!me?.stepUpRequired) return null;
  return (
    <div className="mx-auto w-full max-w-5xl px-4 pt-4">
      <Alert>
        <AlertTitle>다시 인증이 필요합니다</AlertTitle>
        <AlertDescription>
          <p>
            휴대폰 번호로 로그인해 지금은 읽기만 할 수 있습니다. 글쓰기 등 다른 기능을 쓰려면 기존 로그인 수단으로 다시 인증하세요.
          </p>
          <Link href="/account/#step-up" className="font-medium underline underline-offset-4">
            다시 인증하기
          </Link>
        </AlertDescription>
      </Alert>
    </div>
  );
}
