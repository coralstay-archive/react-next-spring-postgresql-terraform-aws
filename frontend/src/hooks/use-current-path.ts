"use client";

import { usePathname, useSearchParams } from "next/navigation";

/** 현재 경로 + 쿼리(로그인 뒤 돌아올 `next`). `useSearchParams` 를 쓰므로 `<Suspense>` 안에서만 쓴다. */
export function useCurrentPath(): string {
  const pathname = usePathname();
  const search = useSearchParams().toString();
  return search ? `${pathname}?${search}` : pathname;
}
