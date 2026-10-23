"use client";

import { Eye, Heart } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";

import { ApiErrorAlert, RefetchErrorAlert } from "@/components/board/api-error-alert";
import { PostFlags } from "@/components/board/post-flags";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useMe } from "@/lib/auth";
import { formatDate, useCategories, usePopularTags, usePostList, type PostSummary } from "@/lib/board";
import {
  listHref,
  parseListFilters,
  validateSearch,
  withFilters,
  type ListFilters,
  type PostSort,
} from "@/lib/board-query";
import { cn } from "@/lib/utils";

/**
 * 게시판 목록 `/` (TASK-14#1). 카테고리 탭·인기 태그·검색·정렬·페이지는 모두 URL 쿼리에 있다({@link parseListFilters}).
 * 탭·태그·페이지는 링크, 검색·정렬·숨김 토글은 `router.push` 로 URL 을 바꾼다.
 */
export function PostListView() {
  const params = useSearchParams();
  const router = useRouter();
  const filters = useMemo(() => parseListFilters(params), [params]);
  const { data: me } = useMe();
  const isAdmin = me?.role === "ADMIN";
  const list = usePostList(filters);
  const searchError = validateSearch(filters.q).error;

  const go = (patch: Partial<ListFilters>) => router.push(listHref(withFilters(filters, patch)));

  // 범위 밖 page(TASK-33#2, FE-22): 응답의 totalPages 를 알면 마지막 쪽(0건이면 1쪽)으로 URL 을 바로잡는다. 뒤로 가기에 남지 않게 replace.
  const totalPages = list.data?.totalPages;
  const outOfRange = totalPages !== undefined && filters.page > Math.max(totalPages, 1);
  useEffect(() => {
    if (outOfRange) router.replace(listHref(withFilters(filters, { page: Math.max(totalPages, 1) })));
  }, [outOfRange, totalPages, filters, router]);

  return (
    <div className="flex w-full flex-col gap-6">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">게시판</h1>
        <Link href="/posts/new/" className={cn(buttonVariants(), "ml-auto")} data-testid="new-post-link">
          글쓰기
        </Link>
      </div>

      <CategoryTabs filters={filters} />

      <div className="flex flex-wrap items-start gap-3">
        <SearchForm key={filters.q ?? ""} filters={filters} onSearch={(q) => go({ q })} />
        <label className="flex items-center gap-2 text-sm">
          <span className="sr-only">정렬</span>
          <select
            aria-label="정렬"
            data-testid="sort-select"
            className="border-input h-8 rounded-lg border bg-transparent px-2 text-sm"
            value={filters.sort}
            onChange={(e) => go({ sort: e.target.value as PostSort })}
          >
            <option value="latest">최신순</option>
            <option value="popular">인기순</option>
          </select>
        </label>
        {isAdmin && (
          <label className="flex h-8 items-center gap-2 text-sm">
            <input
              type="checkbox"
              data-testid="include-hidden-toggle"
              checked={!!filters.includeHidden}
              onChange={(e) => go({ includeHidden: e.target.checked ? true : undefined })}
            />
            숨김·삭제 글 포함
          </label>
        )}
      </div>

      {searchError && (
        <p className="text-destructive text-sm" data-testid="search-error">
          <span className="font-medium">검색어 무시:</span> <span>{searchError}</span> 검색어 없이 목록을 보여 줍니다.
        </p>
      )}

      <TagChips filters={filters} />

      {list.isError && list.data && (
        <RefetchErrorAlert
          error={list.error}
          retrying={list.isFetching}
          onRetry={() => void list.refetch()}
          testId="post-list-refetch-error"
        />
      )}
      {list.isError && !list.data ? (
        <div className="flex flex-col items-start gap-2">
          <ApiErrorAlert error={list.error} testId="post-list-error" />
          {/* 필터·쪽 때문에 실패했을 수 있으니 모두 뺀 첫 화면으로 가는 길을 둔다(TASK-33#2). 이미 / 면 같은 화면이라 뺀다. */}
          {listHref(filters) !== "/" && (
            <Link href="/" className={buttonVariants({ variant: "outline", size: "sm" })} data-testid="post-list-first-page">
              첫 페이지로
            </Link>
          )}
        </div>
      ) : list.isPending ? (
        <p role="status" className="text-muted-foreground text-sm">
          불러오는 중…
        </p>
      ) : list.data.items.length === 0 ? (
        <p className="text-muted-foreground py-12 text-center text-sm" data-testid="post-list-empty">
          글이 없습니다.
        </p>
      ) : (
        <ul className="divide-y rounded-lg border" data-testid="post-list">
          {list.data.items.map((post) => (
            <PostItem key={post.id} post={post} />
          ))}
        </ul>
      )}

      {list.data && <Pagination filters={filters} page={list.data.page + 1} totalPages={list.data.totalPages} />}
    </div>
  );
}

function CategoryTabs({ filters }: { filters: ListFilters }) {
  const query = useCategories();
  const categories = query.data;
  const tabs = [{ slug: undefined, name: "전체" }, ...(categories ?? [])];
  return (
    <div className="flex flex-col gap-1">
      <nav aria-label="카테고리" data-testid="category-tabs" className="flex flex-wrap gap-1 border-b">
        {tabs.map((tab) => {
          const active = filters.category === tab.slug;
          return (
            <Link
              key={tab.slug ?? ""}
              href={listHref(withFilters(filters, { category: tab.slug }))}
              aria-current={active ? "page" : undefined}
              data-testid="category-tab"
              className={cn(
                "-mb-px border-b-2 px-3 py-2 text-sm",
                active ? "border-foreground font-medium" : "text-muted-foreground border-transparent",
              )}
            >
              {tab.name}
            </Link>
          );
        })}
      </nav>
      {query.isError && !categories && (
        <LoadError testId="category-tabs-error" retrying={query.isFetching} onRetry={() => void query.refetch()}>
          카테고리를 불러오지 못했습니다.
        </LoadError>
      )}
    </div>
  );
}

/** 보조 목록(카테고리 탭·인기 태그) 조회 실패 한 줄(TASK-33#2, FE-22). 목록은 그대로 쓸 수 있으니 alert 가 아닌 조용한 안내다. */
function LoadError({
  children,
  onRetry,
  retrying,
  testId,
}: {
  children: ReactNode;
  onRetry: () => void;
  retrying: boolean;
  testId: string;
}) {
  return (
    <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid={testId}>
      {children}
      <Button variant="outline" size="xs" disabled={retrying} onClick={onRetry}>
        다시 시도
      </Button>
    </p>
  );
}

function SearchForm({ filters, onSearch }: { filters: ListFilters; onSearch: (q: string | undefined) => void }) {
  const [text, setText] = useState(filters.q ?? "");
  const [error, setError] = useState<string | null>(null);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const result = validateSearch(text);
    if (result.error) {
      setError(result.error);
      return;
    }
    setError(null);
    onSearch(result.q);
  };

  return (
    <form role="search" onSubmit={submit} className="flex flex-col gap-1">
      <div className="flex gap-2">
        <Input
          type="search"
          aria-label="검색어"
          data-testid="search-input"
          placeholder="제목·본문 검색"
          value={text}
          onChange={(e) => setText(e.target.value)}
          aria-invalid={error ? true : undefined}
          className="w-64"
        />
        <Button type="submit" variant="outline" data-testid="search-submit">
          검색
        </Button>
      </div>
      {error && <p className="text-destructive text-sm">{error}</p>}
    </form>
  );
}

function TagChips({ filters }: { filters: ListFilters }) {
  const query = usePopularTags();
  const tags = query.data;
  return (
    <div className="flex flex-wrap items-center gap-2">
      {filters.tag && (
        <Link
          href={listHref(withFilters(filters, { tag: undefined }))}
          aria-label={`태그 ${filters.tag} 필터 해제`}
          data-testid="active-tag"
        >
          <Badge>#{filters.tag} ✕</Badge>
        </Link>
      )}
      {tags && tags.length > 0 && (
        <nav aria-label="인기 태그" data-testid="tag-chips" className="flex flex-wrap gap-1">
          {tags
            .filter((tag) => tag.name !== filters.tag)
            .map((tag) => (
              <Link key={tag.name} href={listHref(withFilters(filters, { tag: tag.name }))} data-testid="tag-chip">
                <Badge variant="outline">
                  #{tag.name} <span className="text-muted-foreground">{tag.postCount}</span>
                </Badge>
              </Link>
            ))}
        </nav>
      )}
      {query.isError && !tags && (
        <LoadError testId="tag-chips-error" retrying={query.isFetching} onRetry={() => void query.refetch()}>
          인기 태그를 불러오지 못했습니다.
        </LoadError>
      )}
    </div>
  );
}

function PostItem({ post }: { post: PostSummary }) {
  return (
    <li className="flex flex-col gap-1 p-4" data-testid="post-item">
      <div className="flex flex-wrap items-center gap-2">
        {post.category && <Badge variant="secondary">{post.category.name}</Badge>}
        <Link href={`/posts/view/?id=${post.id}`} className="font-medium hover:underline" data-testid="post-item-link">
          {post.title}
        </Link>
        <PostFlags deleted={post.deleted} hidden={post.hidden} />
      </div>
      {post.excerpt && <p className="text-muted-foreground line-clamp-2 text-sm">{post.excerpt}</p>}
      <div className="text-muted-foreground flex flex-wrap items-center gap-3 text-xs">
        <span>{post.author.nickname}</span>
        <time dateTime={post.createdAt}>{formatDate(post.createdAt)}</time>
        <span className="flex items-center gap-1" data-testid="post-item-likes" aria-label={`좋아요 ${post.likeCount}`}>
          <Heart className="size-3" aria-hidden /> {post.likeCount}
        </span>
        <span className="flex items-center gap-1" data-testid="post-item-views" aria-label={`조회 ${post.viewCount}`}>
          <Eye className="size-3" aria-hidden /> {post.viewCount}
        </span>
        {post.tags.map((tag) => (
          <span key={tag}>#{tag}</span>
        ))}
      </div>
    </li>
  );
}

function Pagination({ filters, page, totalPages }: { filters: ListFilters; page: number; totalPages: number }) {
  if (totalPages <= 1) return null;
  return (
    <nav aria-label="페이지" className="flex items-center justify-center gap-3 text-sm">
      {page > 1 ? (
        <Link
          href={listHref(withFilters(filters, { page: page - 1 }))}
          className={buttonVariants({ variant: "outline", size: "sm" })}
          data-testid="pagination-prev"
        >
          이전
        </Link>
      ) : (
        <span className={cn(buttonVariants({ variant: "outline", size: "sm" }), "opacity-50")} aria-hidden>
          이전
        </span>
      )}
      <span data-testid="pagination-current">
        {page} / {totalPages}
      </span>
      {page < totalPages ? (
        <Link
          href={listHref(withFilters(filters, { page: page + 1 }))}
          className={buttonVariants({ variant: "outline", size: "sm" })}
          data-testid="pagination-next"
        >
          다음
        </Link>
      ) : (
        <span className={cn(buttonVariants({ variant: "outline", size: "sm" }), "opacity-50")} aria-hidden>
          다음
        </span>
      )}
    </nav>
  );
}
