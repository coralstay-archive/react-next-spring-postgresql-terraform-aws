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

/**
 * 업로드 흐름 대역: presign(→ `http://storage.test/...` PUT URL) → PUT → confirm. 첨부 id 는 100부터.
 * 받은 요청을 기록한다. `putStatus` 로 S3 PUT 실패를 흉내 낸다.
 */
export function presignHandlers(options: { putStatus?: number } = {}) {
  let nextId = 100;
  const sizes = new Map<number, { contentType: string; size: number }>();
  const calls = {
    presignBody: undefined as unknown,
    putHeaders: undefined as Headers | undefined,
    putSize: undefined as number | undefined,
    confirmed: [] as number[],
    handlers: [
      http.post("*/api/attachments/presign", async ({ request }) => {
        const body = (await request.json()) as { contentType: string; size: number };
        calls.presignBody = body;
        const id = nextId++;
        sizes.set(id, body);
        return HttpResponse.json({
          attachmentId: id,
          key: `uploads/1/${id}.png`,
          uploadUrl: `http://storage.test/bucket/uploads/1/${id}.png?X-Amz-Signature=sig`,
          method: "PUT",
          headers: { "content-type": body.contentType, "x-amz-tagging": "status=pending" },
          expiresAt: "2026-10-12T00:05:00Z",
        });
      }),
      http.put("http://storage.test/bucket/*", async ({ request }) => {
        calls.putHeaders = request.headers;
        calls.putSize = (await request.arrayBuffer()).byteLength;
        if (options.putStatus) return new HttpResponse("denied", { status: options.putStatus });
        return new HttpResponse(null, { status: 200 });
      }),
      http.post("*/api/attachments/:id/confirm", ({ params }) => {
        const id = Number(params.id);
        calls.confirmed.push(id);
        const meta = sizes.get(id)!;
        return HttpResponse.json({
          id,
          url: `/uploads/1/${id}.png`,
          contentType: meta.contentType,
          size: meta.size,
          status: "CONFIRMED",
        });
      }),
    ],
  };
  return calls;
}
