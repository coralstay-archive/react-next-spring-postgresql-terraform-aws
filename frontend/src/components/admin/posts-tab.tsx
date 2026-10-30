"use client";

import Link from "next/link";
import { useState } from "react";

import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { Pager } from "@/components/admin/pager";
import { ApiErrorAlert, RefetchErrorAlert } from "@/components/board/api-error-alert";
import { PostFlags } from "@/components/board/post-flags";
import { Button } from "@/components/ui/button";
import { usePostModeration, type PostModeration } from "@/lib/admin";
import { formatDate, usePostList, type PostSummary } from "@/lib/board";

const ACTIONS: Record<PostModeration, { label: string; title: string; description: string }> = {
  hide: { label: "숨김", title: "글을 숨길까요?", description: "숨긴 글은 관리자에게만 보입니다." },
  unhide: { label: "숨김 해제", title: "숨김을 해제할까요?", description: "다시 모두에게 보입니다(삭제된 글이 아니라면)." },
  restore: {
    label: "삭제 복구",
    title: "삭제된 글을 복구할까요?",
    description: "삭제 표시만 지웁니다. 숨김 상태는 그대로 둡니다.",
  },
};

type Pending = { post: PostSummary; action: PostModeration };

/**
 * 글 탭 (TASK-15#1). 게시판 목록 API 를 `includeHidden=true` 로 읽어(ADMIN 만 숨김·삭제 글이 온다) 숨김·숨김 해제·삭제 복구를 한다.
 * 목록 훅·쿼리 키는 게시판(TASK-14)과 같아서 처리 뒤 `["posts"]` 무효화로 게시판 화면도 따라간다.
 */
export function PostsTab() {
  const [page, setPage] = useState(1);
  const [pending, setPending] = useState<Pending | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogKey, setDialogKey] = useState(0);
  const list = usePostList({ sort: "latest", page, includeHidden: true });
  const mutation = usePostModeration();

  const open = (post: PostSummary, action: PostModeration) => {
    mutation.reset();
    setDialogKey((k) => k + 1);
    setPending({ post, action });
    setDialogOpen(true);
  };

  const confirm = (reason: string) => {
    if (!pending) return;
    mutation.mutate(
      { id: pending.post.id, action: pending.action, reason },
      { onSuccess: () => setDialogOpen(false) },
    );
  };

  const meta = pending ? ACTIONS[pending.action] : ACTIONS.hide;

  return (
    <div className="flex flex-col gap-4">
      {list.isError && list.data && (
        <RefetchErrorAlert
          error={list.error}
          retrying={list.isFetching}
          onRetry={() => void list.refetch()}
          testId="admin-posts-refetch-error"
        />
      )}
      {list.isError && !list.data ? (
        <ApiErrorAlert error={list.error} testId="admin-posts-error" />
      ) : list.isPending ? (
        <p role="status" className="text-muted-foreground text-sm">
          불러오는 중…
        </p>
      ) : list.data.items.length === 0 ? (
        <p className="text-muted-foreground py-8 text-center text-sm" data-testid="admin-posts-empty">
          글이 없습니다.
        </p>
      ) : (
        <ul className="divide-y rounded-lg border" data-testid="admin-post-list">
          {list.data.items.map((post) => (
            <li
              key={post.id}
              className="flex flex-wrap items-center gap-3 px-4 py-3"
              data-testid="admin-post-row"
              data-post-id={post.id}
            >
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <div className="flex flex-wrap items-center gap-2">
                  <Link
                    href={`/posts/view/?id=${post.id}`}
                    className="truncate font-medium hover:underline"
                    data-testid="admin-post-link"
                  >
                    {post.title}
                  </Link>
                  <PostFlags deleted={post.deleted} hidden={post.hidden} />
                </div>
                <p className="text-muted-foreground text-xs">
                  #{post.id} · {post.author.nickname} · {post.category?.name ?? "카테고리 없음"} ·{" "}
                  {formatDate(post.createdAt)}
                </p>
              </div>
              <div className="flex gap-2">
                {post.hidden ? (
                  <Button size="sm" variant="outline" data-testid="admin-post-unhide" onClick={() => open(post, "unhide")}>
                    {ACTIONS.unhide.label}
                  </Button>
                ) : (
                  <Button size="sm" variant="outline" data-testid="admin-post-hide" onClick={() => open(post, "hide")}>
                    {ACTIONS.hide.label}
                  </Button>
                )}
                {post.deleted && (
                  <Button size="sm" variant="outline" data-testid="admin-post-restore" onClick={() => open(post, "restore")}>
                    {ACTIONS.restore.label}
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {list.data && (
        <Pager
          page={page - 1}
          totalPages={list.data.totalPages}
          onChange={(p) => setPage(p + 1)}
          testId="admin-posts"
        />
      )}

      <ConfirmDialog
        key={`dialog-${dialogKey}`}
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        title={meta.title}
        description={`"${pending?.post.title ?? ""}"(#${pending?.post.id ?? ""}) — ${meta.description}`}
        confirmLabel={meta.label}
        destructive={pending?.action === "hide"}
        withReason
        pending={mutation.isPending}
        error={mutation.error}
        onConfirm={confirm}
      />
    </div>
  );
}
