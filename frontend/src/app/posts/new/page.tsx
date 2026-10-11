import type { Metadata } from "next";
import { Suspense } from "react";

import { PostEditor } from "@/components/board/post-editor";
import { RequireAuth } from "@/components/guards";

export const metadata: Metadata = { title: "글쓰기 | 게시판" };

/** `/posts/new/` (TASK-14#3). */
export default function PostNewPage() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 px-4 py-8">
      <Suspense fallback={<p role="status">불러오는 중…</p>}>
        <RequireAuth>
          <PostEditor />
        </RequireAuth>
      </Suspense>
    </main>
  );
}
