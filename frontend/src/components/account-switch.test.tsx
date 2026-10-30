import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { PostEditor } from "@/components/board/post-editor";
import { RequireAuth } from "@/components/guards";
import { draftKeys, loadDraft, saveDraft } from "@/lib/drafts";
import { queryKeys, SESSION_EXPIRED_KEY } from "@/lib/query";
import { categoriesHandler, commentsHandler, makeComment, makePost } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { makeMe, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

const A = makeMe({ id: 1, nickname: "에이" });
const B = makeMe({ id: 2, nickname: "비" });

/**
 * 다른 탭에서 계정이 바뀌는 상황. `state.me` 를 바꾼 뒤 me 를 다시 받으면(포커스·주기 재조회) 이 탭도 새 사용자를 본다.
 * 댓글 7 은 A 가 쓴 것이라 A 일 때만 editable 이다.
 */
function backend() {
  const state: { me: typeof A | null } = { me: A };
  return {
    state,
    handlers: [
      csrfHandler(),
      categoriesHandler(),
      http.get("*/api/auth/me", () =>
        state.me ? HttpResponse.json(state.me) : apiError(401, "unauthorized", "인증이 필요합니다"),
      ),
      http.get("*/api/posts/1", () => HttpResponse.json(makePost({ editable: state.me?.id === 1 }))),
      commentsHandler(1, () => [makeComment({ id: 7, editable: state.me?.id === 1 })]),
    ],
  };
}

describe("TASK-29#1 TASK-29#3 같은 탭에서 계정이 A→B 로 바뀌면 캐시를 리셋하고 작성 화면을 빈 상태로 다시 연다", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  it("TASK-29#1 TASK-29#3 글 편집기: A 의 입력이 B 의 초안이 되지 않고, 빈 편집기로 다시 열리며 A 의 초안은 지운다", async () => {
    const { state, handlers } = backend();
    server.use(...handlers);
    saveDraft(draftKeys.comment(5), A.id, { content: "A 의 다른 초안" });
    const { client } = renderWithQuery(
      <RequireAuth>
        <PostEditor />
      </RequireAuth>,
    );

    await fill(await screen.findByTestId("editor-title"), "A 가 쓰던 제목");
    await waitFor(() => expect(loadDraft(draftKeys.newPost, A.id)).toMatchObject({ title: "A 가 쓰던 제목" }));

    state.me = B;
    await act(() => client.refetchQueries({ queryKey: queryKeys.me }));

    await waitFor(() => expect(screen.getByTestId("editor-title")).toHaveValue(""));
    expect(screen.queryByTestId("draft-restored")).not.toBeInTheDocument();
    expect(screen.queryByTestId("session-lost-notice")).not.toBeInTheDocument();
    expect(client.getQueryData(SESSION_EXPIRED_KEY)).toBeFalsy();
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 600));
    });
    expect(loadDraft(draftKeys.newPost, B.id)).toBeNull();
    expect(loadDraft(draftKeys.newPost, A.id)).toBeNull();
    expect(loadDraft(draftKeys.comment(5), A.id)).toBeNull();
  });

  it("TASK-29#1 TASK-29#3 A 의 세션이 끝난 뒤 B 로 로그인돼도 A 의 입력을 B 편집기에 남기지 않는다", async () => {
    const { state, handlers } = backend();
    server.use(...handlers);
    const { client } = renderWithQuery(
      <RequireAuth>
        <PostEditor />
      </RequireAuth>,
    );
    await fill(await screen.findByTestId("editor-title"), "A 가 쓰던 제목");

    state.me = null;
    await act(() => client.refetchQueries({ queryKey: queryKeys.me }));
    await screen.findByTestId("session-lost-notice");
    expect(screen.getByTestId("editor-title")).toHaveValue("A 가 쓰던 제목");

    state.me = B;
    await act(() => client.refetchQueries({ queryKey: queryKeys.me }));
    await waitFor(() => expect(screen.getByTestId("editor-title")).toHaveValue(""));
    expect(screen.queryByTestId("session-lost-notice")).not.toBeInTheDocument();
    expect(loadDraft(draftKeys.newPost, B.id)).toBeNull();
    expect(loadDraft(draftKeys.newPost, A.id)).toBeNull();
  });

  it("TASK-29#1 TASK-29#3 댓글 폼·캐시: 입력이 비고, A 의 수정·삭제 버튼과 계정 캐시가 사라지며 A 의 댓글 초안을 지운다", async () => {
    navigation.reset("/posts/view/", "id=1");
    const { state, handlers } = backend();
    server.use(...handlers);
    const { client } = renderWithQuery(<PostDetailView />);
    client.setQueryData(queryKeys.identities, [{ provider: "LOCAL" }]);

    expect(await screen.findByTestId("comment-delete")).toBeInTheDocument();
    await fill(screen.getByTestId("comment-input"), "A 가 쓰던 댓글");
    await waitFor(() => expect(loadDraft(draftKeys.comment(1), A.id)).toEqual({ content: "A 가 쓰던 댓글" }));

    state.me = B;
    await act(() => client.refetchQueries({ queryKey: queryKeys.me }));

    await waitFor(() => expect(screen.getByTestId("comment-input")).toHaveValue(""));
    await waitFor(() => expect(screen.queryByTestId("comment-delete")).not.toBeInTheDocument());
    await waitFor(() => expect(screen.queryByTestId("post-delete-button")).not.toBeInTheDocument());
    expect(client.getQueryData(queryKeys.identities)).toBeUndefined();
    expect(client.getQueryData(SESSION_EXPIRED_KEY)).toBeFalsy();
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 600));
    });
    expect(loadDraft(draftKeys.comment(1), A.id)).toBeNull();
    expect(loadDraft(draftKeys.comment(1), B.id)).toBeNull();
  });
});
