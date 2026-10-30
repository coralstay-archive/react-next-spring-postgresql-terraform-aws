import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostDetailView } from "@/app/posts/view/post-detail-view";
import type { PostDetail } from "@/lib/board";
import { queryKeys } from "@/lib/query";
import { commentsHandler, makePost } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((r) => (resolve = r));
  return { promise, resolve };
}

// TASK-33#1 (FE-20) 좋아요 롤백: 이전 스냅숏 전체가 아니라 좋아요 필드만 최신 캐시에 되돌리고, 404 면 상세를 다시 받는다.
describe("TASK-33#1 좋아요 롤백", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  it("TASK-33#1 실패 롤백은 likedByMe·likeCount 만 되돌리고 그사이 바뀐 제목·조회수는 유지한다", async () => {
    const gate = deferred();
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      http.get("*/api/posts/1", () => HttpResponse.json(makePost({ title: "옛 제목", likeCount: 3, viewCount: 5 }))),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/like", async () => {
        await gate.promise;
        return apiError(500, "internal", "서버 오류");
      }),
    );
    const { client } = renderWithQuery(<PostDetailView />);

    const button = await screen.findByTestId("like-button");
    await userEvent.click(button);
    expect(screen.getByTestId("like-count")).toHaveTextContent("4");

    // 요청이 도는 사이 상세가 다시 받아져 제목·조회수가 바뀌었다(좋아요 필드는 낙관적 값 그대로).
    client.setQueryData<PostDetail>(queryKeys.post(1), (old) => old && { ...old, title: "새 제목", viewCount: 9 });
    expect(await screen.findByRole("heading", { level: 1, name: "새 제목" })).toBeInTheDocument();

    gate.resolve();
    await waitFor(() => expect(screen.getByTestId("like-count")).toHaveTextContent("3"));
    expect(button).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("heading", { level: 1, name: "새 제목" })).toBeInTheDocument();
    expect(screen.getByTestId("view-count")).toHaveTextContent("9");
  });

  it("TASK-33#1 요청 중 재조회로 서버 값이 들어왔으면 실패해도 그 값을 덮지 않고 상세를 다시 받는다", async () => {
    const gate = deferred();
    let gets = 0;
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      http.get("*/api/posts/1", () => {
        gets += 1;
        return HttpResponse.json(
          gets === 1 ? makePost({ likeCount: 3, likedByMe: false }) : makePost({ likeCount: 8, likedByMe: true }),
        );
      }),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/like", async () => {
        await gate.promise;
        return apiError(500, "internal", "서버 오류");
      }),
    );
    const { client } = renderWithQuery(<PostDetailView />);

    const button = await screen.findByTestId("like-button");
    await userEvent.click(button);
    expect(screen.getByTestId("like-count")).toHaveTextContent("4");

    // 요청이 도는 사이 재조회가 서버 값(다른 탭에서 이미 좋아요, 총 7)을 가져왔다 — 낙관적 값(4)과 다르다.
    client.setQueryData<PostDetail>(queryKeys.post(1), (old) => old && { ...old, likedByMe: true, likeCount: 7 });
    await waitFor(() => expect(screen.getByTestId("like-count")).toHaveTextContent("7"));

    gate.resolve();
    // 옛 값(3)으로 되돌리지 않고, 상세를 다시 받아 서버 값으로 맞춘다.
    await waitFor(() => expect(gets).toBe(2));
    await waitFor(() => expect(screen.getByTestId("like-count")).toHaveTextContent("8"));
    expect(button).toHaveAttribute("aria-pressed", "true");
  });

  it("TASK-33#1 404(그사이 삭제된 글)면 상세를 다시 받아 없는 글 안내로 바꾼다", async () => {
    let gets = 0;
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      http.get("*/api/posts/1", () => {
        gets += 1;
        return gets === 1 ? HttpResponse.json(makePost()) : apiError(404, "not_found", "글이 없습니다");
      }),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/like", () => apiError(404, "not_found", "글이 없습니다")),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("like-button"));
    expect(await screen.findByTestId("post-not-found")).toBeInTheDocument();
    expect(gets).toBe(2);
  });
});
