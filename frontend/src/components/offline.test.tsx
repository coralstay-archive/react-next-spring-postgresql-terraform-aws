import { onlineManager } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { OfflineBanner } from "@/components/offline-banner";
import { commentsHandler, makePost } from "@/test/board";
import { csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** 한 글자씩 치는 type 은 부하가 걸린 make test 에서 5초를 넘길 수 있어 붙여넣기로 채운다. */
async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

function goOffline() {
  act(() => {
    window.dispatchEvent(new Event("offline"));
  });
}

function goOnline() {
  act(() => {
    window.dispatchEvent(new Event("online"));
  });
}

// onlineManager 는 전역 싱글턴이라 다음 테스트로 새지 않게 되돌린다.
afterEach(() => {
  onlineManager.setOnline(true);
});

describe("TASK-30#1 오프라인 배너", () => {
  it("TASK-30#1 offline 이벤트면 오프라인 배너를 보이고, online 이벤트면 숨긴다", async () => {
    renderWithQuery(<OfflineBanner />);
    expect(screen.queryByTestId("offline-banner")).not.toBeInTheDocument();

    goOffline();
    expect(await screen.findByTestId("offline-banner")).toHaveTextContent("오프라인입니다");

    goOnline();
    await waitFor(() => expect(screen.queryByTestId("offline-banner")).not.toBeInTheDocument());
  });
});

describe("TASK-30#1 오프라인 제출은 일시정지되지 않고 바로 network 오류로 끝난다", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  it("TASK-30#1 TASK-30#B1 오프라인에서 댓글을 등록하면 즉시 오류이고, 다시 연결돼도 요청을 몰래 보내지 않는다", async () => {
    let posts = 0;
    server.use(
      csrfHandler(),
      meHandler(makeMe()),
      http.get("*/api/posts/1", () => HttpResponse.json(makePost())),
      commentsHandler(1, () => []),
      http.post("*/api/posts/1/comments", () => {
        posts++;
        // 오프라인 브라우저의 fetch 는 TypeError 로 실패한다.
        return HttpResponse.error();
      }),
    );
    renderWithQuery(<PostDetailView />);

    await fill(await screen.findByTestId("comment-input"), "오프라인 댓글");
    goOffline();
    await userEvent.click(screen.getByTestId("comment-submit"));

    // 일시정지(isPaused)라면 오류도 없이 버튼만 비활성으로 남는다.
    expect(await screen.findByTestId("comment-create-error")).toHaveTextContent("서버에 연결할 수 없습니다");
    expect(screen.getByTestId("comment-submit")).toBeEnabled();
    expect(screen.getByTestId("comment-input")).toHaveValue("오프라인 댓글");
    const sent = posts;

    goOnline();
    // 일시정지된 뮤테이션은 복귀 때 resumePausedMutations 로 뒤늦게 나간다. 그런 요청이 없어야 한다.
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(posts).toBe(sent);
    expect(posts).toBeLessThanOrEqual(1);
  });
});
