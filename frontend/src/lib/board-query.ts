/**
 * 목록 화면 상태 ↔ URL 쿼리 ↔ `GET /api/posts` 쿼리 (TASK-14#1). 목록 상태는 URL 에만 두어 공유·뒤로 가기가 된다.
 *
 * URL: `/?category=<slug>&tag=<name>&q=<검색어>&sort=popular&page=<1부터>&includeHidden=true`. 기본값(최신순, 1쪽)은 생략한다.
 * API 의 `page` 는 0부터라 하나 뺀다.
 */

export type PostSort = "latest" | "popular";

export const PAGE_SIZE = 20;

/** 서버 `Paging.MAX_OFFSET`(TASK-23#5): `page × size`(OFFSET)가 이를 넘으면 400 `validation` + `fields.page`. 관리자 목록도 같다. */
export const MAX_OFFSET = 10_000;

/** 크기 `size` 로 요청할 수 있는 쪽 수(TASK-33#4). API page 는 0..floor(MAX_OFFSET/size) 라 1부터 세면 그 + 1 쪽. */
export function maxPageCount(size: number): number {
  return Math.floor(MAX_OFFSET / size) + 1;
}

/** 게시판 목록의 마지막으로 요청할 수 있는 쪽(1부터). */
export const MAX_LIST_PAGE = maxPageCount(PAGE_SIZE);

export interface ListFilters {
  category?: string;
  tag?: string;
  /** 앞뒤 공백을 자른 검색어(유효성은 {@link validateSearch}). */
  q?: string;
  sort: PostSort;
  /** 1부터. */
  page: number;
  /** ADMIN 만 의미가 있다(서버가 USER 에게는 무시). */
  includeHidden?: true;
}

export const SEARCH_MIN = 2;
export const SEARCH_MAX = 100;
export const SEARCH_MESSAGE = `검색어는 ${SEARCH_MIN}~${SEARCH_MAX}자여야 합니다`;

/**
 * Java `String.strip()`: `Character.isWhitespace` 인 문자를 앞뒤에서 자른다. JS `trim()` 과 달리 줄바꿈 없는 공백
 * (U+00A0·U+2007·U+202F)과 U+FEFF 는 남기고, U+001C~U+001F 는 자른다.
 */
export function isJavaWhitespace(ch: string): boolean {
  const c = ch.codePointAt(0)!;
  if ((c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x20)) return true;
  if (c === 0x1680 || c === 0x205f || c === 0x3000 || c === 0x2028 || c === 0x2029) return true;
  return c >= 0x2000 && c <= 0x200a && c !== 0x2007;
}

export function javaStrip(value: string): string {
  const chars = [...value];
  let start = 0;
  let end = chars.length;
  while (start < end && isJavaWhitespace(chars[start])) start++;
  while (end > start && isJavaWhitespace(chars[end - 1])) end--;
  return chars.slice(start, end).join("");
}

/** 서버 `PostController.searchTerm`: 자른 뒤 비면 검색 안 함, 아니면 2~100자(코드 포인트). */
export function validateSearch(raw: string | undefined): { q: string | undefined; error?: string } {
  const term = javaStrip(raw ?? "");
  if (!term) return { q: undefined };
  const length = [...term].length;
  if (length < SEARCH_MIN || length > SEARCH_MAX) return { q: term, error: SEARCH_MESSAGE };
  return { q: term };
}

function nonEmpty(value: string | null): string | undefined {
  if (value === null) return undefined;
  const v = javaStrip(value);
  return v ? v : undefined;
}

/**
 * URL `page`(1부터): 1 이상의 정수 문자열만 받고 그 외는 1쪽(TASK-33#2). OFFSET 상한을 넘으면(자릿수가 넘쳐도) {@link MAX_LIST_PAGE} 로 자르고
 * `capped` 로 알린다(TASK-33#4).
 */
export function parseListPage(raw: string | null): { page: number; capped: boolean } {
  if (raw === null || !/^\d+$/.test(raw)) return { page: 1, capped: false };
  const page = Number(raw);
  if (page < 1) return { page: 1, capped: false };
  if (page > MAX_LIST_PAGE) return { page: MAX_LIST_PAGE, capped: true };
  return { page, capped: false };
}

export function parseListFilters(params: { get(name: string): string | null }): ListFilters {
  return withFilters(
    { sort: "latest", page: 1 },
    {
      category: nonEmpty(params.get("category")),
      tag: nonEmpty(params.get("tag")),
      q: nonEmpty(params.get("q")),
      sort: params.get("sort")?.toLowerCase() === "popular" ? "popular" : "latest",
      page: parseListPage(params.get("page")).page,
      includeHidden: params.get("includeHidden") === "true" ? true : undefined,
    },
  );
}

export function listHref(filters: ListFilters): string {
  const params = new URLSearchParams();
  if (filters.category) params.set("category", filters.category);
  if (filters.tag) params.set("tag", filters.tag);
  if (filters.q) params.set("q", filters.q);
  if (filters.sort !== "latest") params.set("sort", filters.sort);
  if (filters.page > 1) params.set("page", String(filters.page));
  if (filters.includeHidden) params.set("includeHidden", "true");
  const query = params.toString();
  return query ? `/?${query}` : "/";
}

/** 필터 일부를 바꾼다. `page` 를 직접 주지 않으면 1쪽으로 돌아간다. undefined 값은 그 필터를 지운다. */
export function withFilters(current: ListFilters, patch: Partial<ListFilters>): ListFilters {
  const next: ListFilters = { ...current, page: 1, ...patch };
  for (const key of Object.keys(next) as (keyof ListFilters)[]) {
    if (next[key] === undefined) delete next[key];
  }
  return next;
}

export function listApiQuery(filters: ListFilters) {
  const search = validateSearch(filters.q);
  return {
    category: filters.category,
    tag: filters.tag,
    q: search.error ? undefined : search.q,
    sort: filters.sort,
    page: filters.page - 1,
    size: PAGE_SIZE,
    includeHidden: filters.includeHidden,
  };
}
