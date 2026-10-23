import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostListView } from "@/app/post-list-view";
import { categoriesHandler, makeSummary, pageOf, tagsHandler } from "@/test/board";
import { apiError, server } from "@/test/server";
import { anonymous, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

function postsHandler(calls: URLSearchParams[], body = pageOf([makeSummary()])) {
  return http.get("*/api/posts", ({ request }) => {
    calls.push(new URL(request.url).searchParams);
    return HttpResponse.json(body);
  });
}

// TASK-33#2 (FE-22) 목록 쿼리 엣지: page 클램프, 오류 시 첫 페이지 링크, 잘못된 q 안내, 카테고리·태그 오류 표시.
describe("TASK-33#2 목록 쿼리 엣지", () => {
  beforeEach(() => {
    navigation.reset("/");
  });

  it("TASK-33#2 page 가 정수가 아니면(음수·소수·지수·문자) 1쪽(API page=0)으로 요청한다", async () => {
    for (const raw of ["-1", "1.5", "1e3", "abc", "0", " 2"]) {
      navigation.reset("/", `page=${encodeURIComponent(raw)}`);
      const calls: URLSearchParams[] = [];
      server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler(calls));
      const { unmount } = renderWithQuery(<PostListView />);
      await screen.findByTestId("post-item");
      expect(calls.at(-1)!.get("page"), raw).toBe("0");
      unmount();
    }
  });

  it("TASK-33#2 totalPages 를 알고 나서 page 가 범위 밖이면 마지막 쪽으로 URL 을 바꾼다(다른 필터 유지)", async () => {
    navigation.reset("/", "category=free&page=9");
    server.use(
      anonymous(),
      categoriesHandler(),
      tagsHandler(),
      postsHandler([], pageOf([], { page: 8, totalPages: 3, totalElements: 45 })),
    );
    renderWithQuery(<PostListView />);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/?category=free&page=3"));
  });

  it("TASK-33#2 결과가 0건인데 page 가 2 이상이면 1쪽으로 URL 을 바꾼다", async () => {
    navigation.reset("/", "q=spring&page=4");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([], pageOf([], { page: 3, totalPages: 0 })));
    renderWithQuery(<PostListView />);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/?q=spring"));
  });

  it("TASK-33#2 범위 안의 page 는 URL 을 바꾸지 않는다", async () => {
    navigation.reset("/", "page=3");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([], pageOf([makeSummary()], { page: 2, totalPages: 3 })));
    renderWithQuery(<PostListView />);
    await screen.findByTestId("post-item");
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("TASK-33#2 목록 오류(데이터 없음)에는 필터를 모두 뺀 '첫 페이지로' 링크가 있다", async () => {
    navigation.reset("/", "tag=java&page=2");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), http.get("*/api/posts", () => apiError(500, "internal")));
    renderWithQuery(<PostListView />);
    await screen.findByTestId("post-list-error");
    expect(screen.getByRole("link", { name: "첫 페이지로" })).toHaveAttribute("href", "/");
  });

  it("TASK-33#2 이미 첫 페이지(/)의 오류에는 '첫 페이지로' 링크를 두지 않는다", async () => {
    server.use(anonymous(), categoriesHandler(), tagsHandler(), http.get("*/api/posts", () => apiError(500, "internal")));
    renderWithQuery(<PostListView />);
    await screen.findByTestId("post-list-error");
    expect(screen.queryByRole("link", { name: "첫 페이지로" })).not.toBeInTheDocument();
  });

  it("TASK-33#2 URL 의 q 가 2~100자가 아니면 '검색어 무시' 안내를 보이고 q 없이 요청한다", async () => {
    for (const q of ["a", "가".repeat(101)]) {
      navigation.reset("/", `q=${encodeURIComponent(q)}`);
      const calls: URLSearchParams[] = [];
      server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler(calls));
      const { unmount } = renderWithQuery(<PostListView />);
      await screen.findByTestId("post-item");
      expect(calls.at(-1)!.has("q")).toBe(false);
      const notice = screen.getByTestId("search-error");
      expect(notice).toHaveTextContent("검색어 무시");
      expect(notice).toHaveTextContent("검색어는 2~100자여야 합니다");
      expect(notice).toHaveTextContent("검색어 없이 목록을 보여 줍니다");
      unmount();
    }
  });

  it("TASK-33#2 카테고리 목록 조회가 실패하면 탭 자리에 안내와 다시 시도를 보인다", async () => {
    let fail = true;
    server.use(
      anonymous(),
      http.get("*/api/categories", () => (fail ? apiError(500, "internal") : HttpResponse.json([{ id: 2, slug: "free", name: "자유게시판", sortOrder: 1, adminOnly: false }]))),
      tagsHandler(),
      postsHandler([]),
    );
    renderWithQuery(<PostListView />);
    const error = await screen.findByTestId("category-tabs-error");
    expect(error).toHaveTextContent("카테고리를 불러오지 못했습니다");
    // "전체" 탭은 그대로 쓸 수 있다.
    expect(within(screen.getByRole("navigation", { name: "카테고리" })).getByRole("link", { name: "전체" })).toBeInTheDocument();
    fail = false;
    await userEvent.click(within(error).getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByRole("link", { name: "자유게시판" })).toBeInTheDocument();
    expect(screen.queryByTestId("category-tabs-error")).not.toBeInTheDocument();
  });

  it("TASK-33#2 인기 태그 조회가 실패하면 안내와 다시 시도를 보인다", async () => {
    let fail = true;
    server.use(
      anonymous(),
      categoriesHandler(),
      http.get("*/api/tags", () => (fail ? apiError(500, "internal") : HttpResponse.json([{ name: "spring", postCount: 2 }]))),
      postsHandler([]),
    );
    renderWithQuery(<PostListView />);
    const error = await screen.findByTestId("tag-chips-error");
    expect(error).toHaveTextContent("인기 태그를 불러오지 못했습니다");
    fail = false;
    await userEvent.click(within(error).getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByRole("link", { name: /#spring/ })).toBeInTheDocument();
    expect(screen.queryByTestId("tag-chips-error")).not.toBeInTheDocument();
  });
});
