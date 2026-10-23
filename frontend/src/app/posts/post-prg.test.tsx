import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditView } from "@/app/posts/edit/post-edit-view";
import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { PostEditor } from "@/components/board/post-editor";
import { queryKeys } from "@/lib/query";
import { categoriesHandler, commentsHandler, makePost } from "@/test/board";
import { csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** 한 글자씩 치는 type 은 부하가 걸린 make test 에서 느려 붙여넣기로 채운다. */
async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

/** push·replace 어느 쪽이든 그 주소로 갔는가(이동 방식은 TASK-31#2 가 따로 본다). */
function navigatedTo(href: string) {
  return [...navigation.push.mock.calls, ...navigation.replace.mock.calls].some((call) => call[0] === href);
}

async function fillRequired() {
  await fill(screen.getByTestId("editor-title"), "새 글");
  await fill(screen.getByTestId("editor-content"), "본문");
}

describe("TASK-31#1 작성 뒤 상세는 캐시 대신 다시 받는다(FE-19, TASK-18 '작성 직후 조회수 0')", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  it("TASK-31#1 생성 응답(조회수 0)으로 상세 캐시를 채우지 않아, 상세 화면이 GET 으로 받은 조회수를 보인다", async () => {
    let gets = 0;
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/posts", () => HttpResponse.json(makePost({ id: 55, viewCount: 0, editable: true }), { status: 201 })),
      http.get("*/api/posts/55", () => {
        gets++;
        return HttpResponse.json(makePost({ id: 55, viewCount: 1, editable: true }));
      }),
      commentsHandler(55, () => []),
    );
    const { client, unmount } = renderWithQuery(<PostEditor />);

    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(navigatedTo("/posts/view/?id=55")).toBe(true));
    expect(client.getQueryData(queryKeys.post(55))).toBeUndefined();
    unmount();

    navigation.reset("/posts/view/", "id=55");
    render(
      <QueryClientProvider client={client}>
        <PostDetailView />
      </QueryClientProvider>,
    );
    expect(await screen.findByTestId("view-count")).toHaveTextContent("1");
    expect(gets).toBe(1);
  });

  it("TASK-31#1 수정 성공은 응답으로 상세를 채우되 stale 로 표시해, 상세가 열리면 다시 받는다", async () => {
    navigation.reset("/posts/edit/", "id=3");
    const post = makePost({ id: 3, title: "원래 제목", editable: true, viewCount: 4 });
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.get("*/api/posts/3", () => HttpResponse.json(post)),
      http.patch("*/api/posts/3", () => HttpResponse.json({ ...post, title: "새 제목" })),
    );
    const { client } = renderWithQuery(<PostEditView />);

    await fill(await screen.findByTestId("editor-title"), " 더함");
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(navigatedTo("/posts/view/?id=3")).toBe(true));
    expect(client.getQueryData(queryKeys.post(3))).toMatchObject({ title: "새 제목" });
    expect(client.getQueryState(queryKeys.post(3))?.isInvalidated).toBe(true);
  });
});
