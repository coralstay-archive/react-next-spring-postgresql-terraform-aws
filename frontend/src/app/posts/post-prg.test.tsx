import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditView } from "@/app/posts/edit/post-edit-view";
import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { PostEditor } from "@/components/board/post-editor";
import { draftKeys, loadDraft } from "@/lib/drafts";
import { queryKeys } from "@/lib/query";
import { categoriesHandler, commentsHandler, makePost, postHandler } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
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

/** POST /api/posts: 요청 수를 세고 201 + 상세. `delay` 동안 응답을 늦춘다. */
function countingCreate(counter: { posts: number }, id = 55) {
  return http.post("*/api/posts", async () => {
    counter.posts++;
    return HttpResponse.json(makePost({ id, editable: true }), { status: 201 });
  });
}

describe("TASK-31#2 PRG·이중 제출 방지(FE-29, FE-21)", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  it("TASK-31#2 TASK-31#B1 같은 틱에 두 번 제출해도(연속 클릭) 글은 하나만 만든다", async () => {
    const counter = { posts: 0 };
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), countingCreate(counter));
    renderWithQuery(<PostEditor />);

    await fillRequired();
    const submit = screen.getByTestId("editor-submit");
    // 상태가 다시 그려지기 전(버튼이 아직 활성)에 두 번 누른 것.
    fireEvent.click(submit);
    fireEvent.click(submit);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/posts/view/?id=55"));
    expect(counter.posts).toBe(1);
  });

  it("TASK-31#2 TASK-31#B1 Enter 를 연달아 눌러도 글은 하나만 만든다", async () => {
    const counter = { posts: 0 };
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), countingCreate(counter));
    renderWithQuery(<PostEditor />);

    await fillRequired();
    const title = screen.getByTestId("editor-title");
    fireEvent.submit(title.closest("form")!);
    fireEvent.submit(title.closest("form")!);
    await userEvent.type(title, "{Enter}");
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/posts/view/?id=55"));
    expect(counter.posts).toBe(1);
  });

  it("TASK-31#2 성공하면 replace 로 상세에 가고(편집기가 히스토리에 남지 않음), 버튼은 비활성으로 남아 다시 보내지 않으며 초안을 지운다", async () => {
    const counter = { posts: 0 };
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), countingCreate(counter));
    renderWithQuery(<PostEditor />);

    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/posts/view/?id=55"));
    expect(navigation.push).not.toHaveBeenCalled();
    expect(screen.getByTestId("editor-submit")).toBeDisabled();
    fireEvent.submit(screen.getByTestId("post-editor"));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(counter.posts).toBe(1);
    expect(loadDraft(draftKeys.newPost, 1)).toBeNull();
  });

  it("TASK-31#2 실패하면 잠금을 풀어 다시 저장할 수 있다", async () => {
    let posts = 0;
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/posts", () => {
        posts++;
        return posts === 1
          ? apiError(503, "service_unavailable")
          : HttpResponse.json(makePost({ id: 56, editable: true }), { status: 201 });
      }),
    );
    renderWithQuery(<PostEditor />);

    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));
    expect(await screen.findByTestId("editor-error")).toBeInTheDocument();
    expect(screen.getByTestId("editor-submit")).toBeEnabled();
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/posts/view/?id=56"));
    expect(posts).toBe(2);
  });

  it.each([
    [404, "post_not_found"],
    [403, "forbidden"],
  ])("TASK-31#2 수정 저장이 %i %s 면 상세 캐시를 무효화해 다시 받는다", async (status, code) => {
    navigation.reset("/posts/edit/", "id=3");
    let gets = 0;
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.get("*/api/posts/3", () => {
        gets++;
        return HttpResponse.json(makePost({ id: 3, editable: true }));
      }),
      http.patch("*/api/posts/3", () => apiError(status, code)),
    );
    const { client } = renderWithQuery(<PostEditView />);

    await userEvent.click(await screen.findByTestId("editor-submit"));
    expect(await screen.findByTestId("editor-error")).toBeInTheDocument();
    await waitFor(() => expect(gets).toBe(2));
    expect(client.getQueryState(queryKeys.post(3))?.status).toBe("success");
  });
});

describe("TASK-31#2 삭제(FE-21)", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  it("TASK-31#2 삭제가 404 면(그사이 삭제) 상세를 다시 받아 없는 글 안내로 바뀐다", async () => {
    let gone = false;
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      http.get("*/api/posts/1", () => (gone ? apiError(404, "post_not_found") : HttpResponse.json(makePost({ editable: true })))),
      commentsHandler(1, () => []),
      http.delete("*/api/posts/1", () => {
        gone = true;
        return apiError(404, "post_not_found");
      }),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    expect(await screen.findByTestId("post-not-found")).toBeInTheDocument();
  });

  it("TASK-31#2 삭제가 403 이면 상세를 다시 받아 수정·삭제 버튼이 사라진다", async () => {
    let denied = false;
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      http.get("*/api/posts/1", () => HttpResponse.json(makePost({ editable: !denied }))),
      commentsHandler(1, () => []),
      http.delete("*/api/posts/1", () => {
        denied = true;
        return apiError(403, "forbidden");
      }),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    await waitFor(() => expect(screen.queryByTestId("post-delete-button")).not.toBeInTheDocument());
  });

  it("TASK-31#2 삭제에 성공하면 replace 로 목록에 가고 버튼은 비활성으로 남는다", async () => {
    let deletes = 0;
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost({ editable: true })),
      commentsHandler(1, () => []),
      http.delete("*/api/posts/1", () => {
        deletes++;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/"));
    expect(screen.getByTestId("post-delete-button")).toBeDisabled();
    expect(deletes).toBe(1);
  });
});
