"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { loginHref, useMe } from "@/lib/auth";

function Loading() {
  return (
    <p role="status" className="text-muted-foreground text-sm">
      불러오는 중…
    </p>
  );
}

/** 로그인이 필요한 화면. 익명이면 `/login/?next=<현재 경로>` 로 보낸다(정적 export 라 클라이언트에서 판단). */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { data: me, isPending, isError } = useMe();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (me === null) router.replace(loginHref(pathname));
  }, [me, router, pathname]);

  if (isError) return <LoadError />;
  if (isPending || !me) return <Loading />;
  return <>{children}</>;
}

/** 관리자 화면. 익명은 로그인으로, USER 는 권한 없음 안내. (서버도 /api/admin/** 를 ADMIN 만 허용한다.) */
export function RequireAdmin({ children }: { children: ReactNode }) {
  return (
    <RequireAuth>
      <AdminOnly>{children}</AdminOnly>
    </RequireAuth>
  );
}

function AdminOnly({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  if (me?.role !== "ADMIN") {
    return (
      <Alert variant="destructive">
        <AlertTitle>권한이 없습니다</AlertTitle>
        <AlertDescription>관리자만 볼 수 있는 화면입니다.</AlertDescription>
      </Alert>
    );
  }
  return <>{children}</>;
}

function LoadError() {
  return (
    <Alert variant="destructive">
      <AlertTitle>사용자 정보를 불러오지 못했습니다</AlertTitle>
      <AlertDescription>잠시 후 다시 시도하세요.</AlertDescription>
    </Alert>
  );
}
