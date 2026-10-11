import type { Metadata } from "next";
import { Suspense } from "react";

import { SignupView } from "./signup-view";

export const metadata: Metadata = { title: "가입 | 게시판" };

export default function SignupPage() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 justify-center px-4 py-12">
      <Suspense fallback={<p role="status">불러오는 중…</p>}>
        <SignupView />
      </Suspense>
    </main>
  );
}
