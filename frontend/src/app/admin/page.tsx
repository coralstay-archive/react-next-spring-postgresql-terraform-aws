import type { Metadata } from "next";
import { Suspense } from "react";

import { RequireAdmin } from "@/components/guards";

import { AdminView } from "./admin-view";

export const metadata: Metadata = { title: "관리자 | 게시판" };

/** 관리자 화면(TASK-15). 탭 상태를 `useSearchParams` 로 읽으므로 Suspense 로 감싼다(정적 export). 실제 차단은 서버가 한다. */
export default function AdminPage() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 px-4 py-8">
      <Suspense fallback={<p role="status">불러오는 중…</p>}>
        <RequireAdmin>
          <AdminView />
        </RequireAdmin>
      </Suspense>
    </main>
  );
}
