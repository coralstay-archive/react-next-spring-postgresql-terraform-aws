"use client";

import { useSearchParams } from "next/navigation";

import { PostNotFound } from "@/app/posts/view/post-detail-view";
import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { PostEditor } from "@/components/board/post-editor";
import { isApiError } from "@/lib/api";
import { parsePostId, usePost } from "@/lib/board";

/** `/posts/edit/?id=` (TASK-14#3). 상세를 받아 폼을 채운다. */
export function PostEditView() {
  const id = parsePostId(useSearchParams().get("id"));
  if (id === null) return <PostNotFound />;
  return <EditById key={id} id={id} />;
}

function EditById({ id }: { id: number }) {
  const post = usePost(id);
  if (post.isError) {
    if (isApiError(post.error) && post.error.status === 404) return <PostNotFound />;
    return <ApiErrorAlert error={post.error} testId="post-error" />;
  }
  if (post.isPending) {
    return (
      <p role="status" className="text-muted-foreground text-sm">
        불러오는 중…
      </p>
    );
  }
  return <PostEditor post={post.data} />;
}
