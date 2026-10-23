import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostDetailView } from "@/app/posts/view/post-detail-view";
import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { PostEditor } from "@/components/board/post-editor";
import { StepUpBanner } from "@/components/step-up-banner";
import { ApiError } from "@/lib/api";
import { ERROR_MESSAGES } from "@/lib/errors";
import { categoriesHandler, commentsHandler, makeComment, makePost, postHandler } from "@/test/board";
import { csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

const RESTRICTED = makeMe({ providers: ["LOCAL", "PHONE"], stepUpRequired: true, stepUpProviders: ["LOCAL"] });

/** 단일 안내 컴포넌트(StepUpNotice)인지: 같은 표식·제목·표준 문구·다시 인증 링크. */
function expectStepUpNotice(element: HTMLElement) {
  expect(element).toHaveAttribute("data-step-up-notice");
  expect(element).toHaveTextContent("다시 인증이 필요합니다");
  expect(element).toHaveTextContent(ERROR_MESSAGES.step_up_required);
  expect(within(element).getByRole("link", { name: "다시 인증하기" })).toHaveAttribute("href", "/account/#step-up");
}

/** 화면 안 간략형(TASK-32#3): 같은 컴포넌트 표식, 조용한 role="status", 짧은 문구와 같은 다시 인증 링크. alert 가 아니다. */
function expectInlineStepUpNotice(element: HTMLElement, text: string) {
  expect(element).toHaveAttribute("data-step-up-notice");
  expect(element).toHaveAttribute("role", "status");
  expect(element).toHaveTextContent(text);
  expect(element).toHaveTextContent("작성 내용은 임시 저장됩니다");
  expect(within(element).getByRole("link", { name: "다시 인증하기" })).toHaveAttribute("href", "/account/#step-up");
}

/** 쓰기 요청을 센다(제한 세션에서 미리 막혔는지 보려고). */
function writeRecorder() {
  const calls: string[] = [];
  const record = ({ request }: { request: Request }) => {
    calls.push(`${request.method} ${new URL(request.url).pathname}`);
    return HttpResponse.json({}, { status: 500 });
  };
  return {
    calls,
    handlers: [
      http.post("*/api/posts", record),
      http.post("*/api/posts/:id/comments", record),
      http.post("*/api/attachments/presign", record),
      http.delete("*/api/posts/:id", record),
      http.delete("*/api/comments/:id", record),
    ],
  };
}

// TASK-32#3 FE-31: step-up 안내는 StepUpNotice 하나로 보이고, 제한 세션이면 작성 컨트롤을 미리 막는다.
describe("TASK-32#3 step-up 안내 단일 컴포넌트", () => {
  it("TASK-32#3 403 step_up_required 오류(ApiErrorAlert)는 StepUpNotice 로 보인다", () => {
    renderWithQuery(
      <ApiErrorAlert error={new ApiError(403, "step_up_required", "다시 인증")} testId="some-error" />,
    );
    expectStepUpNotice(screen.getByTestId("some-error"));
  });

  it("TASK-32#3 레이아웃 배너도 같은 StepUpNotice 다", async () => {
    server.use(meHandler(RESTRICTED));
    renderWithQuery(<StepUpBanner />);
    const banner = await screen.findByTestId("step-up-banner");
    expectStepUpNotice(banner);
    // TASK-34#3 role=alert 는 오류에만: 제한 세션은 상태라 상단 배너도 status 다(403 오류로 받은 안내만 alert).
    expect(banner).toHaveAttribute("role", "status");
  });
});

describe("TASK-32#3 제한 세션의 작성 컨트롤", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  it("TASK-32#3 글 편집기: 입력은 되지만 등록·이미지 올리기는 미리 막고 안내한다(Enter 제출도 요청 없음)", async () => {
    const rec = writeRecorder();
    server.use(meHandler(RESTRICTED), csrfHandler(), categoriesHandler(), ...rec.handlers);
    renderWithQuery(<PostEditor />);

    expectInlineStepUpNotice(await screen.findByTestId("editor-step-up"), "등록하려면 본인 확인이 필요합니다");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByTestId("editor-submit")).toBeDisabled();
    expect(screen.getByTestId("editor-file-input")).toBeDisabled();

    const title = screen.getByTestId("editor-title");
    await userEvent.type(title, "제목");
    await userEvent.type(screen.getByTestId("editor-content"), "본문");
    expect(title).toHaveValue("제목");
    fireEvent.submit(screen.getByTestId("post-editor"));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(rec.calls).toEqual([]);
  });

  it("TASK-32#3 제한이 없으면 편집기에 안내가 없고 등록할 수 있다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);
    await screen.findByTestId("post-editor");
    await waitFor(() => expect(screen.getByTestId("editor-submit")).toBeEnabled());
    expect(screen.queryByTestId("editor-step-up")).not.toBeInTheDocument();
  });

  it("TASK-32#3 댓글 폼: 입력은 되지만 등록은 미리 막고 안내한다", async () => {
    navigation.reset("/posts/view/", "id=1");
    const rec = writeRecorder();
    server.use(meHandler(RESTRICTED), csrfHandler(), postHandler(makePost()), commentsHandler(1, () => []), ...rec.handlers);
    renderWithQuery(<PostDetailView />);

    expectInlineStepUpNotice(await screen.findByTestId("comment-step-up"), "등록하려면 본인 확인이 필요합니다");
    expect(screen.getByTestId("comment-submit")).toBeDisabled();
    const input = screen.getByTestId("comment-input");
    await userEvent.type(input, "댓글");
    expect(input).toHaveValue("댓글");
    fireEvent.submit(screen.getByRole("form", { name: "댓글 쓰기" }));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(rec.calls).toEqual([]);
  });
});

// TASK-32#3(리뷰 반영 결정): 제한 세션에서 글·댓글 삭제는 확인 창("삭제할까요?")보다 먼저 본인 확인 안내를 보이고 요청을 보내지 않는다.
describe("TASK-32#3 제한 세션의 삭제", () => {
  beforeEach(() => {
    navigation.reset("/posts/view/", "id=1");
  });

  it("TASK-32#3 글 삭제: 확인 창 없이 안내만 보이고 요청이 없다", async () => {
    const rec = writeRecorder();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    server.use(
      meHandler(RESTRICTED),
      csrfHandler(),
      postHandler(makePost({ editable: true })),
      commentsHandler(1, () => []),
      ...rec.handlers,
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    expectStepUpNotice(await screen.findByTestId("post-delete-step-up"));
    expect(confirm).not.toHaveBeenCalled();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(rec.calls).toEqual([]);
    confirm.mockRestore();
  });

  it("TASK-32#3 댓글 삭제: 확인 창 없이 안내만 보이고 요청이 없다", async () => {
    const rec = writeRecorder();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    server.use(
      meHandler(RESTRICTED),
      csrfHandler(),
      postHandler(makePost()),
      commentsHandler(1, () => [makeComment({ editable: true })]),
      ...rec.handlers,
    );
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("comment-delete"));
    expectStepUpNotice(await screen.findByTestId("comment-delete-step-up"));
    expect(confirm).not.toHaveBeenCalled();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(rec.calls).toEqual([]);
    confirm.mockRestore();
  });

  it("TASK-32#3 제한이 없으면 글 삭제는 확인 창을 거친다(취소하면 요청 없음)", async () => {
    const rec = writeRecorder();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    server.use(meHandler(makeMe()), csrfHandler(), postHandler(makePost({ editable: true })), commentsHandler(1, () => []), ...rec.handlers);
    renderWithQuery(<PostDetailView />);

    await userEvent.click(await screen.findByTestId("post-delete-button"));
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId("post-delete-step-up")).not.toBeInTheDocument();
    expect(rec.calls).toEqual([]);
    confirm.mockRestore();
  });
});
