"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { Button } from "@/components/ui/button";
import { useCurrentPath } from "@/hooks/use-current-path";
import { loginHref, useMe } from "@/lib/auth";
import {
  COMMENT_PAGE_SIZE,
  formatDate,
  useComments,
  useCreateComment,
  useDeleteComment,
  type Comment,
} from "@/lib/board";
import { COMMENT_MAX, validateComment } from "@/lib/board-validation";

/**
 * 댓글 목록·작성·삭제 (TASK-14#2). 목록은 오래된 순이고 삭제된 댓글은 자리표시(`deleted:true`)로 남는다.
 * 삭제 버튼은 서버가 준 `editable`(작성자 또는 ADMIN)일 때만 보인다.
 */
export function CommentSection({ postId }: { postId: number }) {
  const [page, setPage] = useState(0);
  const comments = useComments(postId, page);
  const remove = useDeleteComment(postId);
  const total = comments.data?.totalElements ?? 0;
  const totalPages = comments.data?.totalPages ?? 0;

  const onDelete = (comment: Comment) => {
    if (!window.confirm("댓글을 삭제할까요?")) return;
    remove.mutate(comment.id);
  };

  return (
    <section aria-labelledby="comments-heading" className="flex flex-col gap-4 border-t pt-6">
      <h2 id="comments-heading" className="text-lg font-semibold">
        댓글 {comments.data ? <span className="text-muted-foreground">{total}</span> : null}
      </h2>

      {comments.isError ? (
        <ApiErrorAlert error={comments.error} testId="comments-error" />
      ) : comments.isPending ? (
        <p role="status" className="text-muted-foreground text-sm">
          불러오는 중…
        </p>
      ) : comments.data.items.length === 0 ? (
        <p className="text-muted-foreground text-sm">첫 댓글을 남겨 보세요.</p>
      ) : (
        <ul className="flex flex-col divide-y" data-testid="comment-list">
          {comments.data.items.map((comment) => (
            <CommentItem
              key={comment.id}
              comment={comment}
              deleting={remove.isPending && remove.variables === comment.id}
              onDelete={() => onDelete(comment)}
            />
          ))}
        </ul>
      )}

      {remove.isError && <ApiErrorAlert error={remove.error} testId="comment-error" />}

      {totalPages > 1 && (
        <nav aria-label="댓글 페이지" className="flex items-center gap-3 text-sm">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            이전
          </Button>
          <span>
            {page + 1} / {totalPages}
          </span>
          <Button variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>
            다음
          </Button>
        </nav>
      )}

      {/* 새 댓글은 맨 뒤에 붙으므로 작성 뒤 마지막 쪽으로 간다. */}
      <CommentForm postId={postId} onCreated={() => setPage(Math.floor(total / COMMENT_PAGE_SIZE))} />
    </section>
  );
}

function CommentItem({ comment, deleting, onDelete }: { comment: Comment; deleting: boolean; onDelete: () => void }) {
  if (comment.deleted) {
    return (
      <li className="text-muted-foreground py-3 text-sm italic" data-testid="comment-item">
        삭제된 댓글입니다
      </li>
    );
  }
  return (
    <li className="flex flex-col gap-1 py-3" data-testid="comment-item">
      <div className="text-muted-foreground flex items-center gap-2 text-xs">
        <span className="text-foreground font-medium">{comment.author?.nickname}</span>
        <time dateTime={comment.createdAt}>{formatDate(comment.createdAt)}</time>
        {comment.editable && (
          <Button
            variant="ghost"
            size="xs"
            className="ml-auto"
            aria-label="댓글 삭제"
            data-testid="comment-delete"
            disabled={deleting}
            onClick={onDelete}
          >
            삭제
          </Button>
        )}
      </div>
      <p className="text-sm break-words whitespace-pre-wrap">{comment.content}</p>
    </li>
  );
}

function CommentForm({ postId, onCreated }: { postId: number; onCreated: () => void }) {
  const { data: me } = useMe();
  const current = useCurrentPath();
  const create = useCreateComment(postId);
  const [content, setContent] = useState("");
  const [error, setError] = useState<string | null>(null);

  if (me === undefined) return null;
  if (me === null) {
    return (
      <p className="text-sm">
        <Link href={loginHref(current)} className="font-medium underline underline-offset-4">
          로그인하고 댓글 쓰기
        </Link>
      </p>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const invalid = validateComment(content);
    setError(invalid);
    if (invalid) return;
    create.mutate(content, {
      onSuccess: () => {
        setContent("");
        onCreated();
      },
    });
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-2" aria-label="댓글 쓰기">
      <textarea
        aria-label="댓글"
        data-testid="comment-input"
        className="border-input min-h-20 rounded-lg border bg-transparent px-2.5 py-2 text-sm"
        maxLength={COMMENT_MAX}
        value={content}
        onChange={(e) => setContent(e.target.value)}
        aria-invalid={error ? true : undefined}
      />
      {error && <p className="text-destructive text-sm">{error}</p>}
      {create.isError && <ApiErrorAlert error={create.error} testId="comment-create-error" />}
      <div>
        <Button type="submit" data-testid="comment-submit" disabled={create.isPending}>
          댓글 등록
        </Button>
      </div>
    </form>
  );
}
