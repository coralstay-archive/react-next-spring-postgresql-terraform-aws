import { hashKey, MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";

import { isApiError } from "@/lib/api";

/**
 * 쿼리 키 규약: `[도메인, ...세부]` 배열. 무효화는 앞부분으로 한다(예: `["posts"]` 면 모든 글 목록·상세).
 * TASK-14(게시판)·TASK-15(관리자)는 여기에 키를 더한다.
 */
export const queryKeys = {
  me: ["auth", "me"] as const,
  identities: ["me", "identities"] as const,
  // 게시판(TASK-14). ["posts"] 로 무효화하면 목록·상세·댓글이 모두 해당한다.
  posts: ["posts"] as const,
  postLists: ["posts", "list"] as const,
  postList: (query: object) => ["posts", "list", query] as const,
  post: (id: number) => ["posts", "detail", id] as const,
  comments: (postId: number) => ["posts", "comments", postId] as const,
  commentPage: (postId: number, page: number) => ["posts", "comments", postId, page] as const,
  categories: ["categories"] as const,
  tags: (q: string) => ["tags", q] as const,
  // 관리자(TASK-15). 관리 쓰기 뒤에는 ["admin", "actions"](감사 로그)도 무효화한다.
  admin: ["admin"] as const,
  adminUsers: ["admin", "users"] as const,
  adminUserList: (query: object) => ["admin", "users", query] as const,
  adminActions: ["admin", "actions"] as const,
  adminActionPage: (page: number) => ["admin", "actions", page] as const,
};

/** 세션 만료 전역 안내 플래그(true 면 안내). `["auth", …]` 라 만료 리셋에서 빠진다. */
export const SESSION_EXPIRED_KEY = ["auth", "sessionExpired"] as const;

const MAX_RETRIES = 2;

/** 4xx 는 다시 해도 같으므로 재시도하지 않는다. 네트워크 오류·5xx 만 최대 2번. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_RETRIES) return false;
  if (isApiError(error)) return error.status === 0 || error.status >= 500;
  return false;
}

/** 로그인 사용자(me 데이터)인가. 익명은 null, 아직 모르면 undefined. */
function isUser(value: unknown): boolean {
  return value !== null && typeof value === "object";
}

/** 만료 처리를 건너뛸 뮤테이션(예: 로그아웃 — 401 이면 원래 하려던 대로 로그아웃 상태가 될 뿐 "만료"가 아니다). */
export interface SessionMeta extends Record<string, unknown> {
  ignoreSessionExpiry?: boolean;
}

/**
 * 세션 만료(TASK-29#1, FE-5). "로그인 상태였는데 세션이 끝났다"를 두 곳에서 알아챈다.
 *
 * 1. me 가 아닌 쿼리·뮤테이션의 401 `unauthorized`(`isSessionExpired`). 401 `invalid_credentials`·`invalid_otp`(틀린 비밀번호·인증번호)와
 *    계약 밖 401(`http_401`)은 세션과 무관하므로 건드리지 않는다.
 * 2. me 재조회(포커스·주기, TASK-29#2)가 사용자 → null 로 바뀐 것. me 자체는 401 을 null(익명)로 돌려주므로 오류가 아니다.
 *    로그인·로그아웃이 `setQueryData` 로 바꾼 것(수동 갱신)은 만료가 아니다.
 *
 * 직전 me 가 사용자였을 때만 만료로 처리한다: me=null, `["auth", …]` 밖의 모든 쿼리를 reset(데이터를 버리고 화면에 떠 있는 것은
 * 익명으로 다시 받는다 — 서버 `editable` 이 false 가 되어 수정·삭제 버튼이 사라진다, B2), 전역 안내 플래그 {@link SESSION_EXPIRED_KEY}.
 * 리셋 뒤 다시 받은 쿼리가 또 401 이어도 me 가 이미 null 이라 되풀이하지 않는다. 다시 로그인하면(me 가 사용자) 플래그를 내린다.
 */
export function createQueryClient(): QueryClient {
  let lastMe: unknown = undefined;

  const expire = () => {
    client.setQueryData(SESSION_EXPIRED_KEY, true);
    void client.resetQueries({ predicate: (query) => query.queryKey[0] !== queryKeys.me[0] });
  };

  const onSessionError = (error: unknown, meta: SessionMeta | undefined) => {
    if (!isApiError(error) || !error.isSessionExpired || meta?.ignoreSessionExpiry) return;
    const wasUser = isUser(client.getQueryData(queryKeys.me));
    client.setQueryData(queryKeys.me, null);
    if (wasUser) expire();
  };

  const queryCache = new QueryCache({ onError: (error, query) => onSessionError(error, query.meta) });
  const client: QueryClient = new QueryClient({
    queryCache,
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => onSessionError(error, mutation.meta),
    }),
    defaultOptions: {
      queries: { retry: shouldRetry, staleTime: 30_000, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });

  queryCache.subscribe((event) => {
    if (event.type !== "updated" || event.action.type !== "success") return;
    if (event.query.queryHash !== meHash) return;
    const data = event.query.state.data;
    const previous = lastMe;
    lastMe = data;
    if (isUser(data)) {
      if (client.getQueryData(SESSION_EXPIRED_KEY)) client.setQueryData(SESSION_EXPIRED_KEY, false);
    } else if (data === null && isUser(previous) && !event.action.manual) {
      expire();
    }
  });

  return client;
}

const meHash = hashKey(queryKeys.me);
