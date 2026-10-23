import { describe, expect, it } from "vitest";

import {
  javaStrip,
  maxPageCount,
  MAX_LIST_PAGE,
  parseListPage,
  listApiQuery,
  listHref,
  parseListFilters,
  validateSearch,
  withFilters,
  PAGE_SIZE,
} from "@/lib/board-query";

// TASK-14#1 목록: URL 쿼리 ↔ 필터 ↔ API 쿼리 변환과 검색어 검증(서버 PostController.searchTerm 과 같은 규칙).
describe("TASK-14#1 목록 필터 ↔ URL", () => {
  it("TASK-14#1 빈 URL 은 기본값(최신순, 1쪽)이다", () => {
    expect(parseListFilters(new URLSearchParams())).toEqual({ sort: "latest", page: 1 });
  });

  it("TASK-14#1 URL 의 category·tag·q·sort·page 를 읽는다(page 는 1부터)", () => {
    const filters = parseListFilters(new URLSearchParams("category=free&tag=java&q=%20spring%20&sort=popular&page=3"));
    expect(filters).toEqual({ category: "free", tag: "java", q: "spring", sort: "popular", page: 3 });
  });

  it("TASK-14#1 잘못된 sort·page 는 기본값으로 바꾼다", () => {
    expect(parseListFilters(new URLSearchParams("sort=weird&page=0"))).toEqual({ sort: "latest", page: 1 });
    expect(parseListFilters(new URLSearchParams("page=abc"))).toEqual({ sort: "latest", page: 1 });
    expect(parseListFilters(new URLSearchParams("sort=POPULAR&page=2"))).toEqual({ sort: "popular", page: 2 });
  });

  it("TASK-14#1 includeHidden=true 만 켠다", () => {
    expect(parseListFilters(new URLSearchParams("includeHidden=true")).includeHidden).toBe(true);
    expect(parseListFilters(new URLSearchParams("includeHidden=1")).includeHidden).toBeUndefined();
  });

  it("TASK-14#1 필터 → 공유 가능한 URL(기본값은 생략, 순서 고정)", () => {
    expect(listHref({ sort: "latest", page: 1 })).toBe("/");
    expect(listHref({ category: "free", tag: "c++", q: "스프링 부트", sort: "popular", page: 2 })).toBe(
      "/?category=free&tag=c%2B%2B&q=%EC%8A%A4%ED%94%84%EB%A7%81+%EB%B6%80%ED%8A%B8&sort=popular&page=2",
    );
    expect(listHref({ sort: "latest", page: 1, includeHidden: true })).toBe("/?includeHidden=true");
  });

  it("TASK-14#1 URL → 필터 → URL 왕복이 같다", () => {
    const href = "/?category=free&tag=java&q=spring&sort=popular&page=2";
    expect(listHref(parseListFilters(new URLSearchParams(href.slice(2))))).toBe(href);
  });

  it("TASK-14#1 필터를 바꾸면 page 는 1로 돌아가고, page 를 직접 주면 그 값이다", () => {
    const current = { category: "free", sort: "popular" as const, page: 4 };
    expect(withFilters(current, { tag: "java" })).toEqual({ category: "free", tag: "java", sort: "popular", page: 1 });
    expect(withFilters(current, { category: undefined })).toEqual({ sort: "popular", page: 1 });
    expect(withFilters(current, { page: 5 })).toEqual({ category: "free", sort: "popular", page: 5 });
  });

  it("TASK-14#1 API 쿼리: page 는 0부터, size 고정, 유효하지 않은 q 는 빼고 보낸다", () => {
    expect(listApiQuery({ category: "free", tag: "java", q: "spring", sort: "popular", page: 2 })).toEqual({
      category: "free",
      tag: "java",
      q: "spring",
      sort: "popular",
      page: 1,
      size: PAGE_SIZE,
      includeHidden: undefined,
    });
    expect(listApiQuery({ q: "a", sort: "latest", page: 1 }).q).toBeUndefined();
    expect(listApiQuery({ sort: "latest", page: 1, includeHidden: true }).includeHidden).toBe(true);
  });
});

describe("TASK-14#1 검색어 검증", () => {
  it("TASK-14#1 앞뒤 공백을 자르고 2~100자(코드 포인트)만 받는다", () => {
    expect(validateSearch("  spring  ")).toEqual({ q: "spring" });
    expect(validateSearch("ab")).toEqual({ q: "ab" });
    expect(validateSearch("a").error).toBe("검색어는 2~100자여야 합니다");
    expect(validateSearch("가".repeat(100))).toEqual({ q: "가".repeat(100) });
    expect(validateSearch("가".repeat(101)).error).toBe("검색어는 2~100자여야 합니다");
    // 이모지는 UTF-16 2단위지만 코드 포인트로는 1자다.
    expect(validateSearch("😀".repeat(100))).toEqual({ q: "😀".repeat(100) });
    expect(validateSearch("😀").error).toBeDefined();
  });

  it("TASK-14#1 비었거나 공백뿐이면 검색하지 않는다(오류 아님)", () => {
    expect(validateSearch("")).toEqual({ q: undefined });
    expect(validateSearch("   \t ")).toEqual({ q: undefined });
  });

  it("TASK-14#1 javaStrip 은 Java String.strip 과 같이 NBSP 는 남긴다", () => {
    expect(javaStrip("　 a b \t\n")).toBe("a b");
    expect(javaStrip(" a ")).toBe(" a ");
  });
});

// TASK-33#4 서버 OFFSET 상한(page × size ≤ 10,000, TASK-23#5): 0부터 500쪽 = 1부터 501쪽까지만 요청할 수 있다.
describe("TASK-33#4 목록 OFFSET 상한", () => {
  it("TASK-33#4 상한 쪽 수는 floor(10000 / size) + 1 이다(page 0..floor(10000/size))", () => {
    expect(maxPageCount(PAGE_SIZE)).toBe(501);
    expect(maxPageCount(50)).toBe(201);
    expect(MAX_LIST_PAGE).toBe(501);
    expect((MAX_LIST_PAGE - 1) * PAGE_SIZE).toBeLessThanOrEqual(10_000);
    expect(MAX_LIST_PAGE * PAGE_SIZE).toBeGreaterThan(10_000);
  });

  it("TASK-33#4 상한을 넘는 URL page(아주 큰 수 포함)는 상한 쪽으로 자르고 잘렸음을 알린다", () => {
    expect(parseListPage("501")).toEqual({ page: 501, capped: false });
    expect(parseListPage("502")).toEqual({ page: 501, capped: true });
    expect(parseListPage("99999999999999999999999")).toEqual({ page: 501, capped: true });
    expect(parseListPage("abc")).toEqual({ page: 1, capped: false });
    expect(parseListPage(null)).toEqual({ page: 1, capped: false });
    expect(parseListFilters(new URLSearchParams("page=1000")).page).toBe(501);
    expect(listApiQuery(parseListFilters(new URLSearchParams("page=1000"))).page).toBe(500);
  });
});
