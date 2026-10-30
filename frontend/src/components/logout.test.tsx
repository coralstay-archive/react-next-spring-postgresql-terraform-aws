import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditor } from "@/components/board/post-editor";
import { RequireAuth } from "@/components/guards";
import { SiteHeader } from "@/components/site-header";
import { draftKeys, loadDraft, saveDraft } from "@/lib/drafts";
import { queryKeys, SESSION_EXPIRED_KEY } from "@/lib/query";
import { categoriesHandler } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

describe("TASK-29#5 로그아웃은 성공(또는 401)일 때만 상태를 해제한다", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  it("TASK-29#5 로그아웃이 500 이면 오류를 보이고 로그인 상태·캐시·초안을 그대로 둔다", async () => {
    saveDraft(draftKeys.newPost, 1, { title: "초안" });
    server.use(
      csrfHandler(),
      meHandler(makeMe({ nickname: "철수" })),
      http.post("*/api/auth/logout", () => apiError(500, "internal_error", "서버 오류")),
    );
    const { client } = renderWithQuery(<SiteHeader />);
    client.setQueryData(queryKeys.categories, []);

    await userEvent.click(await screen.findByRole("button", { name: "로그아웃" }));

    const error = await screen.findByTestId("logout-error");
    expect(error).toHaveTextContent("로그아웃하지 못했습니다");
    expect(error).toHaveTextContent("서버에서 오류가 났습니다");
    expect(screen.getByText("철수")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "로그아웃" })).toBeEnabled();
    expect(navigation.push).not.toHaveBeenCalled();
    expect(client.getQueryData(queryKeys.categories)).toEqual([]);
    expect(loadDraft(draftKeys.newPost, 1)).toEqual({ title: "초안" });
  });

  it("TASK-29#5 네트워크 오류도 로그아웃된 것처럼 보이지 않는다", async () => {
    server.use(
      csrfHandler(),
      meHandler(makeMe({ nickname: "철수" })),
      http.post("*/api/auth/logout", () => HttpResponse.error()),
    );
    renderWithQuery(<SiteHeader />);
    await userEvent.click(await screen.findByRole("button", { name: "로그아웃" }));

    expect(await screen.findByTestId("logout-error")).toHaveTextContent("서버에 연결할 수 없습니다");
    expect(screen.getByText("철수")).toBeInTheDocument();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("TASK-29#5 로그아웃이 401 이면(이미 세션이 없음) 로그아웃 상태로 바꾸고 만료 안내는 띄우지 않는다", async () => {
    server.use(
      csrfHandler(),
      meHandler(makeMe({ nickname: "철수" })),
      http.post("*/api/auth/logout", () => apiError(401, "unauthorized", "인증이 필요합니다")),
    );
    const { client } = renderWithQuery(<SiteHeader />);
    await userEvent.click(await screen.findByRole("button", { name: "로그아웃" }));

    expect(await screen.findByRole("link", { name: "로그인" })).toBeInTheDocument();
    await waitFor(() => expect(navigation.push).toHaveBeenCalledWith("/"));
    expect(screen.queryByTestId("logout-error")).not.toBeInTheDocument();
    expect(client.getQueryData(SESSION_EXPIRED_KEY)).toBeFalsy();
  });

  it("TASK-29#5 로그아웃에 성공하면 초안을 모두 지운다(같은 탭의 다음 사용자에게 남기지 않는다)", async () => {
    saveDraft(draftKeys.newPost, 1, { title: "초안" });
    saveDraft(draftKeys.comment(3), 1, { content: "댓글" });
    server.use(
      csrfHandler(),
      meHandler(makeMe()),
      http.post("*/api/auth/logout", () => new HttpResponse(null, { status: 204 })),
    );
    renderWithQuery(<SiteHeader />);
    await userEvent.click(await screen.findByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(navigation.push).toHaveBeenCalledWith("/"));
    expect(loadDraft(draftKeys.newPost, 1)).toBeNull();
    expect(loadDraft(draftKeys.comment(3), 1)).toBeNull();
  });

  it("TASK-29#5 작성 화면에서 로그아웃하면 '임시 저장되어 있습니다' 안내를 띄우지 않고 초안도 다시 만들지 않는다", async () => {
    navigation.reset("/posts/new/");
    server.use(
      csrfHandler(),
      categoriesHandler(),
      meHandler(makeMe()),
      http.post("*/api/auth/logout", () => new HttpResponse(null, { status: 204 })),
    );
    renderWithQuery(
      <>
        <SiteHeader />
        <RequireAuth>
          <PostEditor />
        </RequireAuth>
      </>,
    );
    const title = await screen.findByTestId("editor-title");
    await userEvent.click(title);
    await userEvent.paste("로그아웃 직전 입력");
    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(navigation.push).toHaveBeenCalledWith("/"));
    expect(screen.queryByTestId("session-lost-notice")).not.toBeInTheDocument();
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 600));
    });
    expect(screen.queryByTestId("session-lost-notice")).not.toBeInTheDocument();
    expect(loadDraft(draftKeys.newPost, 1)).toBeNull();
  });
});
