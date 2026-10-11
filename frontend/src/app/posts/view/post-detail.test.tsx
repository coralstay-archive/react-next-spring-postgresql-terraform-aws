import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostDetailView } from "@/app/posts/view/post-detail-view";
import type { Comment } from "@/lib/board";
import { commentsHandler, makeComment, makePost, postHandler } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { anonymous, makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((r) => (resolve = r));
  return { promise, resolve };
}

// TASK-14#2 상세(/posts/view?id=): 본문, 이미지, 좋아요, 댓글.
describe("TASK-14#2 글 상세: 본문·이미지·조회수", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  it("TASK-14#2 본문은 HTML 로 해석하지 않고 줄바꿈을 보존한 텍스트로 보인다", async () => {
    const content = '<b>굵게</b><img src=x onerror="alert(1)">\n둘째 줄';
    server.use(
      anonymous(),
      postHandler(makePost({ title: "글 제목", content, tags: ["java"], viewCount: 42 })),
      commentsHandler(1, () => []),
    );
    const { container } = renderWithQuery(<PostDetailView />);

    expect(await screen.findByRole("heading", { level: 1, name: "글 제목" })).toBeInTheDocument();
    const body = screen.getByTestId("post-content");
    expect(body.textContent).toBe(content);
    expect(body).toHaveClass("whitespace-pre-wrap");
    expect(container.querySelector("b")).toBeNull();
    expect(container.querySelector('img[src="x"]')).toBeNull();
    expect(screen.getByTestId("view-count")).toHaveTextContent("42");
    expect(screen.getByText("작성자")).toBeInTheDocument();
    expect(screen.getByText("#java")).toBeInTheDocument();
  });

  it("TASK-14#2 첨부 이미지를 attachments[].url 로 보인다", async () => {
    server.use(
      anonymous(),
      postHandler(
        makePost({
          attachments: [
            { id: 10, url: "/uploads/1/a.png", contentType: "image/png", size: 10, status: "CONFIRMED" },
            { id: 11, url: "/uploads/1/b.jpg", contentType: "image/jpeg", size: 10, status: "CONFIRMED" },
          ],
        }),
      ),
      commentsHandler(1, () => []),
    );
    renderWithQuery(<PostDetailView />);

    const gallery = await screen.findByTestId("post-attachments");
    const images = within(gallery).getAllByRole("img");
    expect(images.map((img) => img.getAttribute("src"))).toEqual(["/uploads/1/a.png", "/uploads/1/b.jpg"]);
  });

  it("TASK-14#2 404 post_not_found 면 없는 글 안내", async () => {
    server.use(
      anonymous(),
      http.get("*/api/posts/1", () => apiError(404, "post_not_found", "글을 찾을 수 없습니다")),
      commentsHandler(1, () => []),
    );
    renderWithQuery(<PostDetailView />);
    expect(await screen.findByTestId("post-not-found")).toHaveTextContent("글을 찾을 수 없습니다");
  });

  it("TASK-14#2 id 가 없거나 숫자가 아니면 요청하지 않고 없는 글 안내", async () => {
    navigation.reset("/posts/view/", "id=abc");
    server.use(anonymous());
    renderWithQuery(<PostDetailView />);
    expect(await screen.findByTestId("post-not-found")).toBeInTheDocument();
  });

  it("TASK-14#2 ADMIN 에게 삭제·숨김 배지를 보인다", async () => {
    server.use(
      meHandler(makeMe({ role: "ADMIN" })),
      postHandler(makePost({ deleted: true, hidden: true })),
      commentsHandler(1, () => []),
    );
    renderWithQuery(<PostDetailView />);
    expect(await screen.findByTestId("badge-deleted")).toHaveTextContent("삭제됨");
    expect(screen.getByTestId("badge-hidden")).toHaveTextContent("숨김");
  });
});

describe("TASK-14#2 좋아요: 낙관적 업데이트 + 롤백", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  it("TASK-14#2 누르면 응답 전에 바로 반영하고, 응답 값으로 맞춘다", async () => {
    const gate = deferred();
    let header: string | null = null;
    server.use(
      meHandler(makeMe()),
      csrfHandler("tok"),
      postHandler(makePost({ likeCount: 3, likedByMe: false })),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/like", async ({ request }) => {
        header = request.headers.get("X-XSRF-TOKEN");
        await gate.promise;
        return HttpResponse.json({ liked: true, likeCount: 7 });
      }),
    );
    renderWithQuery(<PostDetailView />);

    const button = await screen.findByTestId("like-button");
    expect(button).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(button);
    expect(button).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("like-count")).toHaveTextContent("4");

    gate.resolve();
    await waitFor(() => expect(screen.getByTestId("like-count")).toHaveTextContent("7"));
    expect(header).toBe("tok");
  });

  it("TASK-14#2 이미 누른 글은 DELETE 로 취소한다", async () => {
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost({ likeCount: 3, likedByMe: true })),
      commentsHandler(1, () => []),
      http.delete("*/api/posts/1/like", () => HttpResponse.json({ liked: false, likeCount: 2 })),
    );
    renderWithQuery(<PostDetailView />);

    const button = await screen.findByTestId("like-button");
    expect(button).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(button);
    await waitFor(() => expect(button).toHaveAttribute("aria-pressed", "false"));
    expect(screen.getByTestId("like-count")).toHaveTextContent("2");
  });

  it("TASK-14#2 실패하면 원래대로 되돌리고 오류(step-up 링크)를 보인다", async () => {
    const gate = deferred();
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost({ likeCount: 3, likedByMe: false })),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/like", async () => {
        await gate.promise;
        return apiError(403, "step_up_required", "다시 인증이 필요합니다");
      }),
    );
    renderWithQuery(<PostDetailView />);

    const button = await screen.findByTestId("like-button");
    await userEvent.click(button);
    expect(screen.getByTestId("like-count")).toHaveTextContent("4");
    gate.resolve();

    await waitFor(() => expect(screen.getByTestId("like-count")).toHaveTextContent("3"));
    expect(button).toHaveAttribute("aria-pressed", "false");
    const alert = await screen.findByTestId("like-error");
    expect(alert).toHaveTextContent("기존 로그인 수단으로 다시 인증하세요");
    expect(within(alert).getByRole("link", { name: "다시 인증하기" })).toHaveAttribute("href", "/account/#step-up");
  });

  it("TASK-14#2 익명이 누르면 로그인으로 보낸다(돌아올 경로 포함)", async () => {
    server.use(anonymous(), postHandler(makePost()), commentsHandler(1, () => []));
    renderWithQuery(<PostDetailView />);

    const button = await screen.findByTestId("like-button");
    // me 를 받기 전에는 로그인 여부를 몰라 잠겨 있다.
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);
    expect(navigation.push).toHaveBeenCalledWith(`/login/?next=${encodeURIComponent("/posts/view/?id=1")}`);
  });
});

describe("TASK-14#2 댓글", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("TASK-14#2 목록: 삭제된 댓글은 자리표시로 보인다", async () => {
    server.use(
      anonymous(),
      postHandler(makePost()),
      commentsHandler(1, () => [
        makeComment({ id: 1, content: "첫 댓글" }),
        makeComment({ id: 2, content: null, author: null, deleted: true }),
      ]),
    );
    renderWithQuery(<PostDetailView />);

    const list = await screen.findByTestId("comment-list");
    await waitFor(() => expect(within(list).getAllByTestId("comment-item")).toHaveLength(2));
    expect(within(list).getByText("첫 댓글")).toBeInTheDocument();
    expect(within(list).getByText("삭제된 댓글입니다")).toBeInTheDocument();
  });

  it("TASK-14#2 익명에게는 작성 폼 대신 로그인 링크", async () => {
    server.use(anonymous(), postHandler(makePost()), commentsHandler(1, () => []));
    renderWithQuery(<PostDetailView />);

    expect(await screen.findByRole("link", { name: "로그인하고 댓글 쓰기" })).toHaveAttribute(
      "href",
      `/login/?next=${encodeURIComponent("/posts/view/?id=1")}`,
    );
    expect(screen.queryByTestId("comment-input")).not.toBeInTheDocument();
  });

  it("TASK-14#2 작성: 공백뿐이면 요청하지 않고 안내", async () => {
    let posted = false;
    server.use(
      meHandler(makeMe()),
      postHandler(makePost()),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/comments", () => {
        posted = true;
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.type(await screen.findByTestId("comment-input"), "   ");
    await userEvent.click(screen.getByTestId("comment-submit"));
    expect(await screen.findByText("댓글을 입력하세요")).toBeInTheDocument();
    expect(posted).toBe(false);
  });

  it("TASK-14#2 작성: POST 뒤 목록을 다시 받고 입력을 비운다", async () => {
    let comments: Comment[] = [];
    let body: unknown = null;
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost()),
      commentsHandler(1, () => comments),
      http.post("*/api/posts/1/comments", async ({ request }) => {
        body = await request.json();
        const created = makeComment({ id: 9, content: "새 댓글", author: { id: 1, nickname: "테스터" }, editable: true });
        comments = [created];
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    renderWithQuery(<PostDetailView />);

    const input = await screen.findByTestId("comment-input");
    await userEvent.type(input, "새 댓글");
    await userEvent.click(screen.getByTestId("comment-submit"));

    expect(await screen.findByText("새 댓글", { selector: "p" })).toBeInTheDocument();
    expect(body).toEqual({ content: "새 댓글" });
    expect(input).toHaveValue("");
  });

  it("TASK-14#2 작성 실패(step_up_required)는 안내하고 입력을 남긴다", async () => {
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost()),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/comments", () => apiError(403, "step_up_required", "다시 인증")),
    );
    renderWithQuery(<PostDetailView />);

    const input = await screen.findByTestId("comment-input");
    await userEvent.type(input, "내용");
    await userEvent.click(screen.getByTestId("comment-submit"));
    expect(await screen.findByRole("link", { name: "다시 인증하기" })).toBeInTheDocument();
    expect(input).toHaveValue("내용");
  });

  it("TASK-14#2 삭제: editable 댓글에만 버튼이 있고, DELETE 뒤 목록을 다시 받는다", async () => {
    let comments = [
      makeComment({ id: 1, content: "내 댓글", editable: true }),
      makeComment({ id: 2, content: "남의 댓글", editable: false }),
    ];
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost()),
      commentsHandler(1, () => comments),
      http.delete("*/api/comments/1", () => {
        comments = [makeComment({ id: 1, content: null, author: null, deleted: true }), comments[1]];
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithQuery(<PostDetailView />);

    const list = await screen.findByTestId("comment-list");
    await within(list).findByText("내 댓글");
    const buttons = within(list).getAllByRole("button", { name: "댓글 삭제" });
    expect(buttons).toHaveLength(1);
    await userEvent.click(buttons[0]);
    expect(await within(list).findByText("삭제된 댓글입니다")).toBeInTheDocument();
    expect(within(list).queryByRole("button", { name: "댓글 삭제" })).not.toBeInTheDocument();
  });

  it("TASK-14#2 삭제 실패(404 comment_not_found)는 한국어로 안내", async () => {
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      postHandler(makePost()),
      commentsHandler(1, () => [makeComment({ id: 1, content: "내 댓글", editable: true })]),
      http.delete("*/api/comments/1", () => apiError(404, "comment_not_found", "not found")),
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByRole("button", { name: "댓글 삭제" }));
    expect(await screen.findByTestId("comment-error")).toHaveTextContent("댓글을 찾을 수 없습니다");
  });
});
