"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { FormError } from "@/components/auth/form-error";
import { buttonVariants } from "@/components/ui/button";
import { fetchMe, safeNext, useSessionChanged } from "@/lib/auth";
import { errorMessage, NEXT_STORAGE_KEY } from "@/lib/errors";

function takeNext(): string {
  try {
    const next = window.sessionStorage.getItem(NEXT_STORAGE_KEY);
    window.sessionStorage.removeItem(NEXT_STORAGE_KEY);
    return safeNext(next);
  } catch {
    return "/";
  }
}

/**
 * 소셜 로그인 콜백 착지점. 백엔드가 `/auth/callback/?status=ok` 또는 `?error=<코드>` 로 보낸다.
 * 성공이면 세션이 바뀌었으므로(새 SESSION·XSRF-TOKEN 쿠키) me 를 다시 받고, 로그인 전에 기억한 경로로 간다.
 */
export function AuthCallbackView() {
  const params = useSearchParams();
  const router = useRouter();
  const sessionChanged = useSessionChanged();
  const status = params.get("status");
  const errorCode = params.get("error") ?? (status === "ok" ? null : "authorization_request_not_found");
  const [failure, setFailure] = useState<string | null>(null);
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
        sessionChanged(me);
        router.replace(takeNext());
      })
      .catch((e) => setFailure(errorMessage(e)));
  }, [status, router, sessionChanged]);

  const message = errorCode ? errorMessage(errorCode) : failure;
  if (!message) {
    return <p role="status">로그인 처리 중…</p>;
  }
  return (
    <div className="grid w-full max-w-md gap-4">
      <h1 className="text-xl font-semibold">로그인하지 못했습니다</h1>
      <FormError message={message} />
      <Link href="/login/" className={buttonVariants({ variant: "outline" })}>
        로그인으로 돌아가기
      </Link>
    </div>
  );
}
