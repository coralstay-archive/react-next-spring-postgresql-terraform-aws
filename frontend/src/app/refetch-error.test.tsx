import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostListView } from "@/app/post-list-view";
import { PostEditView } from "@/app/posts/edit/post-edit-view";
import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { ActionsTab } from "@/components/admin/actions-tab";
import { queryKeys } from "@/lib/query";
import {
  categoriesHandler,
  commentsHandler,
  makeComment,
  makePost,
  makeSummary,
  pageOf,
  tagsHandler,
} from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { anonymous, makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** 한 글자씩 치는 type 은 부하가 걸린 make test 에서 5초를 넘길 수 있어 붙여넣기로 채운다. */
async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

/** 처음엔 성공하고, `fail` 을 켜면 500 으로 실패하는 응답. */
function flaky(body: () => object) {
  const state = { fail: false, calls: 0 };
  const respond = () => {
    state.calls++;
    return state.fail ? apiError(500, "internal_error", "x") : HttpResponse.json(body() as Record<string, unknown>);
  };
  return { state, respond };
}

describe("TASK-30#4 재조회가 실패해도 기존 데이터를 그대로 두고 작은 오류 배너를 보인다", () => {
  it("TASK-30#4 목록: 재조회 500 이면 받은 글 목록을 유지하고 배너, '다시 시도'로 다시 받으면 배너가 사라진다", async () => {
    navigation.reset("/");
    const posts = flaky(() => pageOf([makeSummary({ id: 1, title: "있던 글" })]));
    server.use(anonymous(), categoriesHandler(), tagsHandler(), http.get("*/api/posts", posts.respond));
    const { client } = renderWithQuery(<PostListView />);
    await screen.findByText("있던 글");

    posts.state.fail = true;
    await act(() => client.refetchQueries({ queryKey: queryKeys.postLists }));

    const banner = await screen.findByTestId("post-list-refetch-error");
    expect(banner).toHaveTextContent("최신 내용을 불러오지 못했습니다");
    expect(banner).toHaveTextContent("서버에서 오류가 났습니다");
    expect(screen.getByText("있던 글")).toBeInTheDocument();
    expect(screen.queryByTestId("post-list-error")).not.toBeInTheDocument();

    posts.state.fail = false;
    await userEvent.click(within(banner).getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(screen.queryByTestId("post-list-refetch-error")).not.toBeInTheDocument());
    expect(screen.getByText("있던 글")).toBeInTheDocument();
  });

  it("TASK-30#4 처음 불러오기가 실패하면(데이터 없음) 지금처럼 오류 화면", async () => {
    navigation.reset("/");
    server.use(
      anonymous(),
      categoriesHandler(),
      tagsHandler(),
      http.get("*/api/posts", () => apiError(500, "internal_error", "x")),
    );
    renderWithQuery(<PostListView />);
    expect(await screen.findByTestId("post-list-error")).toHaveTextContent("서버에서 오류가 났습니다");
    expect(screen.queryByTestId("post-list-refetch-error")).not.toBeInTheDocument();
  });

  it("TASK-30#4 상세·댓글: 재조회 500 이면 글·댓글·댓글 입력을 유지하고 각각 배너", async () => {
    navigation.reset("/posts/view/", "id=1");
    const post = flaky(() => makePost({ id: 1, title: "있던 제목" }));
    const comments = flaky(() => pageOf([makeComment({ id: 7, content: "있던 댓글" })]));
    server.use(
      csrfHandler(),
      meHandler(makeMe()),
      http.get("*/api/posts/1", post.respond),
      http.get("*/api/posts/1/comments", comments.respond),
    );
    const { client } = renderWithQuery(<PostDetailView />);
    await screen.findByText("있던 댓글");
    await fill(screen.getByTestId("comment-input"), "쓰던 댓글");

    post.state.fail = true;
    comments.state.fail = true;
    await act(() => client.refetchQueries({ queryKey: queryKeys.posts }));

    expect(await screen.findByTestId("post-refetch-error")).toHaveTextContent("최신 내용을 불러오지 못했습니다");
    expect(await screen.findByTestId("comments-refetch-error")).toHaveTextContent("최신 내용을 불러오지 못했습니다");
    expect(screen.getByTestId("post-title")).toHaveTextContent("있던 제목");
    expect(screen.getByText("있던 댓글")).toBeInTheDocument();
    expect(screen.getByTestId("comment-input")).toHaveValue("쓰던 댓글");
    expect(screen.queryByTestId("post-error")).not.toBeInTheDocument();
    expect(screen.queryByTestId("comments-error")).not.toBeInTheDocument();
  });

  it("TASK-30#4 상세: 재조회가 404 면(그사이 삭제) 없는 글 안내로 바꾼다", async () => {
    navigation.reset("/posts/view/", "id=1");
    let gone = false;
    server.use(
      meHandler(makeMe()),
      http.get("*/api/posts/1", () => (gone ? apiError(404, "post_not_found", "x") : HttpResponse.json(makePost()))),
      commentsHandler(1, () => []),
    );
    const { client } = renderWithQuery(<PostDetailView />);
    await screen.findByTestId("post-title");

    gone = true;
    await act(() => client.refetchQueries({ queryKey: queryKeys.post(1) }));
    expect(await screen.findByTestId("post-not-found")).toBeInTheDocument();
  });

  it("TASK-30#4 세션 만료 리셋 뒤 상세 재조회가 실패해도 글(익명 표시)·댓글 폼·입력이 남는다(TASK-29 메모 사례)", async () => {
    navigation.reset("/posts/view/", "id=1");
    const state = { loggedIn: true, postFails: false };
    server.use(
      csrfHandler(),
      http.get("*/api/auth/me", () =>
        state.loggedIn ? HttpResponse.json(makeMe()) : apiError(401, "unauthorized", "인증이 필요합니다"),
      ),
      http.get("*/api/posts/1", () =>
        state.postFails
          ? apiError(503, "db_unavailable", "x")
          : HttpResponse.json(makePost({ id: 1, title: "있던 제목", editable: state.loggedIn })),
      ),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/comments", () => {
        state.loggedIn = false;
        state.postFails = true;
        return apiError(401, "unauthorized", "인증이 필요합니다");
      }),
    );
    renderWithQuery(<PostDetailView />);
    expect(await screen.findByTestId("post-edit-link")).toBeInTheDocument();

    await fill(await screen.findByTestId("comment-input"), "쓰던 댓글");
    await userEvent.click(screen.getByTestId("comment-submit"));

    expect(await screen.findByTestId("post-refetch-error")).toHaveTextContent("서비스에 일시적인 장애가 있습니다");
    expect(screen.getByTestId("post-title")).toHaveTextContent("있던 제목");
    expect(screen.queryByTestId("post-edit-link")).not.toBeInTheDocument();
    expect(screen.getByTestId("comment-input")).toHaveValue("쓰던 댓글");
    expect(screen.getByTestId("comment-create-error")).toHaveTextContent("로그인이 필요합니다");
    expect(screen.queryByTestId("post-error")).not.toBeInTheDocument();
  });

  it("TASK-30#4 수정 화면: 편집기를 열기 전(권한 없음 안내) 재조회가 실패해도 안내를 유지하고 배너", async () => {
    navigation.reset("/posts/edit/", "id=1");
    const post = flaky(() => makePost({ id: 1, editable: false }));
    server.use(meHandler(makeMe()), categoriesHandler(), http.get("*/api/posts/1", post.respond));
    const { client } = renderWithQuery(<PostEditView />);
    await screen.findByTestId("post-forbidden");

    post.state.fail = true;
    await act(() => client.refetchQueries({ queryKey: queryKeys.post(1) }));

    expect(await screen.findByTestId("post-refetch-error")).toBeInTheDocument();
    expect(screen.getByTestId("post-forbidden")).toBeInTheDocument();
    expect(screen.queryByTestId("post-error")).not.toBeInTheDocument();
  });

  it("TASK-30#4 관리자 감사 로그: 재조회가 실패해도 표를 유지하고 배너", async () => {
    const actions = flaky(() =>
      pageOf([
        {
          id: 1,
          adminId: 1,
          adminNickname: "관리자",
          action: "USER_SUSPEND",
          targetType: "USER",
          targetId: 5,
          reason: "스팸",
          createdAt: "2026-10-01T00:00:00Z",
        },
      ]),
    );
    server.use(meHandler(makeMe({ role: "ADMIN" })), http.get("*/api/admin/actions", actions.respond));
    const { client } = renderWithQuery(<ActionsTab />);
    await screen.findByTestId("admin-action-table");

    actions.state.fail = true;
    await act(() => client.refetchQueries({ queryKey: queryKeys.adminActions }));

    expect(await screen.findByTestId("admin-actions-refetch-error")).toBeInTheDocument();
    expect(screen.getByTestId("admin-action-table")).toBeInTheDocument();
    expect(screen.queryByTestId("admin-actions-error")).not.toBeInTheDocument();
  });
});

describe("TASK-30#4 데이터가 이미 있으면 재조회 실패로 화면을 바꾸지 않는다", () => {
  beforeEach(() => {
    navigation.reset("/");
  });

  it("TASK-30#4 목록 재조회가 네트워크 오류여도 목록과 페이지 이동이 남는다", async () => {
    let fail = false;
    server.use(
      anonymous(),
      categoriesHandler(),
      tagsHandler(),
      http.get("*/api/posts", () =>
        fail ? HttpResponse.error() : HttpResponse.json(pageOf([makeSummary()], { totalPages: 3, totalElements: 50 })),
      ),
    );
    const { client } = renderWithQuery(<PostListView />);
    await screen.findByTestId("post-list");

    fail = true;
    await act(() => client.refetchQueries({ queryKey: queryKeys.postLists }));

    expect(await screen.findByTestId("post-list-refetch-error")).toHaveTextContent("서버에 연결할 수 없습니다");
    expect(screen.getByTestId("post-list")).toBeInTheDocument();
    expect(screen.getByTestId("pagination-current")).toHaveTextContent("1 / 3");
  });
});
