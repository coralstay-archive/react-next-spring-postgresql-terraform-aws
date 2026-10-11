import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditView } from "@/app/posts/edit/post-edit-view";
import { PostEditor } from "@/components/board/post-editor";
import { RequireAuth } from "@/components/guards";
import { categoriesHandler, makePost, postHandler, presignHandlers } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { anonymous, makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

function png(name = "cat.png", bytes = 8) {
  return new File([new Uint8Array(bytes)], name, { type: "image/png" });
}

/** POST /api/posts: 본문을 기록하고 201 + 상세. */
function createHandler(bodies: unknown[], id = 55) {
  return http.post("*/api/posts", async ({ request }) => {
    bodies.push(await request.json());
    return HttpResponse.json(makePost({ id, editable: true }), { status: 201 });
  });
}

async function fillRequired(title = "새 글", content = "본문입니다\n둘째 줄") {
  await userEvent.type(screen.getByTestId("editor-title"), title);
  await userEvent.type(screen.getByTestId("editor-content"), content);
}

// TASK-14#3 작성·수정(태그, 이미지 업로드).
describe("TASK-14#3 글쓰기 /posts/new/", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  it("TASK-14#3 USER 에게는 adminOnly 카테고리를 숨긴다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    const select = screen.getByTestId("editor-category");
    await within(select).findByRole("option", { name: "자유게시판" });
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["카테고리 없음", "자유게시판"]);
  });

  it("TASK-14#3 ADMIN 에게는 adminOnly 카테고리도 보인다", async () => {
    server.use(meHandler(makeMe({ role: "ADMIN" })), categoriesHandler());
    renderWithQuery(<PostEditor />);

    const select = screen.getByTestId("editor-category");
    await within(select).findByRole("option", { name: "공지" });
    expect(within(select).getAllByRole("option")).toHaveLength(3);
  });

  it("TASK-14#3 제목·본문이 비었거나 공백뿐이면 요청하지 않고 안내", async () => {
    const bodies: unknown[] = [];
    server.use(meHandler(makeMe()), categoriesHandler(), createHandler(bodies));
    renderWithQuery(<PostEditor />);

    await userEvent.type(screen.getByTestId("editor-title"), "   ");
    await userEvent.click(screen.getByTestId("editor-submit"));
    expect(await screen.findByText("제목을 입력하세요")).toBeInTheDocument();
    expect(screen.getByText("본문을 입력하세요")).toBeInTheDocument();
    expect(bodies).toEqual([]);
  });

  it("TASK-14#3 태그: 입력 중 정규화 미리보기, Enter 로 추가, 중복은 합치고 5개까지", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    const input = screen.getByTestId("editor-tag-input");
    await userEvent.type(input, "  Spring   Boot ");
    expect(screen.getByTestId("editor-tag-preview")).toHaveTextContent("spring boot");
    await userEvent.keyboard("{Enter}");
    const chips = screen.getByTestId("editor-tags");
    expect(within(chips).getByText("#spring boot")).toBeInTheDocument();
    expect(input).toHaveValue("");

    await userEvent.type(input, "SPRING boot{Enter}");
    expect(within(chips).getAllByText(/^#/)).toHaveLength(1);

    for (const tag of ["a", "b", "c", "d"]) await userEvent.type(input, `${tag}{Enter}`);
    expect(within(chips).getAllByText(/^#/)).toHaveLength(5);
    await userEvent.type(input, "f{Enter}");
    expect(await screen.findByText("태그는 5개까지 붙일 수 있습니다")).toBeInTheDocument();
    expect(within(chips).getAllByText(/^#/)).toHaveLength(5);

    await userEvent.click(within(chips).getByRole("button", { name: "태그 a 빼기" }));
    expect(within(chips).getAllByText(/^#/)).toHaveLength(4);
  });

  it("TASK-14#3 태그: 쉼표로도 추가하고 30자 초과는 안내", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    const input = screen.getByTestId("editor-tag-input");
    await userEvent.type(input, "java,");
    expect(within(screen.getByTestId("editor-tags")).getByText("#java")).toBeInTheDocument();
    await userEvent.type(input, `${"x".repeat(31)}{Enter}`);
    expect(await screen.findByText("태그는 30자 이하여야 합니다")).toBeInTheDocument();
  });

  it("TASK-14#3 이미지를 올리고(presign→PUT→confirm) attachmentIds 와 함께 작성한 뒤 상세로 간다", async () => {
    const bodies: unknown[] = [];
    const uploads = presignHandlers();
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), ...uploads.handlers, createHandler(bodies));
    renderWithQuery(<PostEditor />);

    await userEvent.upload(screen.getByTestId("editor-file-input"), [png("a.png"), png("b.png")]);
    const list = screen.getByTestId("editor-attachments");
    await waitFor(() => expect(within(list).getAllByRole("img")).toHaveLength(2));
    expect(uploads.confirmed).toEqual([100, 101]);

    await fillRequired();
    await userEvent.selectOptions(screen.getByTestId("editor-category"), "free");
    await userEvent.type(screen.getByTestId("editor-tag-input"), "Java{Enter}");
    await userEvent.click(screen.getByTestId("editor-submit"));

    await waitFor(() => expect(navigation.push).toHaveBeenCalledWith("/posts/view/?id=55"));
    expect(bodies).toEqual([
      {
        title: "새 글",
        content: "본문입니다\n둘째 줄",
        categorySlug: "free",
        tags: ["java"],
        attachmentIds: [100, 101],
      },
    ]);
  });

  it("TASK-14#3 입력 중인 태그도 등록할 때 붙인다", async () => {
    const bodies: unknown[] = [];
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), createHandler(bodies));
    renderWithQuery(<PostEditor />);

    await fillRequired();
    await userEvent.type(screen.getByTestId("editor-tag-input"), " Next JS ");
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ tags: ["next js"], attachmentIds: [] });
    expect(bodies[0]).not.toHaveProperty("categorySlug");
  });

  it("TASK-14#3 허용하지 않는 파일은 올리지 않고 안내한다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    const user = userEvent.setup({ applyAccept: false });
    await user.upload(screen.getByTestId("editor-file-input"), new File(["x"], "a.txt", { type: "text/plain" }));
    expect(await screen.findByText(/PNG·JPEG·WebP·GIF 이미지만 올릴 수 있습니다/)).toBeInTheDocument();
    expect(within(screen.getByTestId("editor-attachments")).queryByRole("img")).not.toBeInTheDocument();
  });

  it("TASK-14#3 업로드가 실패하면 그 파일에 오류를 보이고 첨부하지 않는다", async () => {
    const bodies: unknown[] = [];
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/attachments/presign", () => apiError(503, "uploads_unavailable")),
      createHandler(bodies),
    );
    renderWithQuery(<PostEditor />);

    await userEvent.upload(screen.getByTestId("editor-file-input"), png());
    expect(await screen.findByText(/지금은 이미지를 올릴 수 없습니다/)).toBeInTheDocument();
    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ attachmentIds: [] });
  });

  it("TASK-14#3 서버 오류 코드(category_admin_only)를 한국어로 보인다", async () => {
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/posts", () => apiError(403, "category_admin_only", "관리자 전용")),
    );
    renderWithQuery(<PostEditor />);

    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));
    expect(await screen.findByText("이 카테고리에는 관리자만 글을 쓸 수 있습니다.")).toBeInTheDocument();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("TASK-14#3 서버 400 validation fields 는 해당 칸에 보인다", async () => {
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/posts", () =>
        apiError(400, "validation", "입력값", { fields: { title: "제목은 1~100자입니다" } }),
      ),
    );
    renderWithQuery(<PostEditor />);

    await fillRequired();
    await userEvent.click(screen.getByTestId("editor-submit"));
    expect(await screen.findByText("제목은 1~100자입니다")).toBeInTheDocument();
  });
});

describe("TASK-14#3 글 수정 /posts/edit/?id=", () => {
  beforeEach(() => {
    navigation.reset("/posts/edit/", "id=3");
  });

  it("TASK-14#3 기존 값을 채우고 PATCH 로 저장한 뒤 상세로 간다(첨부 빼기 포함)", async () => {
    const post = makePost({
      id: 3,
      title: "원래 제목",
      content: "원래 본문",
      tags: ["java"],
      editable: true,
      attachments: [{ id: 9, url: "/uploads/1/9.png", contentType: "image/png", size: 5, status: "CONFIRMED" }],
    });
    let body: unknown = null;
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      postHandler(post),
      http.patch("*/api/posts/3", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...post, title: "새 제목", attachments: [] });
      }),
    );
    renderWithQuery(<PostEditView />);

    const title = await screen.findByTestId("editor-title");
    expect(title).toHaveValue("원래 제목");
    expect(screen.getByTestId("editor-content")).toHaveValue("원래 본문");
    // 카테고리 목록이 오면 선택이 맞춰진다.
    await within(screen.getByTestId("editor-category")).findByRole("option", { name: "자유게시판" });
    expect(screen.getByTestId("editor-category")).toHaveValue("free");
    expect(within(screen.getByTestId("editor-tags")).getByText("#java")).toBeInTheDocument();

    await userEvent.clear(title);
    await userEvent.type(title, "새 제목");
    await userEvent.selectOptions(screen.getByTestId("editor-category"), "");
    await userEvent.click(screen.getByRole("button", { name: "첨부 이미지 1 빼기" }));
    await userEvent.click(screen.getByTestId("editor-submit"));

    await waitFor(() => expect(navigation.push).toHaveBeenCalledWith("/posts/view/?id=3"));
    expect(body).toEqual({
      title: "새 제목",
      content: "원래 본문",
      categorySlug: "",
      tags: ["java"],
      attachmentIds: [],
    });
  });

  it("TASK-14#3 없는 글(404)이면 안내한다", async () => {
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      http.get("*/api/posts/3", () => apiError(404, "post_not_found")),
    );
    renderWithQuery(<PostEditView />);
    expect(await screen.findByTestId("post-not-found")).toBeInTheDocument();
  });
});

describe("TASK-14#3 작성·수정은 로그인 필요", () => {
  it("TASK-14#3 익명이 /posts/edit/?id=3 에 오면 쿼리까지 next 로 담아 로그인으로 보낸다", async () => {
    navigation.reset("/posts/edit/", "id=3");
    server.use(anonymous());
    renderWithQuery(
      <RequireAuth>
        <p>편집기</p>
      </RequireAuth>,
    );
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledWith(`/login/?next=${encodeURIComponent("/posts/edit/?id=3")}`),
    );
    expect(screen.queryByText("편집기")).not.toBeInTheDocument();
  });
});
