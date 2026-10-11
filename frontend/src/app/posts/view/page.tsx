import type { Metadata } from "next";
import { Suspense } from "react";

import { PostDetailView } from "./post-detail-view";

export const metadata: Metadata = { title: "글 | 게시판" };

/** `/posts/view/?id=<글 id>` (TASK-14#2). 정적 export 는 동적 세그먼트를 미리 만들 수 없어 쿼리 파라미터로 받는다. */
export default function PostViewPage() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 px-4 py-8">
      <Suspense fallback={<p role="status">불러오는 중…</p>}>
        <PostDetailView />
      </Suspense>
    </main>
  );
}
