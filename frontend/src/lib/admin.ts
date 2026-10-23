"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import type { Provider } from "@/lib/auth";
import { javaStrip } from "@/lib/board-query";
import type { Category, Page } from "@/lib/board";
import { queryKeys } from "@/lib/query";

/** 관리자 API 응답 모양과 훅 (TASK-15, 서버는 TASK-12 README "관리자 API"). */

export type UserStatus = "ACTIVE" | "SUSPENDED";

export interface AdminUser {
  id: number;
  nickname: string;
  email: string | null;
  phone: string | null;
  role: "USER" | "ADMIN";
  status: UserStatus;
  providers: Provider[];
  createdAt: string;
}

export type AdminActionType =
  | "USER_SUSPEND"
  | "USER_UNSUSPEND"
  | "USER_SESSIONS_REVOKE"
  | "POST_HIDE"
  | "POST_UNHIDE"
  | "POST_RESTORE"
  | "CATEGORY_CREATE"
  | "CATEGORY_UPDATE"
  | "CATEGORY_DELETE";

export interface AdminActionEntry {
  id: number;
  adminId: number;
  /** 관리자 계정이 지워졌으면 null. */
  adminNickname: string | null;
  action: AdminActionType | string;
  targetType: "USER" | "POST" | "CATEGORY" | string;
  targetId: number;
  reason: string | null;
  createdAt: string;
}

export const ACTION_LABELS: Record<string, string> = {
  USER_SUSPEND: "사용자 정지",
  USER_UNSUSPEND: "사용자 정지 해제",
  USER_SESSIONS_REVOKE: "사용자 세션 끊기",
  POST_HIDE: "글 숨김",
  POST_UNHIDE: "글 숨김 해제",
  POST_RESTORE: "글 복구",
  CATEGORY_CREATE: "카테고리 생성",
  CATEGORY_UPDATE: "카테고리 수정",
  CATEGORY_DELETE: "카테고리 삭제",
};

export const TARGET_LABELS: Record<string, string> = { USER: "사용자", POST: "글", CATEGORY: "카테고리" };

/** 관리 목록 페이지 크기. 서버 상한은 100. */
export const ADMIN_PAGE_SIZE = 20;

/** 서버 `ReasonRequest`: 사유는 선택, 500자 이하(Java length = UTF-16 단위, JS length 와 같다). */
export const REASON_MAX = 500;

export interface UserFilters {
  q?: string;
  status?: UserStatus;
  /** 0부터(API 와 같다). */
  page: number;
}

/** 관리 쓰기 공통: 감사 로그가 한 줄 늘었으니 감사 로그 쿼리도 무효화한다. */
function invalidateActions(client: ReturnType<typeof useQueryClient>) {
  return client.invalidateQueries({ queryKey: queryKeys.adminActions });
}

export function useAdminUsers(filters: UserFilters) {
  const query = { q: filters.q, status: filters.status, page: filters.page, size: ADMIN_PAGE_SIZE };
  return useQuery({
    queryKey: queryKeys.adminUserList(query),
    queryFn: ({ signal }) => api<Page<AdminUser>>("/admin/users", { query, signal }),
    placeholderData: keepPreviousData,
  });
}

/** `{reason}` 본문. 비어 있으면 `{}`(서버가 null 로 저장). */
export function reasonBody(reason: string): { reason?: string } {
  const trimmed = reason.trim();
  return trimmed ? { reason: trimmed } : {};
}

/**
 * 정지·해제 응답. 정지는 `warnings`(평소 `[]`)가 붙는다 — 정지 커밋 뒤 세션 삭제가 실패하면 `["session_revoke_failed"]`(정지는 그대로,
 * TASK-27#3). 해제 응답에는 없다.
 */
export type UserStatusResult = AdminUser & { warnings?: string[] };

export const SESSION_REVOKE_FAILED = "session_revoke_failed";

/**
 * 정지 경고 문장(TASK-33#5). 모르는 코드는 코드 없이 일반 문장만 보인다(코드는 {@link reportUnknownWarnings} 가 콘솔로 남긴다) —
 * 서버가 새 경고를 더해도 무음이 되지 않고, 화면에 내부 코드가 드러나지도 않는다.
 */
export function suspendWarningMessage(code: string, user: { nickname: string; id: number }): string {
  const who = `${user.nickname}(#${user.id})`;
  if (code === SESSION_REVOKE_FAILED) {
    return (
      `${who} 정지는 적용됐지만 로그인 세션을 모두 끊지 못했을 수 있습니다. 남은 세션으로도 글·댓글 쓰기는 막히지만, 읽기는 그 세션이 끝날 때까지 될 수 있습니다. ` +
      "'세션 끊기 다시 시도'는 정지 상태는 그대로 두고 세션만 다시 끊습니다(감사 로그에 세션 끊기로 한 줄 남습니다)."
    );
  }
  return `${who} 정지는 적용됐지만 서버가 확인이 필요한 경고를 보냈습니다.`;
}

/** 모르는 경고 코드를 콘솔에 남긴다(화면에는 일반 문장만, TASK-33#5). */
export function reportUnknownWarnings(codes: readonly string[]) {
  for (const code of codes) {
    if (code !== SESSION_REVOKE_FAILED) console.warn(`알 수 없는 관리자 경고 코드: ${code}`);
  }
}

export function useUserStatusAction() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action, reason }: { id: number; action: "suspend" | "unsuspend"; reason: string }) =>
      api<UserStatusResult>(`/admin/users/${id}/${action}`, { method: "POST", body: reasonBody(reason) }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: queryKeys.adminUsers });
      void invalidateActions(client);
    },
  });
}

/** 세션 끊기 응답(TASK-33#5): 정지 응답과 같은 `warnings` 모양. */
export interface RevokeSessionsResult {
  warnings: string[];
}

/**
 * 세션 끊기(TASK-33#5): 정지 상태는 그대로 두고 그 사용자의 세션·refresh 토큰만 끊는다(멱등, 서버
 * `POST /admin/users/{id}/sessions/revoke`). 정지 경고의 '세션 끊기 다시 시도'가 쓴다. 감사 로그가 한 줄 늘어난다.
 */
export function useRevokeSessions() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      api<RevokeSessionsResult>(`/admin/users/${id}/sessions/revoke`, { method: "POST", body: reasonBody(reason) }),
    onSuccess: () => void invalidateActions(client),
  });
}

export type PostModeration = "hide" | "unhide" | "restore";

export function usePostModeration() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action, reason }: { id: number; action: PostModeration; reason: string }) =>
      api<unknown>(`/admin/posts/${id}/${action}`, { method: "POST", body: reasonBody(reason) }),
    onSuccess: () => {
      // 목록·상세 모두 숨김·삭제 플래그가 바뀐다.
      void client.invalidateQueries({ queryKey: queryKeys.posts });
      void invalidateActions(client);
    },
  });
}

export function useAdminActions(page: number) {
  return useQuery({
    queryKey: queryKeys.adminActionPage(page),
    queryFn: ({ signal }) =>
      api<Page<AdminActionEntry>>("/admin/actions", { query: { page, size: ADMIN_PAGE_SIZE }, signal }),
    placeholderData: keepPreviousData,
  });
}

// ---- 카테고리 ----

export interface CategoryForm {
  slug: string;
  name: string;
  /** 입력칸 그대로(빈 문자열이면 생성 시 맨 뒤). */
  sortOrder: string;
  adminOnly: boolean;
}

export type CategoryFormErrors = Partial<Record<keyof CategoryForm, string>>;

export const SLUG_PATTERN = /^[a-z0-9-]{2,30}$/;
export const SLUG_MESSAGE = "slug 는 소문자·숫자·하이픈 2~30자여야 합니다";
export const NAME_MAX = 50;
/** 서버 `AdminCategoryRequests.MAX_SORT_ORDER`(TASK-27#4): 순서는 0~10000 정수. */
export const SORT_ORDER_MAX = 10000;
export const SORT_ORDER_MESSAGE = `순서는 0~${SORT_ORDER_MAX} 사이의 정수여야 합니다`;

/**
 * 서버 `AdminCategoryRequests` 와 같은 규칙. 이름은 서버처럼 앞뒤 공백을 자른 뒤 1~50자. 순서는 0~10000 정수(TASK-33#6).
 * 수정(`original`)에서 순서를 바꾸지 않았으면 PATCH 에 빠지므로(서버도 검사하지 않는다) 범위 밖 기존 값이어도 통과시킨다.
 */
export function validateCategoryForm(form: CategoryForm, original?: Category): CategoryFormErrors {
  const errors: CategoryFormErrors = {};
  if (!SLUG_PATTERN.test(form.slug)) errors.slug = SLUG_MESSAGE;
  const name = javaStrip(form.name);
  if (!name) errors.name = "이름을 입력하세요";
  else if (name.length > NAME_MAX) errors.name = `이름은 1~${NAME_MAX}자입니다`;
  const order = form.sortOrder.trim();
  // '바꾸지 않음'은 updatePayload 와 같은 숫자 비교다(020000 = 20000 이면 PATCH 에서 빠지므로 검사도 하지 않는다).
  const unchanged = original !== undefined && order !== "" && Number(order) === original.sortOrder;
  if (order && !unchanged) {
    if (!/^\d+$/.test(order) || Number(order) > SORT_ORDER_MAX) errors.sortOrder = SORT_ORDER_MESSAGE;
  }
  return errors;
}

export interface CategoryPayload {
  slug?: string;
  name?: string;
  sortOrder?: number;
  adminOnly?: boolean;
}

/** 생성 본문. 순서를 비우면 보내지 않는다(서버가 맨 뒤로). */
export function createPayload(form: CategoryForm): CategoryPayload {
  const payload: CategoryPayload = { slug: form.slug, name: javaStrip(form.name), adminOnly: form.adminOnly };
  const order = form.sortOrder.trim();
  if (order) payload.sortOrder = Number(order);
  return payload;
}

/** 수정 본문: 원래 값과 다른 필드만(PATCH 는 보낸 필드만 바꾼다). 순서를 비우면 바꾸지 않는다. */
export function updatePayload(original: Category, form: CategoryForm): CategoryPayload {
  const full = createPayload(form);
  const payload: CategoryPayload = {};
  if (full.slug !== original.slug) payload.slug = full.slug;
  if (full.name !== original.name) payload.name = full.name;
  if (full.sortOrder !== undefined && full.sortOrder !== original.sortOrder) payload.sortOrder = full.sortOrder;
  if (full.adminOnly !== original.adminOnly) payload.adminOnly = full.adminOnly;
  return payload;
}

/** 카테고리가 바뀌면 공개 카테고리 목록(탭·편집기)과, 글에 붙은 카테고리 이름·slug 가 달라지므로 글 쿼리도 무효화한다. */
function useInvalidateCategories() {
  const client = useQueryClient();
  return () => {
    void client.invalidateQueries({ queryKey: queryKeys.categories });
    void client.invalidateQueries({ queryKey: queryKeys.posts });
    void invalidateActions(client);
  };
}

export function useSaveCategory() {
  const invalidate = useInvalidateCategories();
  return useMutation({
    mutationFn: ({ id, payload }: { id?: number; payload: CategoryPayload }) =>
      id === undefined
        ? api<Category>("/admin/categories", { method: "POST", body: payload })
        : api<Category>(`/admin/categories/${id}`, { method: "PATCH", body: payload }),
    onSuccess: invalidate,
  });
}

export function useDeleteCategory() {
  const invalidate = useInvalidateCategories();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/admin/categories/${id}`, { method: "DELETE" }),
    onSuccess: invalidate,
  });
}
