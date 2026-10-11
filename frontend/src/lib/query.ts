import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";

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
};

const MAX_RETRIES = 2;

/** 4xx 는 다시 해도 같으므로 재시도하지 않는다. 네트워크 오류·5xx 만 최대 2번. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_RETRIES) return false;
  if (isApiError(error)) return error.status === 0 || error.status >= 500;
  return false;
}

export function createQueryClient(): QueryClient {
  // 401 unauthorized: 세션이 끝났다(만료·로그아웃·정지로 세션 삭제). me 를 로그아웃 상태로 바꿔 헤더와 가드가 따라가게 한다.
  // 401 이라도 invalid_credentials·invalid_otp(틀린 비밀번호·인증번호)는 세션과 무관하므로 건드리지 않는다.
  const onError = (error: unknown) => {
    if (isApiError(error) && error.isSessionExpired) {
      client.setQueryData(queryKeys.me, null);
    }
  };
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: {
      queries: { retry: shouldRetry, staleTime: 30_000, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  return client;
}
