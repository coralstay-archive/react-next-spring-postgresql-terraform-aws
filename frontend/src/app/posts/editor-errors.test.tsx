import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditView } from "@/app/posts/edit/post-edit-view";
import { PostEditor } from "@/components/board/post-editor";
import { RequireAuth } from "@/components/guards";
import { draftKeys, saveDraft } from "@/lib/drafts";
import { CATEGORIES, categoriesHandler, makePost, presignHandlers } from "@/test/board";
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

describe("TASK-31#4 첨부 연결 오류면 문제 첨부를 표시하고 그것만 빼고 다시 저장(FE-25)", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  async function uploadTwo() {
    await userEvent.upload(screen.getByTestId("editor-file-input"), [png("a.png"), png("b.png")]);
    await waitFor(() => expect(within(screen.getByTestId("editor-attachments")).getAllByRole("img")).toHaveLength(2));
  }

  it.each([
    ["unknown_attachment", "없는 첨부입니다: 101"],
    ["attachment_not_confirmed", "업로드 확정(confirm) 전인 첨부입니다: 101"],
    ["attachment_in_use", "다른 글에 붙은 첨부입니다: 101"],
  ])("TASK-31#4 %s 면 그 첨부(id 101)에 표시하고, '문제 이미지 빼고 다시 저장'은 나머지로 다시 보낸다", async (code, message) => {
    const bodies: { attachmentIds: number[] }[] = [];
    const uploads = presignHandlers();
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      ...uploads.handlers,
      http.post("*/api/posts", async ({ request }) => {
        const body = (await request.json()) as { attachmentIds: number[] };
        bodies.push(body);
        if (body.attachmentIds.includes(101)) return apiError(400, code, message);
        return HttpResponse.json(makePost({ id: 77, editable: true }), { status: 201 });
      }),
    );
    renderWithQuery(<PostEditor />);

    await uploadTwo();
    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));

    const problems = await screen.findAllByTestId("editor-attachment-problem");
    expect(problems).toHaveLength(1);
    // 두 번째 첨부(id 101) 칸에 붙는다.
    const cells = within(screen.getByTestId("editor-attachments")).getAllByRole("listitem");
    expect(within(cells[1]).getByTestId("editor-attachment-problem")).toBeInTheDocument();
    expect(within(cells[0]).queryByTestId("editor-attachment-problem")).not.toBeInTheDocument();
    expect(screen.queryByTestId("editor-error")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "문제 이미지 빼고 다시 저장" }));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/posts/view/?id=77"));
    expect(bodies.map((b) => b.attachmentIds)).toEqual([[100, 101], [100]]);
  });

  it("TASK-31#4 fields 의 attachmentIds[n] 도 그 첨부에 표시한다", async () => {
    const uploads = presignHandlers();
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      ...uploads.handlers,
      rejectWith(400, "validation", "입력값을 확인하세요", { fields: { "attachmentIds[0]": "잘못된 첨부 id 입니다" } }),
    );
    renderWithQuery(<PostEditor />);

    await uploadTwo();
    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));

    const cells = within(screen.getByTestId("editor-attachments")).getAllByRole("listitem");
    expect(await within(cells[0]).findByTestId("editor-attachment-problem")).toHaveTextContent("잘못된 첨부 id 입니다");
    expect(within(cells[1]).queryByTestId("editor-attachment-problem")).not.toBeInTheDocument();
  });

  it("TASK-31#4 어떤 첨부인지 알 수 없으면(attachment_forbidden) 첨부 칸에 안내하고 빼고 저장 버튼은 없다", async () => {
    const uploads = presignHandlers();
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      ...uploads.handlers,
      rejectWith(403, "attachment_forbidden", "본인이 올린 첨부만 쓸 수 있습니다"),
    );
    renderWithQuery(<PostEditor />);

    await uploadTwo();
    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));

    expect(await screen.findByTestId("editor-attachments-error")).toHaveTextContent("본인이 올린 첨부만 붙일 수 있습니다.");
    expect(screen.queryByTestId("editor-attachment-problem")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "문제 이미지 빼고 다시 저장" })).not.toBeInTheDocument();
  });
});

describe("TASK-31#8 카테고리 목록 오류 표시, 현재 카테고리를 선택지에 유지(FE-30)", () => {
  beforeEach(() => {
    navigation.reset("/posts/edit/", "id=3");
  });

  it("TASK-31#8 목록을 못 받으면 칸 아래에 알리고 '다시 시도'로 다시 받는다", async () => {
    navigation.reset("/posts/new/");
    let fail = true;
    server.use(
      meHandler(makeMe()),
      http.get("*/api/categories", () => (fail ? apiError(500, "internal_error") : HttpResponse.json(CATEGORIES))),
    );
    renderWithQuery(<PostEditor />);

    const alert = await screen.findByTestId("editor-categories-error");
    expect(alert).toHaveTextContent("카테고리 목록을 불러오지 못했습니다");
    fail = false;
    await userEvent.click(within(alert).getByRole("button", { name: "다시 시도" }));
    await within(screen.getByTestId("editor-category")).findByRole("option", { name: "자유게시판" });
    expect(screen.queryByTestId("editor-categories-error")).not.toBeInTheDocument();
  });

  it("TASK-31#8 목록을 못 받아도 수정 중인 글의 카테고리는 선택된 채 보이고 그대로 보낸다", async () => {
    let body: unknown = null;
    const post = makePost({ id: 3, editable: true, category: { id: 2, slug: "free", name: "자유게시판" } });
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      http.get("*/api/categories", () => apiError(500, "internal_error")),
      http.get("*/api/posts/3", () => HttpResponse.json(post)),
      http.patch("*/api/posts/3", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(post);
      }),
    );
    renderWithQuery(<PostEditView />);

    const select = await screen.findByTestId("editor-category");
    await screen.findByTestId("editor-categories-error");
    expect(select).toHaveValue("free");
    expect(within(select).getByRole("option", { selected: true })).toHaveTextContent("자유게시판");
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(body).toMatchObject({ categorySlug: "free" }));
  });

  it("TASK-31#8 글의 카테고리가 목록에 없어도(그사이 삭제·이름 바뀜) 선택지로 남아 표시와 전송값이 같다", async () => {
    let body: unknown = null;
    const post = makePost({ id: 3, editable: true, category: { id: 9, slug: "old", name: "옛 카테고리" } });
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      categoriesHandler(),
      http.get("*/api/posts/3", () => HttpResponse.json(post)),
      http.patch("*/api/posts/3", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(post);
      }),
    );
    renderWithQuery(<PostEditView />);

    const select = await screen.findByTestId("editor-category");
    await within(select).findByRole("option", { name: "자유게시판" });
    expect(select).toHaveValue("old");
    expect(within(select).getByRole("option", { selected: true })).toHaveTextContent("옛 카테고리");
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(body).toMatchObject({ categorySlug: "old" }));
  });

  it("TASK-31#8 복원한 초안의 카테고리가 목록에 없어도 선택지로 남는다", async () => {
    navigation.reset("/posts/new/");
    saveDraft(draftKeys.newPost, 1, { title: "초안", content: "본문", categorySlug: "gone", tags: [], attachments: [] });
    server.use(meHandler(makeMe()), categoriesHandler());
    // 초안은 사용자를 안 뒤에 연 편집기가 복원한다(RequireAuth).
    renderWithQuery(
      <RequireAuth>
        <PostEditor />
      </RequireAuth>,
    );

    const select = await screen.findByTestId("editor-category");
    await within(select).findByRole("option", { name: "자유게시판" });
    expect(screen.getByTestId("editor-title")).toHaveValue("초안");
    expect(select).toHaveValue("gone");
  });
});
