"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";

import { Button, buttonVariants } from "@/components/ui/button";
import { useLogout, useMe } from "@/lib/auth";

export function SiteHeader() {
  const { data: me } = useMe();
  const logout = useLogout();
  const router = useRouter();

  return (
    <header className="border-b">
      <nav aria-label="주 메뉴" className="mx-auto flex h-14 w-full max-w-5xl items-center gap-4 px-4">
        <Link href="/" className="font-semibold tracking-tight">
          게시판
        </Link>
        <div className="ml-auto flex items-center gap-2 text-sm">
          {me === undefined ? null : me ? (
            <>
              <span className="text-muted-foreground" aria-label="로그인한 사용자">
                {me.nickname}
              </span>
              {me.role === "ADMIN" && (
                <Link href="/admin/" className={buttonVariants({ variant: "ghost", size: "sm" })}>
                  관리자
                </Link>
              )}
              <Link href="/account/" className={buttonVariants({ variant: "ghost", size: "sm" })}>
                내 계정
              </Link>
              <Button
                variant="outline"
                size="sm"
                disabled={logout.isPending}
                onClick={() => logout.mutate(undefined, { onSettled: () => router.push("/") })}
              >
                로그아웃
              </Button>
            </>
          ) : (
            <>
              <Link href="/login/" className={buttonVariants({ variant: "ghost", size: "sm" })}>
                로그인
              </Link>
              <Link href="/signup/" className={buttonVariants({ variant: "outline", size: "sm" })}>
                가입
              </Link>
            </>
          )}
        </div>
      </nav>
    </header>
  );
}
