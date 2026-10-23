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
import { categoriesHandler, commentsHandler, makePost, postHandler } from "@/test/board";
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
    expectStepUpNotice(await screen.findByTestId("step-up-banner"));
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

    expectStepUpNotice(await screen.findByTestId("editor-step-up"));
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

    expectStepUpNotice(await screen.findByTestId("comment-step-up"));
    expect(screen.getByTestId("comment-submit")).toBeDisabled();
    const input = screen.getByTestId("comment-input");
    await userEvent.type(input, "댓글");
    expect(input).toHaveValue("댓글");
    fireEvent.submit(screen.getByRole("form", { name: "댓글 쓰기" }));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(rec.calls).toEqual([]);
  });
});
