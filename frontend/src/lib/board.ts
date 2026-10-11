"use client";

import { useQuery } from "@tanstack/react-query";

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

export function formatDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("ko-KR", { dateStyle: "medium", timeStyle: "short" });
}
