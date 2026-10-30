import { onlineManager } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { ApiErrorAlert, RefetchErrorAlert } from "@/components/board/api-error-alert";
import { PostEditor } from "@/components/board/post-editor";
import { RequireAuth } from "@/components/guards";
import { OfflineBanner } from "@/components/offline-banner";
import { StepUpNotice } from "@/components/step-up-notice";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ApiError } from "@/lib/api";
import { draftKeys, saveDraft } from "@/lib/drafts";
import { categoriesHandler, commentsHandler, makePost } from "@/test/board";
import { csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

afterEach(() => {
  onlineManager.setOnline(true);
});

describe("TASK-34#3 role=alert 는 오류에만(FE-36)", () => {
  it("TASK-34#3 Alert: destructive 는 alert, 기본(안내·성공)은 status", () => {
    render(
      <>
        <Alert variant="destructive" data-testid="err">
          <AlertDescription>오류</AlertDescription>
        </Alert>
        <Alert data-testid="info">
          <AlertDescription>안내</AlertDescription>
        </Alert>
      </>,
    );
    expect(screen.getByTestId("err")).toHaveAttribute("role", "alert");
    expect(screen.getByTestId("info")).toHaveAttribute("role", "status");
  });

  it("TASK-34#3 Alert: role 을 주면 그것을 쓴다", () => {
    render(<Alert role="note" data-testid="note" />);
    expect(screen.getByTestId("note")).toHaveAttribute("role", "note");
  });

  it("TASK-34#3 오프라인 배너는 상태 안내(status)다 — 사용자의 요청이 실패한 오류가 아니다", () => {
    renderWithQuery(<OfflineBanner />);
    act(() => {
      window.dispatchEvent(new Event("offline"));
    });
    expect(screen.getByRole("status")).toHaveTextContent("오프라인입니다");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("TASK-34#3 재조회 실패 안내는 기존 내용을 그대로 두는 조용한 안내(status), API 오류는 alert", () => {
    const error = new ApiError(503, "db_unavailable", "장애");
    renderWithQuery(
      <>
        <RefetchErrorAlert error={error} onRetry={() => {}} testId="refetch" />
        <ApiErrorAlert error={error} testId="api" />
      </>,
    );
    expect(screen.getByTestId("refetch")).toHaveAttribute("role", "status");
    expect(screen.getByTestId("api")).toHaveAttribute("role", "alert");
  });

  it("TASK-34#3 제한 세션 안내: 상태 안내는 status, 403 오류로 받았을 때만 alert", () => {
    renderWithQuery(
      <>
        <StepUpNotice testId="banner">안내</StepUpNotice>
        <StepUpNotice error testId="error">오류</StepUpNotice>
      </>,
    );
    expect(screen.getByTestId("banner")).toHaveAttribute("role", "status");
    expect(screen.getByTestId("error")).toHaveAttribute("role", "alert");
  });

  it("TASK-34#3 초안 복원 안내는 status", async () => {
    navigation.reset("/posts/new/");
    server.use(meHandler(makeMe()), categoriesHandler());
    saveDraft(draftKeys.newPost, 1, { title: "초안", content: "본문", categorySlug: "", tags: [], attachments: [] });
    renderWithQuery(
      <RequireAuth>
        <PostEditor />
      </RequireAuth>,
    );
    expect(await screen.findByTestId("draft-restored")).toHaveAttribute("role", "status");
  });
});

describe("TASK-34#3 편집기·댓글 필드 오류는 aria-describedby·aria-invalid 로 입력에 연결(FE-36)", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  function expectLinked(input: HTMLElement, errorTestId: string, text: string) {
    const error = screen.getByTestId(errorTestId);
    expect(error).toHaveTextContent(text);
    expect(error.id).not.toBe("");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input.getAttribute("aria-describedby")?.split(" ")).toContain(error.id);
    expect(input).toHaveAccessibleDescription(expect.stringContaining(text));
  }

  it("TASK-34#3 제목·본문 오류는 그 입력의 설명이다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    await userEvent.click(await screen.findByTestId("editor-submit"));
    await screen.findByTestId("editor-title-error");
    expectLinked(screen.getByTestId("editor-title"), "editor-title-error", screen.getByTestId("editor-title-error").textContent!);
    expectLinked(screen.getByTestId("editor-content"), "editor-content-error", screen.getByTestId("editor-content-error").textContent!);
  });

  it("TASK-34#3 오류가 없으면 aria-invalid·aria-describedby 가 없다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    const title = await screen.findByTestId("editor-title");
    expect(title).not.toHaveAttribute("aria-invalid");
    expect(title).not.toHaveAttribute("aria-describedby");
  });

  it.each([
    ["categorySlug", "editor-category", "editor-category-error"],
    ["tags", "editor-tag-input", "editor-tags-error"],
    ["attachmentIds", "editor-file-input", "editor-attachments-error"],
  ])("TASK-34#3 서버 fields.%s 오류는 %s 의 설명이다", async (key, inputTestId, errorTestId) => {
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/posts", () =>
        HttpResponse.json(
          { status: 400, error: "validation", message: "입력값을 확인하세요", fields: { [key]: "서버가 거절한 값입니다" } },
          { status: 400 },
        ),
      ),
    );
    renderWithQuery(<PostEditor />);

    await userEvent.click(await screen.findByTestId("editor-title"));
    await userEvent.paste("새 글");
    await userEvent.click(screen.getByTestId("editor-content"));
    await userEvent.paste("본문");
    await userEvent.click(screen.getByTestId("editor-submit"));
    await screen.findByTestId(errorTestId);
    expectLinked(screen.getByTestId(inputTestId), errorTestId, "서버가 거절한 값입니다");
  });

  it("TASK-34#3 댓글 입력 오류는 댓글 입력의 설명이다", async () => {
    navigation.reset("/posts/view/", "id=1");
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      http.get("*/api/posts/1", () => HttpResponse.json(makePost({ id: 1 }))),
      commentsHandler(1, () => []),
    );
    renderWithQuery(<PostDetailView />);

    const input = await screen.findByTestId("comment-input");
    expect(input).not.toHaveAttribute("aria-describedby");
    await userEvent.click(screen.getByTestId("comment-submit"));
    await waitFor(() => expect(input).toHaveAttribute("aria-invalid", "true"));
    expectLinked(input, "comment-input-error", "댓글을 입력하세요");
  });
});
