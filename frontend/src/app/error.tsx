"use client";

import Link from "next/link";
import { useEffect } from "react";

import { Button } from "@/components/ui/button";

/**
 * 화면 오류 경계(TASK-34#1, FE-34). 루트 레이아웃(헤더·배너) 안에서 페이지가 렌더 중에 던진 오류를 받는다.
 * 원문 `error.message` 는 내부 정보(경로·변수 이름)가 섞일 수 있어 화면에 보이지 않고 콘솔에만 남긴다(TASK-28 B2).
 * "다시 시도"는 Next 16 의 `retry`(다시 받아 그리기)를 부른다.
 */
export default function ErrorPage({
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
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col items-start gap-4 px-4 py-10">
      <h1 className="text-2xl font-semibold">문제가 생겼습니다</h1>
      <p role="alert" className="text-muted-foreground text-sm">
        화면을 보여 주는 중에 오류가 났습니다. 잠시 뒤 다시 시도하세요. 계속되면 목록으로 돌아가 다시 들어와 주세요.
      </p>
      <div className="flex items-center gap-3">
        <Button type="button" onClick={() => retry()}>
          다시 시도
        </Button>
        <Link href="/" className="text-sm font-medium underline underline-offset-4">
          목록으로
        </Link>
      </div>
    </main>
  );
}
