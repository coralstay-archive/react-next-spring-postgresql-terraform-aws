import { QueryClientProvider } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditView } from "@/app/posts/edit/post-edit-view";
import { PostEditor } from "@/components/board/post-editor";
import { RequireAdmin, RequireAuth } from "@/components/guards";
import { SessionExpiredBanner } from "@/components/session-expired-banner";
import { draftKeys, loadDraft, saveDraft } from "@/lib/drafts";
import { queryKeys } from "@/lib/query";
import { categoriesHandler, makePost } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** 한 글자씩 치는 type 은 부하가 걸린 make test 에서 5초를 넘길 수 있어 붙여넣기로 채운다. */
async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

/** 로그인 상태로 시작해, `expire()` 뒤에는 me 가 401 이고 쓰기도 401 인 백엔드. */
function backend(me = makeMe()) {
  const state = { loggedIn: true };
  return {
    state,
    handlers: [
      csrfHandler(),
      categoriesHandler(),
      http.get("*/api/auth/me", () =>
        state.loggedIn ? HttpResponse.json(me) : apiError(401, "unauthorized", "인증이 필요합니다"),
      ),
      http.get("*/api/posts/9", () => HttpResponse.json(makePost({ id: 9, title: "원래 제목", editable: state.loggedIn }))),
      http.post("*/api/posts", () => {
        state.loggedIn = false;
        return apiError(401, "unauthorized", "인증이 필요합니다");
      }),
      http.patch("*/api/posts/9", () => {
        state.loggedIn = false;
        return apiError(401, "unauthorized", "인증이 필요합니다");
      }),
    ],
  };
}

function NewPost() {
  return (
    <RequireAuth>
      <PostEditor />
    </RequireAuth>
  );
}

describe("TASK-29#3 세션이 끝나도 작성 화면을 유지하고 초안을 보존한다", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  afterEach(() => {
    window.sessionStorage.clear();
  });

  it("TASK-29#3 TASK-29#B1 글 등록이 401 이어도 편집기와 입력이 남고, 인라인 재로그인 안내와 초안이 있다", async () => {
    const { handlers } = backend();
    server.use(...handlers);
    renderWithQuery(<NewPost />);

    await fill(await screen.findByTestId("editor-title"), "쓰던 제목");
    await fill(screen.getByTestId("editor-content"), "쓰던 본문");
    await userEvent.click(screen.getByTestId("editor-submit"));

    const notice = await screen.findByTestId("session-lost-notice");
    expect(notice).toHaveTextContent("로그인이 끊겼습니다");
    expect(screen.getByRole("link", { name: "다시 로그인" })).toHaveAttribute(
      "href",
      `/login/?next=${encodeURIComponent("/posts/new/")}`,
    );
    expect(screen.getByTestId("editor-title")).toHaveValue("쓰던 제목");
    expect(screen.getByTestId("editor-content")).toHaveValue("쓰던 본문");
    expect(navigation.replace).not.toHaveBeenCalled();
    expect(loadDraft(draftKeys.newPost, 1)).toMatchObject({ title: "쓰던 제목", content: "쓰던 본문" });
  });

  it("TASK-29#3 TASK-29#B1 다시 로그인해 돌아오면 초안을 복원하고, 버리기로 지울 수 있다", async () => {
    saveDraft(draftKeys.newPost, 1, {
      title: "복원 제목",
      content: "복원 본문",
      categorySlug: "free",
      tags: ["java"],
      attachments: [],
    });
    server.use(...backend().handlers);
    renderWithQuery(<NewPost />);

    expect(await screen.findByTestId("editor-title")).toHaveValue("복원 제목");
    expect(screen.getByTestId("editor-content")).toHaveValue("복원 본문");
    expect(screen.getByText("#java")).toBeInTheDocument();
    expect(screen.getByTestId("draft-restored")).toHaveTextContent("임시 저장된 초안을 불러왔습니다");

    await userEvent.click(screen.getByRole("button", { name: "초안 버리기" }));
    expect(screen.getByTestId("editor-title")).toHaveValue("");
    expect(screen.queryByTestId("draft-restored")).not.toBeInTheDocument();
    await waitFor(() => expect(loadDraft(draftKeys.newPost, 1)).toBeNull());
  });

  it("TASK-29#3 다른 사용자로 로그인하면 남의 초안을 복원하지 않는다", async () => {
    saveDraft(draftKeys.newPost, 2, { title: "남의 제목", content: "남의 본문", categorySlug: "", tags: [], attachments: [] });
    server.use(...backend(makeMe({ id: 1 })).handlers);
    renderWithQuery(<NewPost />);

    expect(await screen.findByTestId("editor-title")).toHaveValue("");
    expect(screen.queryByTestId("draft-restored")).not.toBeInTheDocument();
  });

  it("TASK-29#3 등록에 성공하면 초안을 지운다", async () => {
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      categoriesHandler(),
      http.post("*/api/posts", () => HttpResponse.json(makePost({ id: 55, editable: true }), { status: 201 })),
    );
    renderWithQuery(<NewPost />);
    await fill(await screen.findByTestId("editor-title"), "제목");
    await fill(screen.getByTestId("editor-content"), "본문");
    await waitFor(() => expect(loadDraft(draftKeys.newPost, 1)).not.toBeNull());

    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(navigation.push).toHaveBeenCalled());
    expect(loadDraft(draftKeys.newPost, 1)).toBeNull();
  });

  it("TASK-29#3 TASK-29#B1 수정 중 401 이어도(익명 재조회가 editable=false 여도) 편집기를 유지하고 초안을 글별로 저장한다", async () => {
    navigation.reset("/posts/edit/", "id=9");
    server.use(...backend().handlers);
    renderWithQuery(
      <RequireAuth>
        <PostEditView />
      </RequireAuth>,
    );

    const title = await screen.findByTestId("editor-title");
    expect(title).toHaveValue("원래 제목");
    await fill(title, " 고침");
    await userEvent.click(screen.getByTestId("editor-submit"));

    await screen.findByTestId("session-lost-notice");
    // 리셋 뒤 익명으로 다시 받은 상세(editable=false)가 와도 권한 없음으로 바뀌지 않는다.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(screen.queryByTestId("post-forbidden")).not.toBeInTheDocument();
    expect(screen.getByTestId("editor-title")).toHaveValue("원래 제목 고침");
    expect(loadDraft(draftKeys.editPost(9), 1)).toMatchObject({ title: "원래 제목 고침" });
  });

  it("TASK-29#3 RequireAdmin 도 세션이 끝나면 내용(다이얼로그·사유)을 언마운트하지 않는다(FE-32)", async () => {
    navigation.reset("/admin/", "tab=users");
    server.use(meHandler(makeMe({ role: "ADMIN" })));
    const { client } = renderWithQuery(
      <RequireAdmin>
        <textarea aria-label="사유" defaultValue="쓰던 사유" />
      </RequireAdmin>,
    );
    expect(await screen.findByRole("textbox", { name: "사유" })).toHaveValue("쓰던 사유");

    act(() => client.setQueryData(queryKeys.me, null));

    expect(await screen.findByTestId("session-lost-notice")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "사유" })).toHaveValue("쓰던 사유");
    expect(screen.queryByTestId("admin-forbidden")).not.toBeInTheDocument();
    expect(navigation.replace).not.toHaveBeenCalled();
  });
});

describe("TASK-29#3 작성 화면에서는 세션 안내를 하단 고정 안내 하나만 보인다", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  it("TASK-29#3 상단 만료 배너와 오류 속 '로그인하기' 링크를 숨기고, 작성 화면을 떠나면 상단 배너가 다시 보인다", async () => {
    server.use(...backend().handlers);
    const { client, rerender } = renderWithQuery(
      <>
        <SessionExpiredBanner />
        <NewPost />
      </>,
    );

    await fill(await screen.findByTestId("editor-title"), "제목");
    await fill(screen.getByTestId("editor-content"), "본문");
    await userEvent.click(screen.getByTestId("editor-submit"));

    await screen.findByTestId("session-lost-notice");
    expect(await screen.findByTestId("editor-error")).toHaveTextContent("로그인이 필요합니다");
    expect(screen.queryByTestId("session-expired-banner")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "로그인하기" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /로그인/ })).toHaveLength(1);

    rerender(
      <QueryClientProvider client={client}>
        <SessionExpiredBanner />
      </QueryClientProvider>,
    );
    expect(await screen.findByTestId("session-expired-banner")).toBeInTheDocument();
  });
});

