import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditor } from "@/components/board/post-editor";
import { categoriesHandler, makePost, presignHandlers } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

async function fillRequired() {
  await fill(screen.getByTestId("editor-title"), "새 글");
  await fill(screen.getByTestId("editor-content"), "본문");
}

function png(name: string) {
  return new File([new Uint8Array(8)], name, { type: "image/png" });
}

function rejectWith(status: number, code: string, message: string, extra: Record<string, unknown> = {}) {
  return http.post("*/api/posts", () => apiError(status, code, message, extra));
}

describe("TASK-31#3 표시되지 않던 서버 필드 오류 키를 칸에 붙이고, 남은 키는 상단 알림(FE-24)", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  it.each([
    ["categorySlug", "editor-category-error"],
    ["tags[1]", "editor-tags-error"],
    ["tags", "editor-tags-error"],
    ["attachmentIds", "editor-attachments-error"],
    ["attachmentIds[0]", "editor-attachments-error"],
    ["content", "editor-content-error"],
  ])("TASK-31#3 fields.%s 는 해당 칸(%s)에 보이고 상단 알림은 없다", async (key, testId) => {
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      rejectWith(400, "validation", "입력값을 확인하세요", { fields: { [key]: "서버가 거절한 값입니다" } }),
    );
    renderWithQuery(<PostEditor />);

    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));
    expect(await screen.findByTestId(testId)).toHaveTextContent("서버가 거절한 값입니다");
    expect(screen.queryByTestId("editor-error")).not.toBeInTheDocument();
  });

  it("TASK-31#3 폼에 없는 키(titel 등)가 있으면 상단 알림에 그 메시지를 보이고, 아는 키는 칸에 붙인다", async () => {
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      rejectWith(400, "validation", "입력값을 확인하세요", {
        fields: { title: "제목은 1~100자입니다", titel: "알 수 없는 필드입니다" },
      }),
    );
    renderWithQuery(<PostEditor />);

    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));
    expect(await screen.findByTestId("editor-title-error")).toHaveTextContent("제목은 1~100자입니다");
    const alert = screen.getByTestId("editor-error");
    expect(alert).toHaveTextContent("알 수 없는 필드입니다");
    expect(alert).not.toHaveTextContent("제목은 1~100자입니다");
  });
});
