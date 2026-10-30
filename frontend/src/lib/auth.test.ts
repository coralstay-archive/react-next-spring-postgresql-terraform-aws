import { describe, expect, it } from "vitest";

import { safeNext } from "@/lib/auth";

// TASK-28#1 FE-1: `?next=` 는 new URL 기준 같은 오리진만, pathname+search+hash 만 쓴다.
// TASK-28#B1 로그인 후 이동은 언제나 같은 오리진이다.
describe("TASK-28#1 safeNext: 오픈 리다이렉트 차단", () => {
  const origin = window.location.origin;

  it.each([
    ["/", "/"],
    ["/posts/", "/posts/"],
    ["/posts/edit/?id=3", "/posts/edit/?id=3"],
    ["/admin/?tab=users#top", "/admin/?tab=users#top"],
    ["/account/#step-up", "/account/#step-up"],
    ["/a/../posts/", "/posts/"],
    ["/검색/?q=한글", "/%EA%B2%80%EC%83%89/?q=%ED%95%9C%EA%B8%80"],
    [`${origin}/posts/?id=1#c`, "/posts/?id=1#c"],
  ])("TASK-28#1 TASK-28#B1 같은 오리진 경로 %j 는 %j 로 쓴다", (next, expected) => {
    expect(safeNext(next)).toBe(expected);
  });

  it.each([
    // 다른 오리진·스킴
    "https://evil.com/",
    "http://evil.com",
    "//evil.com",
    "//evil.com/path",
    "///evil.com",
    "javascript:alert(1)",
    "data:text/html,<script>alert(1)</script>",
    `${origin}@evil.com/`,
    `${origin.replace("http", "https")}/posts/`,
    // 역슬래시(브라우저가 / 로 바꾼다)
    "/\\evil.com",
    "\\\\evil.com",
    "/\\/evil.com",
    "\\/evil.com",
    // 탭·개행·제어 문자(URL 파서가 지운다)
    "\t/evil.com",
    "/\t/evil.com",
    "/\n/evil.com",
    "/\r/evil.com",
    "/%09/evil.com\t",
    "\u0000/evil.com",
    "/\u007F/evil.com",
    // 인코딩 우회
    "/%2F/evil.com",
    "/%2f%2fevil.com",
    "/%5Cevil.com",
    "/%5cevil.com",
    "/%09/evil.com",
    "/%0A/evil.com",
    `${origin}//evil.com`,
    // 상대 경로·빈 값
    "evil.com",
    "posts/",
    "",
    " ",
  ])("TASK-28#1 TASK-28#B1 %j 는 거부하고 fallback", (next) => {
    expect(safeNext(next)).toBe("/");
    expect(safeNext(next, "/account/")).toBe("/account/");
  });

  it("TASK-28#1 null·undefined 는 fallback", () => {
    expect(safeNext(null)).toBe("/");
    expect(safeNext(undefined, "/x/")).toBe("/x/");
  });

  it("TASK-28#B1 허용된 결과는 언제나 '/' 로 시작하고 '//'·'/\\' 로 시작하지 않는다", () => {
    const samples = ["/", "/posts/?q=a", "/a//b", `${origin}/x#y`];
    for (const next of samples) {
      const result = safeNext(next);
      expect(result.startsWith("/")).toBe(true);
      expect(result.startsWith("//")).toBe(false);
      expect(result.startsWith("/\\")).toBe(false);
      expect(new URL(result, origin).origin).toBe(origin);
    }
  });
});
