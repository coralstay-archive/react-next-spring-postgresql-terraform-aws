"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { listApiQuery, type ListFilters } from "@/lib/board-query";
import { queryKeys } from "@/lib/query";

/** 게시판 API 응답 모양(백엔드 PostResponses·CommentResponse·AttachmentResponses, README "게시판 API"). */

export interface AuthorRef {
  id: number;
  nickname: string;
}

export interface CategoryRef {
  id: number;
  slug: string;
  name: string;
}

export interface Category extends CategoryRef {
  sortOrder: number;
  /** 공지처럼 ADMIN 만 글을 쓸 수 있는 카테고리. */
  adminOnly: boolean;
}

export interface TagCount {
  name: string;
  postCount: number;
}

export interface PostSummary {
  id: number;
  title: string;
  excerpt: string;
  author: AuthorRef;
  category: CategoryRef | null;
  tags: string[];
  createdAt: string;
  updatedAt: string;
  deleted: boolean;
  hidden: boolean;
  likeCount: number;
  viewCount: number;
}

export interface Attachment {
  id: number;
  url: string;
  contentType: string;
  size: number;
  status: "PENDING" | "CONFIRMED";
}

export interface PostDetail {
  id: number;
  title: string;
  content: string;
  author: AuthorRef;
  category: CategoryRef | null;
  tags: string[];
  createdAt: string;
  updatedAt: string;
  /** 보는 사람이 작성자나 ADMIN 이고 글이 삭제되지 않았다. 수정·삭제 버튼은 이 값만 본다(TASK-14#4). */
  editable: boolean;
  deleted: boolean;
  hidden: boolean;
  likeCount: number;
  likedByMe: boolean;
  viewCount: number;
  attachments: Attachment[];
}

export interface Comment {
  id: number;
  postId: number;
  /** 삭제된 댓글은 null. */
  content: string | null;
  author: AuthorRef | null;
  createdAt: string;
  updatedAt: string;
  deleted: boolean;
  editable: boolean;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface LikeState {
  liked: boolean;
  likeCount: number;
}

export function usePostList(filters: ListFilters) {
  const query = listApiQuery(filters);
  return useQuery({
    queryKey: queryKeys.postList(query),
    queryFn: ({ signal }) => api<Page<PostSummary>>("/posts", { query, signal }),
  });
}

export function useCategories() {
  return useQuery({
    queryKey: queryKeys.categories,
    queryFn: ({ signal }) => api<Category[]>("/categories", { signal }),
    staleTime: 5 * 60_000,
  });
}

export function usePopularTags() {
  return useQuery({
    queryKey: queryKeys.tags(""),
    queryFn: ({ signal }) => api<TagCount[]>("/tags", { query: { limit: 20 }, signal }),
    staleTime: 60_000,
  });
}

/** URL `?id=` → 양의 정수 id, 아니면 null(요청하지 않고 "없는 글"). */
export function parsePostId(raw: string | null): number | null {
  if (!raw || !/^\d{1,15}$/.test(raw)) return null;
  const id = Number(raw);
  return id > 0 ? id : null;
}

/** 상세. 서버가 조회수를 30분에 한 번 센다(B2). 응답의 `viewCount` 에는 이번 조회가 들어 있다. */
export function usePost(id: number) {
  return useQuery({
    queryKey: queryKeys.post(id),
    queryFn: ({ signal }) => api<PostDetail>(`/posts/${id}`, { signal }),
  });
}

/**
 * 좋아요·취소. 누르는 즉시 상세 캐시를 바꾸고(낙관적), 실패하면 이전 값으로 되돌린다. 성공하면 서버 값 `{liked, likeCount}` 로 맞춘다.
 * 목록의 likeCount·인기순도 바뀌므로 목록 캐시는 stale 로만 표시한다(지금 다시 받지는 않는다).
 */
export function useToggleLike(id: number) {
  const client = useQueryClient();
  const key = queryKeys.post(id);
  return useMutation({
    mutationFn: (like: boolean) => api<LikeState>(`/posts/${id}/like`, { method: like ? "POST" : "DELETE" }),
    onMutate: async (like) => {
      await client.cancelQueries({ queryKey: key });
      const previous = client.getQueryData<PostDetail>(key);
      if (previous && previous.likedByMe !== like) {
        client.setQueryData<PostDetail>(key, {
          ...previous,
          likedByMe: like,
          likeCount: Math.max(0, previous.likeCount + (like ? 1 : -1)),
        });
      }
      return { previous };
    },
    onError: (_error, _like, context) => {
      if (context?.previous) client.setQueryData(key, context.previous);
    },
    onSuccess: (state) => {
      client.setQueryData<PostDetail>(key, (old) =>
        old ? { ...old, likedByMe: state.liked, likeCount: state.likeCount } : old,
      );
    },
    onSettled: () => client.invalidateQueries({ queryKey: queryKeys.postLists, refetchType: "none" }),
  });
}

export const COMMENT_PAGE_SIZE = 50;

export function useComments(postId: number, page: number) {
  return useQuery({
    queryKey: queryKeys.commentPage(postId, page),
    queryFn: ({ signal }) =>
      api<Page<Comment>>(`/posts/${postId}/comments`, { query: { page, size: COMMENT_PAGE_SIZE }, signal }),
  });
}

export function useCreateComment(postId: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (content: string) => api<Comment>(`/posts/${postId}/comments`, { method: "POST", body: { content } }),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.comments(postId) }),
  });
}

export function useDeleteComment(postId: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (commentId: number) => api<void>(`/comments/${commentId}`, { method: "DELETE" }),
    onSettled: () => client.invalidateQueries({ queryKey: queryKeys.comments(postId) }),
  });
}

export function formatDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("ko-KR", { dateStyle: "medium", timeStyle: "short" });
}
