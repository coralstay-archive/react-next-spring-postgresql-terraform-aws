"use client";

import { useRouter } from "next/navigation";
import { Fragment, useEffect, type ReactNode } from "react";

import Link from "next/link";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useCurrentPath } from "@/hooks/use-current-path";
import { loginHref, useAuthFlag, useSessionUser } from "@/lib/auth";
import { LOGGED_OUT_KEY } from "@/lib/query";

function Loading() {
  return (
    <p role="status" className="text-muted-foreground text-sm">
      불러오는 중…
    </p>
  );
}

/**
 * 로그인이 필요한 화면. 익명이면 `/login/?next=<현재 경로+쿼리>` 로 보낸다(정적 export 라 클라이언트에서 판단).
 * 쿼리(`/posts/edit/?id=3`)까지 담으려고 `useSearchParams` 를 쓰므로 `<Suspense>` 안에서 쓴다.
 *
 * 내용을 한 번 그린 뒤 세션이 끝나면(TASK-29#3, FE-9·FE-32) 로그인으로 보내지 않고 내용을 그대로 둔 채 위에 재로그인 안내를 붙인다 —
 * 작성 중인 입력·첨부·다이얼로그가 언마운트되어 사라지지 않게. 입력은 초안으로도 남는다(lib/drafts.ts).
 * 사용자가 다른 계정으로 바뀌면 내용을 다시 마운트한다(A 의 입력이 B 화면·B 초안으로 넘어가지 않게).
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { data: me, user, isError, isFetching, refetch, expired } = useSessionUser();
  const router = useRouter();
  const current = useCurrentPath();
  const loggedOut = useAuthFlag(LOGGED_OUT_KEY);

  useEffect(() => {
    if (me === null && !expired) router.replace(loginHref(current));
  }, [me, expired, router, current]);

  // 명시적 로그아웃(TASK-29#5): 초안은 이미 지웠고 곧 `/` 로 간다. "임시 저장되어 있습니다" 안내는 틀린 말이라 내용째 비운다.
  if (loggedOut && me === null) return null;

  if (!expired) {
    if (me === undefined && isError) return <LoadError retrying={isFetching} onRetry={() => void refetch()} />;
    if (!me) return <Loading />;
  }
  // 만료 전후로 트리 모양을 같게 둔다(children 위치가 바뀌면 다시 마운트되어 입력이 사라진다).
  // 다만 계정이 A→B 로 바뀌면(TASK-29#1·#3) 사용자 id key 로 일부러 다시 마운트해 A 의 입력·상태를 버리고 빈 화면으로 연다.
  return (
    <>
      {expired ? <SessionLostNotice /> : null}
      <Fragment key={user?.id}>{children}</Fragment>
    </>
  );
}

/**
 * 작성 화면의 인라인 재로그인 안내(TASK-29#3). 다시 로그인하면 지금 경로로 돌아오고 초안을 복원한다.
 * 화면 아래에 고정해 보인다(레이아웃 흐름을 바꾸지 않고, 길게 쓰는 중에도 보이게).
 */
export function SessionLostNotice() {
  const current = useCurrentPath();
  return (
    <Alert
      variant="destructive"
      data-testid="session-lost-notice"
      className="bg-background fixed inset-x-0 bottom-4 z-50 mx-auto w-[calc(100%-2rem)] max-w-xl shadow-lg"
    >
      <AlertTitle>로그인이 끊겼습니다</AlertTitle>
      <AlertDescription>
        <p>
          작성 중인 내용은 이 화면과 이 탭에 임시 저장되어 있습니다. 다시 로그인한 뒤 이 화면으로 돌아오면 이어서 쓸 수 있습니다.
        </p>
        <Link href={loginHref(current)} className="font-medium underline underline-offset-4">
          다시 로그인
        </Link>
      </AlertDescription>
    </Alert>
  );
}

/**
 * 관리자 화면(TASK-15#2). 익명은 로그인으로, USER 는 403 권한 없음 안내(관리 API 는 부르지 않는다).
 * 화면 차단은 안내일 뿐이고 실제 차단은 서버가 한다(/api/admin/** 는 ADMIN 만).
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  return (
    <RequireAuth>
      <AdminOnly>{children}</AdminOnly>
    </RequireAuth>
  );
}

function AdminOnly({ children }: { children: ReactNode }) {
  // 세션이 끝나도 마지막 사용자로 판단한다(TASK-29#3, FE-32: 관리 다이얼로그·사유 유지).
  const { user } = useSessionUser();
  if (user?.role !== "ADMIN") {
    return (
      <Alert variant="destructive" data-testid="admin-forbidden">
        <AlertTitle>권한이 없습니다 (403)</AlertTitle>
        <AlertDescription>관리자만 볼 수 있는 화면입니다.</AlertDescription>
      </Alert>
    );
  }
  return <>{children}</>;
}

/** me 를 못 받았을 때(네트워크·5xx). 로그인으로 보내지 않고 다시 시도를 준다(TASK-29#6, FE-11). */
function LoadError({ retrying, onRetry }: { retrying: boolean; onRetry: () => void }) {
  return (
    <Alert variant="destructive">
      <AlertTitle>사용자 정보를 불러오지 못했습니다</AlertTitle>
      <AlertDescription>
        <p>잠시 후 다시 시도하세요.</p>
        <Button variant="outline" size="sm" disabled={retrying} onClick={onRetry}>
          다시 시도
        </Button>
      </AlertDescription>
    </Alert>
  );
}
