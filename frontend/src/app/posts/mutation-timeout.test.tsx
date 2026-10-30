import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditor } from "@/components/board/post-editor";
import { RequireAuth } from "@/components/guards";
import { MUTATION_TIMEOUT_MS } from "@/lib/api";
import { draftKeys, loadDraft } from "@/lib/drafts";
import { categoriesHandler } from "@/test/board";
import { csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

describe("TASK-30#3 글 등록이 시간 초과돼도 편집기·입력·초안을 유지한다", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setTimeout", "clearTimeout"] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("TASK-30#3 응답이 35초 안에 오지 않으면 '처리됐는지 확인' 안내를 보이고 입력과 초안(TASK-29)을 남긴다", async () => {
    let posts = 0;
    server.use(
      csrfHandler(),
      categoriesHandler(),
      meHandler(makeMe()),
      http.post("*/api/posts", () => {
        posts++;
        return new Promise<Response>(() => {});
      }),
    );
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithQuery(
      <RequireAuth>
        <PostEditor />
      </RequireAuth>,
    );

    await user.click(await screen.findByTestId("editor-title"));
    await user.paste("쓰던 제목");
    await user.click(screen.getByTestId("editor-content"));
    await user.paste("쓰던 본문");
    await user.click(screen.getByTestId("editor-submit"));
    await vi.waitFor(() => expect(posts).toBe(1));

    await act(() => vi.advanceTimersByTimeAsync(MUTATION_TIMEOUT_MS));

    const error = await screen.findByTestId("editor-error");
    expect(error).toHaveTextContent("처리됐는지 확인하지 못했습니다");
    expect(screen.getByTestId("editor-title")).toHaveValue("쓰던 제목");
    expect(screen.getByTestId("editor-content")).toHaveValue("쓰던 본문");
    expect(screen.getByTestId("editor-submit")).toBeEnabled();
    expect(navigation.push).not.toHaveBeenCalled();
    expect(navigation.replace).not.toHaveBeenCalled();
    expect(loadDraft(draftKeys.newPost, 1)).toMatchObject({ title: "쓰던 제목", content: "쓰던 본문" });
    expect(posts).toBe(1);
  });
});
