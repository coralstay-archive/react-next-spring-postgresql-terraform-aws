import { useMutation } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { SessionExpiredBanner } from "@/components/session-expired-banner";
import { api } from "@/lib/api";
import { queryKeys, SESSION_EXPIRED_KEY } from "@/lib/query";
import { commentsHandler, makeComment, makePost } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { makeMe, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/**
 * 로그인 상태가 서버에서 끝나는 상황을 흉내 낸다. `expire()` 뒤에는 me 가 401 이고 글·댓글은 익명 응답(editable=false)이다.
 */
function sessionBackend() {
  const state = { loggedIn: true, meCalls: 0 };
  const handlers = [
    csrfHandler(),
    http.get("*/api/auth/me", () => {
      state.meCalls++;
      return state.loggedIn ? HttpResponse.json(makeMe()) : apiError(401, "unauthorized", "인증이 필요합니다");
    }),
    http.get("*/api/posts/1", () => HttpResponse.json(makePost({ editable: state.loggedIn }))),
    commentsHandler(1, () => [makeComment({ id: 7, editable: state.loggedIn })]),
    http.delete("*/api/comments/7", () => {
      state.loggedIn = false;
      return apiError(401, "unauthorized", "인증이 필요합니다");
    }),
  ];
  return { state, handlers };
}

describe("TASK-29#1 세션 만료 401: me 외 쿼리 리셋 + 전역 안내", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("TASK-29#1 TASK-29#B2 쓰기가 401 이면 남아 있던 수정·삭제 버튼이 사라진다(로그아웃 상태에서는 보이지 않는다)", async () => {
    const { handlers } = sessionBackend();
    server.use(...handlers);
    renderWithQuery(<PostDetailView />);

    expect(await screen.findByTestId("comment-delete")).toBeInTheDocument();
    expect(screen.getByTestId("post-delete-button")).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("comment-delete"));

    await waitFor(() => expect(screen.queryByTestId("comment-delete")).not.toBeInTheDocument());
    await waitFor(() => expect(screen.queryByTestId("post-delete-button")).not.toBeInTheDocument());
    expect(screen.queryByTestId("post-edit-link")).not.toBeInTheDocument();
  });

  it("TASK-29#1 만료되면 '세션이 만료되었습니다' 전역 안내와 지금 경로로 돌아오는 로그인 링크를 보인다", async () => {
    const { handlers } = sessionBackend();
    server.use(...handlers);
    renderWithQuery(
      <>
        <SessionExpiredBanner />
        <PostDetailView />
      </>,
    );
    expect(screen.queryByText(/세션이 만료되었습니다/)).not.toBeInTheDocument();

    await userEvent.click(await screen.findByTestId("comment-delete"));

    const banner = await screen.findByTestId("session-expired-banner");
    expect(banner).toHaveTextContent("세션이 만료되었습니다");
    expect(screen.getByRole("link", { name: "다시 로그인" })).toHaveAttribute(
      "href",
      `/login/?next=${encodeURIComponent("/posts/view/?id=1")}`,
    );

    await userEvent.click(screen.getByRole("button", { name: "안내 닫기" }));
    expect(screen.queryByTestId("session-expired-banner")).not.toBeInTheDocument();
  });

  it("TASK-29#1 me 쿼리는 리셋하지 않고 null 로 두며, 리셋 뒤 다시 401 이 나도 반복하지 않는다", async () => {
    const { state, handlers } = sessionBackend();
    server.use(...handlers);
    const { client } = renderWithQuery(<PostDetailView />);
    await userEvent.click(await screen.findByTestId("comment-delete"));
    await waitFor(() => expect(client.getQueryData(SESSION_EXPIRED_KEY)).toBe(true));

    expect(client.getQueryData(queryKeys.me)).toBeNull();
    const before = state.meCalls;
    await waitFor(() => expect(screen.queryByTestId("comment-delete")).not.toBeInTheDocument());
    expect(state.meCalls).toBe(before);
  });

  function Probe({ path }: { path: string }) {
    const mutation = useMutation({ mutationFn: () => api(path, { method: "POST" }) });
    return (
      <button type="button" onClick={() => mutation.mutate()}>
        보내기
      </button>
    );
  }

  it("TASK-29#1 틀린 비밀번호(401 invalid_credentials)는 세션 만료가 아니다", async () => {
    server.use(
      csrfHandler(),
      http.post("*/api/probe", () => apiError(401, "invalid_credentials", "틀림")),
    );
    const { client } = renderWithQuery(<Probe path="/probe" />);
    client.setQueryData(queryKeys.me, makeMe());
    client.setQueryData(queryKeys.categories, []);

    await userEvent.click(screen.getByRole("button", { name: "보내기" }));
    await act(async () => {});

    expect(client.getQueryData(queryKeys.me)).toEqual(makeMe());
    expect(client.getQueryData(queryKeys.categories)).toEqual([]);
    expect(client.getQueryData(SESSION_EXPIRED_KEY)).toBeUndefined();
  });

  it("TASK-29#1 처음부터 익명(me=null)이면 401 이 나도 만료 안내·리셋을 하지 않는다", async () => {
    let called = false;
    server.use(
      csrfHandler(),
      http.post("*/api/probe", () => {
        called = true;
        return apiError(401, "unauthorized", "인증이 필요합니다");
      }),
    );
    const { client } = renderWithQuery(<Probe path="/probe" />);
    client.setQueryData(queryKeys.me, null);
    client.setQueryData(queryKeys.categories, []);

    await userEvent.click(screen.getByRole("button", { name: "보내기" }));
    await waitFor(() => expect(called).toBe(true));
    await act(async () => {});

    expect(client.getQueryData(queryKeys.categories)).toEqual([]);
    expect(client.getQueryData(SESSION_EXPIRED_KEY)).toBeUndefined();
  });

  it("TASK-29#1 다시 로그인하면(me 가 사용자로 바뀌면) 만료 안내를 내린다", async () => {
    const { state, handlers } = sessionBackend();
    server.use(...handlers);
    const { client } = renderWithQuery(
      <>
        <SessionExpiredBanner />
        <PostDetailView />
      </>,
    );
    await userEvent.click(await screen.findByTestId("comment-delete"));
    await screen.findByTestId("session-expired-banner");

    state.loggedIn = true;
    act(() => client.setQueryData(queryKeys.me, makeMe()));
    await waitFor(() => expect(screen.queryByTestId("session-expired-banner")).not.toBeInTheDocument());
  });
});
