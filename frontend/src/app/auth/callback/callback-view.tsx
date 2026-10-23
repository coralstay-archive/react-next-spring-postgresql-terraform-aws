"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { FormError } from "@/components/auth/form-error";
import { buttonVariants } from "@/components/ui/button";
import { fetchMe, loginHref, safeNext, useSessionChanged } from "@/lib/auth";
import { errorMessage, NEXT_STORAGE_KEY, oauthErrorMessage } from "@/lib/errors";

/** 로그인 전에 기억한 next(safeNext 를 거친 값). 읽기만 한다. */
function peekNext(): string {
  try {
    return safeNext(window.sessionStorage.getItem(NEXT_STORAGE_KEY));
  } catch {
    return "/";
  }
}

/** 기억한 next 는 성공이든 실패든 한 번만 쓰고 지운다(TASK-29#7). */
function forgetNext() {
  try {
    window.sessionStorage.removeItem(NEXT_STORAGE_KEY);
  } catch {
    // 저장소를 못 쓰면 지울 것도 없다.
  }
}

/**
 * 소셜 로그인 콜백 착지점. 백엔드가 `/auth/callback/?status=ok` 또는 `?error=<코드>` 로 보낸다.
 * 성공이면 세션이 바뀌었으므로(새 SESSION·XSRF-TOKEN 쿠키) me 를 다시 받고, 로그인 전에 기억한 경로로 간다.
 * 실패하면(TASK-29#7, FE-13) 기억한 next 를 sessionStorage 에서 지운다(다음 로그인에 엉뚱하게 재사용되지 않게). 대신
 * "로그인으로 돌아가기" 링크의 `?next=` 에 실어(safeNext 를 거친 값) 다시 로그인하면 원래 가려던 곳으로 간다.
 */
export function AuthCallbackView() {
  const params = useSearchParams();
  const router = useRouter();
  const sessionChanged = useSessionChanged();
  const status = params.get("status");
  // `?error=`(빈 값 포함)는 실패다. status 도 error 도 없으면 state 를 잃은 것으로 본다.
  const rawError = params.get("error");
  const errorCode = rawError !== null ? rawError : status === "ok" ? null : "authorization_request_not_found";
  const [failure, setFailure] = useState<string | null>(null);
  // 마운트 때 한 번 읽어 둔다(성공 이동·실패 링크 모두 이 값). 이 화면은 useSearchParams 때문에 클라이언트에서만 그린다.
  const [storedNext] = useState(peekNext);
  const started = useRef(false);

  useEffect(() => {
    if (status !== "ok" || started.current) return;
    started.current = true;
    fetchMe()
      .then((me) => {
        if (!me) {
          setFailure("로그인 정보를 확인하지 못했습니다. 다시 로그인하세요.");
          return;
        }
        forgetNext();
        sessionChanged(me);
        router.replace(storedNext);
      })
      .catch((e) => setFailure(errorMessage(e)));
  }, [status, router, sessionChanged, storedNext]);

  const message = errorCode !== null ? oauthErrorMessage(errorCode) : failure;
  useEffect(() => {
    if (message) forgetNext();
  }, [message]);
  if (!message) {
    return <p role="status">로그인 처리 중…</p>;
  }
  return (
    <div className="grid w-full max-w-md gap-4">
      <h1 className="text-xl font-semibold">로그인하지 못했습니다</h1>
      <FormError message={message} />
      <Link href={loginHref(storedNext === "/" ? undefined : storedNext)} className={buttonVariants({ variant: "outline" })}>
        로그인으로 돌아가기
      </Link>
    </div>
  );
}
