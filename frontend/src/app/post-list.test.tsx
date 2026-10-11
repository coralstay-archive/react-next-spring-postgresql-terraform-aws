import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostListView } from "@/app/post-list-view";
import { categoriesHandler, makeSummary, pageOf, tagsHandler } from "@/test/board";
import { server } from "@/test/server";
import { anonymous, makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** GET /api/posts: 받은 쿼리를 기록하고 주어진 페이지를 돌려준다. */
function postsHandler(calls: URLSearchParams[], body = pageOf([makeSummary()])) {
  return http.get("*/api/posts", ({ request }) => {
    calls.push(new URL(request.url).searchParams);
    return HttpResponse.json(body);
  });
}

// TASK-14#1 목록(카테고리·태그·검색·정렬·페이징). 상태는 URL 쿼리와 동기화한다.
describe("TASK-14#1 게시판 목록", () => {
  beforeEach(() => {
    navigation.reset("/");
  });

  it("TASK-14#1 URL 쿼리로 /api/posts 요청을 만든다(page 는 0부터)", async () => {
    navigation.reset("/", "category=free&tag=java&q=spring&sort=popular&page=2");
    const calls: URLSearchParams[] = [];
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler(calls));
    renderWithQuery(<PostListView />);

    await screen.findByTestId("post-item");
    const query = calls.at(-1)!;
    expect(Object.fromEntries(query)).toEqual({
      category: "free",
      tag: "java",
      q: "spring",
      sort: "popular",
      page: "1",
      size: "20",
    });
  });

  it("TASK-14#1 글 항목은 상세 링크와 작성자·카테고리·태그·좋아요·조회수를 보인다", async () => {
    const calls: URLSearchParams[] = [];
    server.use(
      anonymous(),
      categoriesHandler(),
      tagsHandler(),
      postsHandler(
        calls,
        pageOf([makeSummary({ id: 7, title: "첫 글", likeCount: 3, viewCount: 12, tags: ["java", "spring"] })]),
      ),
    );
    renderWithQuery(<PostListView />);

    const item = await screen.findByTestId("post-item");
    expect(within(item).getByRole("link", { name: "첫 글" })).toHaveAttribute("href", "/posts/view/?id=7");
    expect(within(item).getByText("작성자")).toBeInTheDocument();
    expect(within(item).getByText("자유게시판")).toBeInTheDocument();
    expect(within(item).getByText("#java")).toBeInTheDocument();
    expect(within(item).getByTestId("post-item-likes")).toHaveTextContent("3");
    expect(within(item).getByTestId("post-item-views")).toHaveTextContent("12");
  });

  it("TASK-14#1 카테고리 탭은 다른 필터를 유지하고 page 를 1로 돌린 링크다", async () => {
    navigation.reset("/", "q=spring&sort=popular&page=3");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([]));
    renderWithQuery(<PostListView />);

    const tabs = await screen.findByRole("navigation", { name: "카테고리" });
    const free = await within(tabs).findByRole("link", { name: "자유게시판" });
    expect(free).toHaveAttribute("href", "/?category=free&q=spring&sort=popular");
    expect(within(tabs).getByRole("link", { name: "전체" })).toHaveAttribute("aria-current", "page");
    expect(within(tabs).getByRole("link", { name: "공지" })).toHaveAttribute("href", "/?category=notice&q=spring&sort=popular");
  });

  it("TASK-14#1 선택된 카테고리 탭은 aria-current 다", async () => {
    navigation.reset("/", "category=free");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([]));
    renderWithQuery(<PostListView />);

    const tabs = await screen.findByRole("navigation", { name: "카테고리" });
    expect(await within(tabs).findByRole("link", { name: "자유게시판" })).toHaveAttribute("aria-current", "page");
    expect(within(tabs).getByRole("link", { name: "전체" })).toHaveAttribute("href", "/");
  });

  it("TASK-14#1 인기 태그 칩은 태그 필터 링크이고, 선택된 태그는 해제 링크가 있다", async () => {
    navigation.reset("/", "tag=java&sort=popular");
    server.use(anonymous(), categoriesHandler(), tagsHandler([{ name: "spring", postCount: 4 }]), postsHandler([]));
    renderWithQuery(<PostListView />);

    const chips = await screen.findByRole("navigation", { name: "인기 태그" });
    expect(await within(chips).findByRole("link", { name: /#spring/ })).toHaveAttribute(
      "href",
      "/?tag=spring&sort=popular",
    );
    expect(screen.getByRole("link", { name: "태그 java 필터 해제" })).toHaveAttribute("href", "/?sort=popular");
  });

  it("TASK-14#1 검색: 앞뒤 공백을 자르고 URL 로 이동한다(page 는 1로)", async () => {
    navigation.reset("/", "category=free&page=3");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([]));
    renderWithQuery(<PostListView />);

    await userEvent.type(screen.getByRole("searchbox", { name: "검색어" }), "  spring boot  ");
    await userEvent.click(screen.getByRole("button", { name: "검색" }));
    expect(navigation.push).toHaveBeenCalledWith("/?category=free&q=spring+boot");
  });

  it("TASK-14#1 검색: 1자는 안내만 하고 이동하지 않는다", async () => {
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([]));
    renderWithQuery(<PostListView />);

    await userEvent.type(screen.getByRole("searchbox", { name: "검색어" }), "a");
    await userEvent.click(screen.getByRole("button", { name: "검색" }));
    expect(await screen.findByText("검색어는 2~100자여야 합니다")).toBeInTheDocument();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("TASK-14#1 검색: 비우고 검색하면 q 를 뺀다", async () => {
    navigation.reset("/", "q=spring&sort=popular");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([]));
    renderWithQuery(<PostListView />);

    const box = screen.getByRole("searchbox", { name: "검색어" });
    expect(box).toHaveValue("spring");
    await userEvent.clear(box);
    await userEvent.click(screen.getByRole("button", { name: "검색" }));
    expect(navigation.push).toHaveBeenCalledWith("/?sort=popular");
  });

  it("TASK-14#1 URL 의 q 가 1자면 q 없이 요청하고 안내한다", async () => {
    navigation.reset("/", "q=a");
    const calls: URLSearchParams[] = [];
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler(calls));
    renderWithQuery(<PostListView />);

    await screen.findByTestId("post-item");
    expect(calls.at(-1)!.has("q")).toBe(false);
    expect(screen.getByText("검색어는 2~100자여야 합니다")).toBeInTheDocument();
  });

  it("TASK-14#1 정렬을 바꾸면 URL 로 이동한다(page 는 1로)", async () => {
    navigation.reset("/", "tag=java&page=2");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([]));
    renderWithQuery(<PostListView />);

    await userEvent.selectOptions(screen.getByRole("combobox", { name: "정렬" }), "popular");
    expect(navigation.push).toHaveBeenCalledWith("/?tag=java&sort=popular");
  });

  it("TASK-14#1 페이지네이션: 이전·다음 링크와 현재 쪽", async () => {
    navigation.reset("/", "category=free&page=2");
    server.use(
      anonymous(),
      categoriesHandler(),
      tagsHandler(),
      postsHandler([], pageOf([makeSummary()], { page: 1, totalPages: 3, totalElements: 45 })),
    );
    renderWithQuery(<PostListView />);

    const pager = await screen.findByRole("navigation", { name: "페이지" });
    await within(pager).findByText("2 / 3");
    expect(within(pager).getByRole("link", { name: "이전" })).toHaveAttribute("href", "/?category=free");
    expect(within(pager).getByRole("link", { name: "다음" })).toHaveAttribute("href", "/?category=free&page=3");
  });

  it("TASK-14#1 마지막 쪽에는 다음 링크가 없다", async () => {
    navigation.reset("/", "page=3");
    server.use(
      anonymous(),
      categoriesHandler(),
      tagsHandler(),
      postsHandler([], pageOf([makeSummary()], { page: 2, totalPages: 3 })),
    );
    renderWithQuery(<PostListView />);

    const pager = await screen.findByRole("navigation", { name: "페이지" });
    await within(pager).findByText("3 / 3");
    expect(within(pager).queryByRole("link", { name: "다음" })).not.toBeInTheDocument();
  });

  it("TASK-14#1 글이 없으면 안내한다", async () => {
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([], pageOf([])));
    renderWithQuery(<PostListView />);
    expect(await screen.findByText("글이 없습니다.")).toBeInTheDocument();
  });

  it("TASK-14#1 목록 오류(400 validation)는 서버 문장으로 보인다", async () => {
    server.use(
      anonymous(),
      categoriesHandler(),
      tagsHandler(),
      http.get("*/api/posts", () =>
        HttpResponse.json({ status: 400, error: "validation", message: "잘못된 값" }, { status: 400 }),
      ),
    );
    renderWithQuery(<PostListView />);
    expect(await screen.findByRole("alert")).toHaveTextContent("입력값을 확인하세요.");
  });

  it("TASK-14#1 글쓰기 링크가 있다", async () => {
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsHandler([]));
    renderWithQuery(<PostListView />);
    expect(screen.getByRole("link", { name: "글쓰기" })).toHaveAttribute("href", "/posts/new/");
  });

  it("TASK-14#1 ADMIN 은 숨김·삭제 글 포함 토글과 배지를 본다", async () => {
    navigation.reset("/", "includeHidden=true");
    const calls: URLSearchParams[] = [];
    server.use(
      meHandler(makeMe({ role: "ADMIN" })),
      categoriesHandler(),
      tagsHandler(),
      postsHandler(calls, pageOf([makeSummary({ id: 1, hidden: true }), makeSummary({ id: 2, deleted: true })])),
    );
    renderWithQuery(<PostListView />);

    const toggle = await screen.findByRole("checkbox", { name: "숨김·삭제 글 포함" });
    expect(toggle).toBeChecked();
    await waitFor(() => expect(screen.getAllByTestId("post-item")).toHaveLength(2));
    expect(calls.at(-1)!.get("includeHidden")).toBe("true");
    expect(screen.getByText("숨김")).toBeInTheDocument();
    expect(screen.getByText("삭제됨")).toBeInTheDocument();
    await userEvent.click(toggle);
    expect(navigation.push).toHaveBeenCalledWith("/");
  });

  it("TASK-14#1 USER 에게는 숨김 토글이 없다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler(), tagsHandler(), postsHandler([]));
    renderWithQuery(<PostListView />);
    await screen.findByTestId("post-item");
    expect(screen.queryByRole("checkbox", { name: "숨김·삭제 글 포함" })).not.toBeInTheDocument();
  });
});
