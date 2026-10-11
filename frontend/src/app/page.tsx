import { Suspense } from "react";

import { PostListView } from "./post-list-view";

/** 홈 = 게시판 목록(TASK-14#1). 목록 상태는 URL 쿼리에 있으므로 `useSearchParams` 를 쓰는 뷰를 Suspense 로 감싼다(정적 export). */
export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 px-4 py-8">
      <Suspense fallback={<p role="status">불러오는 중…</p>}>
        <PostListView />
      </Suspense>
    </main>
  );
}
