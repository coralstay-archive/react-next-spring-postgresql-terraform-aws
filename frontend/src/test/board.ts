import { http, HttpResponse } from "msw";

import type { Category, Comment, PostDetail, PostSummary, TagCount } from "@/lib/board";

/** 게시판 테스트 픽스처·MSW 핸들러 (TASK-14). */

export const CATEGORIES: Category[] = [
  { id: 1, slug: "notice", name: "공지", sortOrder: 0, adminOnly: true },
  { id: 2, slug: "free", name: "자유게시판", sortOrder: 1, adminOnly: false },
];

export function makeSummary(overrides: Partial<PostSummary> = {}): PostSummary {
  return {
    id: 1,
    title: "제목",
    excerpt: "본문 요약",
    author: { id: 1, nickname: "작성자" },
    category: { id: 2, slug: "free", name: "자유게시판" },
    tags: [],
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    deleted: false,
    hidden: false,
    likeCount: 0,
    viewCount: 0,
    ...overrides,
  };
}

export function makePost(overrides: Partial<PostDetail> = {}): PostDetail {
  return {
    id: 1,
    title: "제목",
    content: "본문",
    author: { id: 1, nickname: "작성자" },
    category: { id: 2, slug: "free", name: "자유게시판" },
    tags: [],
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    editable: false,
    deleted: false,
    hidden: false,
    likeCount: 0,
    likedByMe: false,
    viewCount: 1,
    attachments: [],
    ...overrides,
  };
}

export function makeComment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: 1,
    postId: 1,
    content: "댓글",
    author: { id: 2, nickname: "댓글러" },
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    deleted: false,
    editable: false,
    ...overrides,
  };
}

export function pageOf<T>(items: T[], meta: { page?: number; size?: number; totalElements?: number; totalPages?: number } = {}) {
  return {
    items,
    page: meta.page ?? 0,
    size: meta.size ?? 20,
    totalElements: meta.totalElements ?? items.length,
    totalPages: meta.totalPages ?? (items.length ? 1 : 0),
  };
}

export function categoriesHandler(categories: Category[] = CATEGORIES) {
  return http.get("*/api/categories", () => HttpResponse.json(categories));
}

export function tagsHandler(tags: TagCount[] = [{ name: "java", postCount: 3 }]) {
  return http.get("*/api/tags", () => HttpResponse.json(tags));
}

export function postsPageHandler(items: PostSummary[] = [makeSummary()]) {
  return http.get("*/api/posts", () => HttpResponse.json(pageOf(items)));
}

export function postHandler(post: PostDetail) {
  return http.get(`*/api/posts/${post.id}`, () => HttpResponse.json(post));
}

export function commentsHandler(postId: number, get: () => Comment[]) {
  return http.get(`*/api/posts/${postId}/comments`, () => HttpResponse.json(pageOf(get())));
}
