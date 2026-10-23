"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { StepUpNotice } from "@/components/step-up-notice";
import { Button, buttonVariants } from "@/components/ui/button";
import { useMe } from "@/lib/auth";
import { useDeletePost, type PostDetail } from "@/lib/board";

/**
 * 수정·삭제 버튼 (TASK-14#4). 서버가 준 `editable`(작성자 또는 ADMIN, 삭제되지 않은 글)일 때만 그린다. 화면에서 역할·작성자를
 * 다시 계산하지 않는다. 서버가 403·404 를 주면 안내하고 머물며, 상세를 다시 받아 없는 글 안내·버튼 숨김으로 맞춘다(TASK-31#2).
 * 제한 세션이면 삭제를 눌러도 확인 창 대신 본인 확인 안내만 보이고 요청하지 않는다(TASK-32#3).
 */
export function PostActions({ post }: { post: PostDetail }) {
  const router = useRouter();
  const remove = useDeletePost(post.id);
  const { data: me } = useMe();
  const [stepUpShown, setStepUpShown] = useState(false);
  if (!post.editable) return null;

  const onDelete = () => {
    if (me?.stepUpRequired) {
      setStepUpShown(true);
      return;
    }
    if (!window.confirm("글을 삭제할까요?")) return;
    // 삭제한 글은 히스토리에서도 빼고(replace) 버튼은 이동할 때까지 비활성으로 둔다(TASK-31#2, FE-21).
    remove.mutate(undefined, { onSuccess: () => router.replace("/") });
  };

  return (
    <div className="flex flex-col items-end gap-2">
      <div className="flex gap-2">
        <Link
          href={`/posts/edit/?id=${post.id}`}
          className={buttonVariants({ variant: "outline", size: "sm" })}
          data-testid="post-edit-link"
        >
          수정
        </Link>
        <Button
          variant="destructive"
          size="sm"
          data-testid="post-delete-button"
          disabled={remove.isPending || remove.isSuccess}
          onClick={onDelete}
        >
          삭제
        </Button>
      </div>
      {stepUpShown && me?.stepUpRequired && (
        <StepUpNotice testId="post-delete-step-up">다시 인증한 뒤에 글을 삭제할 수 있습니다.</StepUpNotice>
      )}
      {remove.isError && <ApiErrorAlert error={remove.error} testId="post-delete-error" />}
    </div>
  );
}
