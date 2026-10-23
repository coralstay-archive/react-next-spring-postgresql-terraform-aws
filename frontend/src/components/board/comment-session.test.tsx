import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { draftKeys, loadDraft, saveDraft } from "@/lib/drafts";
import { commentsHandler, makeComment, makePost } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** 한 글자씩 치는 type 은 부하가 걸린 make test 에서 5초를 넘길 수 있어 붙여넣기로 채운다. */
async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

/** 로그인 상태로 시작하는 글 1 상세. `onComment` 가 댓글 POST 응답을 정한다. */
function detailBackend(onComment: (state: { loggedIn: boolean }) => Response) {
  const state = { loggedIn: true, posts: 0 };
  return {
    state,
    handlers: [
      csrfHandler(),
      http.get("*/api/auth/me", () =>
        state.loggedIn ? HttpResponse.json(makeMe()) : apiError(401, "unauthorized", "인증이 필요합니다"),
      ),
      http.get("*/api/posts/1", () => HttpResponse.json(makePost({ editable: state.loggedIn }))),
      commentsHandler(1, () => [makeComment({ id: 7, editable: state.loggedIn })]),
      http.post("*/api/posts/1/comments", () => {
        state.posts++;
        return onComment(state);
      }),
    ],
  };
}

describe("TASK-29#4 댓글 작성 중 세션이 끝나거나 정지돼도 폼·입력·오류를 유지한다", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  it("TASK-29#4 TASK-29#B1 댓글 401 이면 폼과 입력이 남고, 로그인 링크가 있는 오류를 보이며 초안을 저장한다", async () => {
    const { handlers } = detailBackend((state) => {
      state.loggedIn = false;
      return apiError(401, "unauthorized", "인증이 필요합니다");
    });
    server.use(...handlers);
    renderWithQuery(<PostDetailView />);

    await fill(await screen.findByTestId("comment-input"), "쓰던 댓글");
    await userEvent.click(screen.getByTestId("comment-submit"));

    const error = await screen.findByTestId("comment-create-error");
    expect(error).toHaveTextContent("로그인이 필요합니다");
    expect(screen.getByRole("link", { name: "로그인하기" })).toHaveAttribute(
      "href",
      `/login/?next=${encodeURIComponent("/posts/view/?id=1")}`,
    );
    // 리셋으로 익명 응답이 다시 와도(삭제 버튼이 사라진 뒤에도) 폼·입력·오류가 그대로다.
    await waitFor(() => expect(screen.queryByTestId("comment-delete")).not.toBeInTheDocument());
    expect(screen.getByTestId("comment-input")).toHaveValue("쓰던 댓글");
    expect(screen.getByTestId("comment-create-error")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "로그인하고 댓글 쓰기" })).not.toBeInTheDocument();
    expect(loadDraft(draftKeys.comment(1), 1)).toEqual({ content: "쓰던 댓글" });
  });

  it("TASK-29#4 정지된 사용자(403 suspended)도 폼·입력을 유지하고 정지 안내를 보인다", async () => {
    const { handlers } = detailBackend(() => apiError(403, "suspended", "정지된 계정입니다"));
    server.use(...handlers);
    renderWithQuery(<PostDetailView />);

    await fill(await screen.findByTestId("comment-input"), "정지 중 댓글");
    await userEvent.click(screen.getByTestId("comment-submit"));

    expect(await screen.findByTestId("comment-create-error")).toHaveTextContent("정지된 계정입니다");
    expect(screen.getByTestId("comment-input")).toHaveValue("정지 중 댓글");
  });

  it("TASK-29#4 TASK-29#B1 다시 로그인해 돌아오면 댓글 초안을 복원하고, 등록에 성공하면 지운다", async () => {
    saveDraft(draftKeys.comment(1), 1, { content: "복원할 댓글" });
    const { handlers } = detailBackend(() => HttpResponse.json(makeComment({ id: 8 }), { status: 201 }));
    server.use(...handlers);
    renderWithQuery(<PostDetailView />);

    expect(await screen.findByTestId("comment-input")).toHaveValue("복원할 댓글");
    await userEvent.click(screen.getByTestId("comment-submit"));

    await waitFor(() => expect(screen.getByTestId("comment-input")).toHaveValue(""));
    expect(loadDraft(draftKeys.comment(1), 1)).toBeNull();
  });

  it("TASK-29#4 다른 사용자가 남긴 댓글 초안은 복원하지 않는다", async () => {
    saveDraft(draftKeys.comment(1), 2, { content: "남의 댓글" });
    server.use(meHandler(makeMe({ id: 1 })), ...detailBackend(() => new HttpResponse(null, { status: 500 })).handlers);
    renderWithQuery(<PostDetailView />);
    expect(await screen.findByTestId("comment-input")).toHaveValue("");
  });

  it("TASK-29#4 같은 틱에 두 번 제출해도 댓글 요청은 하나다(이중 제출 차단)", async () => {
    const { state, handlers } = detailBackend(() => HttpResponse.json(makeComment({ id: 8 }), { status: 201 }));
    server.use(...handlers);
    renderWithQuery(<PostDetailView />);

    await fill(await screen.findByTestId("comment-input"), "한 번만");
    const form = screen.getByRole("form", { name: "댓글 쓰기" }) as HTMLFormElement;
    form.requestSubmit();
    form.requestSubmit();

    await waitFor(() => expect(screen.getByTestId("comment-input")).toHaveValue(""));
    expect(state.posts).toBe(1);
  });
});
