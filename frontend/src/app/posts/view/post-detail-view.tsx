"use client";

import { Eye, Heart } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";

import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { CommentSection } from "@/components/board/comment-section";
import { PostActions } from "@/components/board/post-actions";
import { PostFlags } from "@/components/board/post-flags";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { useCurrentPath } from "@/hooks/use-current-path";
import { isApiError } from "@/lib/api";
import { loginHref, useMe } from "@/lib/auth";
import { formatDate, parsePostId, usePost, useToggleLike, type PostDetail } from "@/lib/board";
import { listHref } from "@/lib/board-query";

/** 글 상세 `/posts/view/?id=` (TASK-14#2). 정적 export 라 동적 세그먼트 대신 쿼리 파라미터를 쓴다. */
export function PostDetailView() {
  const id = parsePostId(useSearchParams().get("id"));
  if (id === null) return <PostNotFound />;
  return <PostDetailById key={id} id={id} />;
}

export function PostNotFound() {
  return (
    <div className="flex flex-col items-start gap-4" data-testid="post-not-found">
      <p>글을 찾을 수 없습니다. 삭제되었거나 볼 수 없는 글입니다.</p>
      <Link href="/" className={buttonVariants({ variant: "outline" })}>
        목록으로
      </Link>
    </div>
  );
}

function PostDetailById({ id }: { id: number }) {
  const post = usePost(id);
  // 세션 만료 리셋(TASK-29#1)으로 데이터가 비고 다시 받는 동안에도 글을 그대로 두어 댓글 폼(입력·오류)이 언마운트되지 않게 한다
  // (TASK-29#4). 그동안은 익명으로 보고 수정·삭제·좋아요 상태를 끈다(B2).
  const [last, setLast] = useState<PostDetail | null>(null);
  if (post.data && post.data !== last) setLast(post.data);
  const shown = post.data ?? (post.isPending && last ? { ...last, editable: false, likedByMe: false } : undefined);

  if (shown) {
    return (
      <article className="flex w-full flex-col gap-6">
        <PostBody post={shown} />
        <CommentSection postId={id} />
      </article>
    );
  }
  if (post.isError) {
    if (isApiError(post.error) && post.error.status === 404) return <PostNotFound />;
    return <ApiErrorAlert error={post.error} testId="post-error" />;
  }
  return (
    <p role="status" className="text-muted-foreground text-sm">
      불러오는 중…
    </p>
  );
}

function PostBody({ post }: { post: PostDetail }) {
  return (
    <>
      <header className="flex flex-col gap-2 border-b pb-4">
        <div className="flex flex-wrap items-center gap-2">
          {post.category && (
            <Link href={listHref({ category: post.category.slug, sort: "latest", page: 1 })}>
              <Badge variant="secondary">{post.category.name}</Badge>
            </Link>
          )}
          <PostFlags deleted={post.deleted} hidden={post.hidden} />
          <div className="ml-auto">
            <PostActions post={post} />
          </div>
        </div>
        <h1 className="text-2xl font-semibold tracking-tight break-words" data-testid="post-title">
          {post.title}
        </h1>
        <div className="text-muted-foreground flex flex-wrap items-center gap-3 text-sm">
          <span data-testid="post-author">{post.author.nickname}</span>
          <time dateTime={post.createdAt}>{formatDate(post.createdAt)}</time>
          <span className="flex items-center gap-1" data-testid="view-count" aria-label={`조회 ${post.viewCount}`}>
            <Eye className="size-4" aria-hidden /> {post.viewCount}
          </span>
        </div>
      </header>

      {/* 본문은 텍스트 노드로만 넣는다(HTML 해석 없음 = XSS 없음). 줄바꿈은 CSS 로 보존한다. */}
      <div className="leading-relaxed break-words whitespace-pre-wrap" data-testid="post-content">
        {post.content}
      </div>

      {post.attachments.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2" data-testid="post-attachments">
          {post.attachments.map((attachment, index) => (
            <a key={attachment.id} href={attachment.url} target="_blank" rel="noopener noreferrer">
              {/* 정적 export(images.unoptimized)라 next/image 최적화가 없다. 크기를 모르는 사용자 이미지라 img 를 쓴다. */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={attachment.url}
                alt={`첨부 이미지 ${index + 1}`}
                loading="lazy"
                className="max-h-[480px] w-full rounded-lg border object-contain"
              />
            </a>
          ))}
        </div>
      )}

      {post.tags.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {post.tags.map((tag) => (
            <Link key={tag} href={listHref({ tag, sort: "latest", page: 1 })}>
              <Badge variant="outline">#{tag}</Badge>
            </Link>
          ))}
        </div>
      )}

      <LikeButton post={post} />
    </>
  );
}

function LikeButton({ post }: { post: PostDetail }) {
  const { data: me } = useMe();
  const router = useRouter();
  const current = useCurrentPath();
  const toggle = useToggleLike(post.id);

  const click = () => {
    if (me === null) {
      router.push(loginHref(current));
      return;
    }
    toggle.mutate(!post.likedByMe);
  };

  return (
    <div className="flex flex-col items-start gap-2">
      <Button
        variant={post.likedByMe ? "default" : "outline"}
        aria-pressed={post.likedByMe}
        aria-label="좋아요"
        data-testid="like-button"
        disabled={me === undefined || toggle.isPending}
        onClick={click}
      >
        <Heart className={post.likedByMe ? "fill-current" : undefined} aria-hidden />
        <span data-testid="like-count">{post.likeCount}</span>
      </Button>
      {toggle.isError && <ApiErrorAlert error={toggle.error} testId="like-error" />}
    </div>
  );
}
