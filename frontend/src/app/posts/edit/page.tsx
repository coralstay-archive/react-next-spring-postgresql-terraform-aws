import type { Metadata } from "next";
import { Suspense } from "react";

import { RequireAuth } from "@/components/guards";

import { PostEditView } from "./post-edit-view";

export const metadata: Metadata = { title: "글 수정 | 게시판" };

/** `/posts/edit/?id=<글 id>` (TASK-14#3). */
export default function PostEditPage() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 px-4 py-8">
      <Suspense fallback={<p role="status">불러오는 중…</p>}>
        <RequireAuth>
          <PostEditView />
        </RequireAuth>
      </Suspense>
    </main>
  );
}
