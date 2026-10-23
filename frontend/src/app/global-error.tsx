"use client";

import Link from "next/link";
import { useEffect } from "react";

import "./globals.css";

/**
 * 루트 레이아웃까지 실패했을 때의 마지막 오류 화면(TASK-34#1, FE-34). 레이아웃을 대신하므로 자체 `<html>`·`<body>` 를 그리고,
 * Providers(쿼리·인증)에 기대지 않게 `Link` 와 일반 `<button>` 만 쓴다. 원문 오류는 콘솔에만 남긴다(TASK-28 B2).
 */
export default function GlobalError({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <html lang="ko" className="h-full antialiased">
      <body className="flex min-h-full flex-col">
        <title>오류 - 게시판</title>
        <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col items-start gap-4 px-4 py-10">
          <h1 className="text-2xl font-semibold">문제가 생겼습니다</h1>
          <p role="alert" className="text-sm">
            화면을 불러오지 못했습니다. 잠시 뒤 다시 시도하세요. 계속되면 새로 고침하거나 처음 화면으로 가 주세요.
          </p>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => retry()}
              className="rounded-lg border px-3 py-1.5 text-sm font-medium"
            >
              다시 시도
            </button>
            <Link href="/" className="text-sm font-medium underline underline-offset-4">
              처음 화면으로
            </Link>
          </div>
        </main>
      </body>
    </html>
  );
}
