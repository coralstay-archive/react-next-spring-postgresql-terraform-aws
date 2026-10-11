import type { Metadata } from "next";
import { Suspense } from "react";

import { RequireAuth } from "@/components/guards";

import { AccountView } from "./account-view";

export const metadata: Metadata = { title: "내 계정 | 게시판" };

export default function AccountPage() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 justify-center px-4 py-12">
      <Suspense fallback={<p role="status">불러오는 중…</p>}>
        <RequireAuth>
          <AccountView />
        </RequireAuth>
      </Suspense>
    </main>
  );
}
