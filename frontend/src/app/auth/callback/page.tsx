import type { Metadata } from "next";
import { Suspense } from "react";

import { AuthCallbackView } from "./callback-view";

export const metadata: Metadata = { title: "로그인 처리 | 게시판" };

export default function AuthCallbackPage() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 justify-center px-4 py-12">
      <Suspense fallback={<p role="status">로그인 처리 중…</p>}>
        <AuthCallbackView />
      </Suspense>
    </main>
  );
}
