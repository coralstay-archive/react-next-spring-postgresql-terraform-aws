import type { Metadata } from "next";
import { Suspense } from "react";

import { LoginView } from "./login-view";

export const metadata: Metadata = { title: "로그인 | 게시판" };

// 정적 export: 검색 파라미터(next, error)는 클라이언트에서 읽으므로 Suspense 경계 안에 둔다.
export default function LoginPage() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 justify-center px-4 py-12">
      <Suspense fallback={<p role="status">불러오는 중…</p>}>
        <LoginView />
      </Suspense>
    </main>
  );
}
