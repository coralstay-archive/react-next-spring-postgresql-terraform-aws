"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";

import { PostNotFound } from "@/app/posts/view/post-detail-view";
import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { PostEditor } from "@/components/board/post-editor";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { isApiError } from "@/lib/api";
import { parsePostId, usePost, type PostDetail } from "@/lib/board";

/** `/posts/edit/?id=` (TASK-14#3). 상세를 받아 폼을 채운다. */
export function PostEditView() {
  const id = parsePostId(useSearchParams().get("id"));
  if (id === null) return <PostNotFound />;
  return <EditById key={id} id={id} />;
}

function EditById({ id }: { id: number }) {
  const post = usePost(id);
  // 처음 받은 editable 글로 편집기를 연 뒤에는 그 글을 계속 쓴다(TASK-29#3). 세션 만료 리셋 뒤 익명으로 다시 받은 상세
  // (editable=false·로딩)로 바뀌어 편집기가 언마운트되면 입력이 사라진다. 저장 때 서버가 다시 401·403·404 로 판단한다.
  const [opened, setOpened] = useState<PostDetail | null>(null);
  if (opened === null && post.data?.editable) setOpened(post.data);
  if (opened) return <PostEditor post={opened} />;
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
  // 수정 권한은 서버의 editable 로만 판단한다(TASK-14#4). 저장 때도 서버가 다시 403·404 로 막는다.
  if (!post.data.editable) {
    return (
      <Alert variant="destructive" data-testid="post-forbidden">
        <AlertDescription>수정할 권한이 없습니다. 작성자나 관리자만 고칠 수 있고, 삭제된 글은 고칠 수 없습니다.</AlertDescription>
      </Alert>
    );
  }
  return <PostEditor post={post.data} />;
}
