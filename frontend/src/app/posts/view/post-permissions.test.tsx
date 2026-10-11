import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditView } from "@/app/posts/edit/post-edit-view";
import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { categoriesHandler, commentsHandler, makePost, postHandler } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { anonymous, makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

// TASK-14#4 작성자/ADMIN 에게만 수정·삭제 버튼. 판단은 서버가 준 editable 하나로 한다.
describe("TASK-14#4 수정·삭제 버튼 노출", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  it("TASK-14#4 editable=false 면 버튼이 없다(익명)", async () => {
    server.use(anonymous(), postHandler(makePost({ editable: false })), commentsHandler(1, () => []));
    renderWithQuery(<PostDetailView />);
    await screen.findByTestId("post-title");
    expect(screen.queryByTestId("post-edit-link")).not.toBeInTheDocument();
    expect(screen.queryByTestId("post-delete-button")).not.toBeInTheDocument();
  });

  it("TASK-14#4 editable=false 면 버튼이 없다(남의 글을 보는 USER)", async () => {
    server.use(meHandler(makeMe({ id: 99 })), postHandler(makePost({ editable: false })), commentsHandler(1, () => []));
    renderWithQuery(<PostDetailView />);
    await screen.findByTestId("post-title");
    expect(screen.queryByRole("link", { name: "수정" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "삭제" })).not.toBeInTheDocument();
  });

  it("TASK-14#4 editable=true 면 수정 링크와 삭제 버튼(작성자든 ADMIN 이든)", async () => {
    server.use(
      meHandler(makeMe({ role: "ADMIN", id: 50 })),
      postHandler(makePost({ editable: true })),
      commentsHandler(1, () => []),
    );
    renderWithQuery(<PostDetailView />);
    expect(await screen.findByRole("link", { name: "수정" })).toHaveAttribute("href", "/posts/edit/?id=1");
    expect(screen.getByRole("button", { name: "삭제" })).toBeInTheDocument();
  });
});

describe("TASK-14#4 삭제", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("TASK-14#4 확인하면 DELETE 하고 목록으로 간다", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    let deleted = false;
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost({ editable: true })),
      commentsHandler(1, () => []),
      http.delete("*/api/posts/1", () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    await waitFor(() => expect(navigation.push).toHaveBeenCalledWith("/"));
    expect(deleted).toBe(true);
  });

  it("TASK-14#4 취소하면 요청하지 않는다", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(false);
    server.use(meHandler(makeMe()), postHandler(makePost({ editable: true })), commentsHandler(1, () => []));
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it.each([
    [403, "forbidden", "권한이 없습니다."],
    [404, "post_not_found", "글을 찾을 수 없습니다"],
  ])("TASK-14#4 서버 %i %s 는 안내하고 머문다", async (status, code, text) => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost({ editable: true })),
      commentsHandler(1, () => []),
      http.delete("*/api/posts/1", () => apiError(status, code)),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    expect(await screen.findByTestId("post-delete-error")).toHaveTextContent(text);
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("TASK-14#4 제한 세션(step_up_required)이면 다시 인증 링크", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    server.use(
      meHandler(makeMe({ stepUpRequired: true })),
      csrfHandler(),
      postHandler(makePost({ editable: true })),
      commentsHandler(1, () => []),
      http.delete("*/api/posts/1", () => apiError(403, "step_up_required")),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    const alert = await screen.findByTestId("post-delete-error");
    expect(within(alert).getByRole("link", { name: "다시 인증하기" })).toHaveAttribute("href", "/account/#step-up");
  });
});

describe("TASK-14#4 수정 화면 권한", () => {
  beforeEach(() => {
    navigation.reset("/posts/edit/", "id=1");
  });

  it("TASK-14#4 editable=false 인 글은 폼 대신 권한 없음 안내", async () => {
    server.use(meHandler(makeMe()), categoriesHandler(), postHandler(makePost({ editable: false })));
    renderWithQuery(<PostEditView />);
    expect(await screen.findByTestId("post-forbidden")).toHaveTextContent("수정할 권한이 없습니다");
    expect(screen.queryByTestId("post-editor")).not.toBeInTheDocument();
  });

  it("TASK-14#4 저장이 403 forbidden 이면 안내한다", async () => {
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      postHandler(makePost({ editable: true })),
      http.patch("*/api/posts/1", () => apiError(403, "forbidden")),
    );
    renderWithQuery(<PostEditView />);
    await userEvent.click(await screen.findByTestId("editor-submit"));
    expect(await screen.findByTestId("editor-error")).toHaveTextContent("권한이 없습니다.");
  });
});
