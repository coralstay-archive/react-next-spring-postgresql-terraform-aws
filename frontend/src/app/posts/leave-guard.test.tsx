import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditor } from "@/components/board/post-editor";
import { draftKeys, loadDraft } from "@/lib/drafts";
import { categoriesHandler, makePost, presignHandlers } from "@/test/board";
import { csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";
import { installFakeXhr } from "@/test/xhr";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

function png(name = "a.png") {
  return new File([new Uint8Array(8)], name, { type: "image/png" });
}

/** beforeunload 를 보내고 막혔는지(브라우저가 '떠날까요?'를 묻는지). */
function beforeUnloadBlocked() {
  const event = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

/** 편집기 옆의 앱 안 링크(헤더 메뉴 같은). React onClick(= next/link 의 라우터 이동)이 불렸는지 본다. */
function renderWithLink() {
  const navigate = vi.fn((event: { preventDefault: () => void }) => event.preventDefault());
  const view = renderWithQuery(
    <>
      <a href="/account/" onClick={navigate}>
        내 계정
      </a>
      <PostEditor />
    </>,
  );
  return { ...view, navigate };
}

describe("TASK-31#7 작성·업로드 중 이탈 경고(FE-28)", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("TASK-31#7 TASK-31#B2 입력이 있으면 탭 닫기·새로고침(beforeunload)에 경고한다 — 빈 폼이면 경고하지 않는다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    expect(beforeUnloadBlocked()).toBe(false);
    await fill(screen.getByTestId("editor-title"), "쓰던 제목");
    expect(beforeUnloadBlocked()).toBe(true);
  });

  it("TASK-31#7 TASK-31#B2 업로드 중이면 앱 안 링크를 누를 때 확인을 받고, 취소하면 이동하지 않는다", async () => {
    const uploads = presignHandlers();
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), ...uploads.handlers);
    const xhrs = installFakeXhr();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const { navigate } = renderWithLink();

    await userEvent.upload(screen.getByTestId("editor-file-input"), png());
    await waitFor(() => expect(xhrs.instances).toHaveLength(1));
    expect(beforeUnloadBlocked()).toBe(true);

    expect(fireEvent.click(screen.getByRole("link", { name: "내 계정" }))).toBe(false);
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("업로드가 취소됩니다"));
    expect(navigate).not.toHaveBeenCalled();
    expect(xhrs.instances[0].aborted).toBe(false);

    confirm.mockReturnValue(true);
    fireEvent.click(screen.getByRole("link", { name: "내 계정" }));
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it("TASK-31#7 TASK-31#B2 글자 입력만 있으면 앱 안 이동은 묻지 않는다 — 초안(TASK-29)에 남아 다시 열면 복원된다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const { navigate } = renderWithLink();

    await fill(screen.getByTestId("editor-title"), "쓰던 제목");
    fireEvent.click(screen.getByRole("link", { name: "내 계정" }));

    expect(confirm).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(loadDraft(draftKeys.newPost, 1)).toMatchObject({ title: "쓰던 제목" });
  });

  it("TASK-31#7 TASK-31#B2 초안을 저장하지 못하면(저장 공간 오류) 글자 입력만 있어도 앱 안 이동에 확인을 받는다", async () => {
    server.use(meHandler(makeMe()), categoriesHandler());
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const { navigate } = renderWithLink();
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });

    await fill(screen.getByTestId("editor-title"), "쓰던 제목");
    fireEvent.click(screen.getByRole("link", { name: "내 계정" }));

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("임시 저장하지 못했습니다"));
    expect(navigate).not.toHaveBeenCalled();
  });

  it("TASK-31#7 저장에 성공해 이동하는 중에는 경고하지 않는다", async () => {
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/posts", () => HttpResponse.json(makePost({ id: 9, editable: true }), { status: 201 })),
    );
    renderWithQuery(<PostEditor />);

    await fill(screen.getByTestId("editor-title"), "제목");
    await fill(screen.getByTestId("editor-content"), "본문");
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/posts/view/?id=9"));
    expect(beforeUnloadBlocked()).toBe(false);
  });
});

describe("TASK-31#7 취소 버튼", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("TASK-31#7 히스토리가 없으면(새 탭으로 바로 연 편집기) 취소는 / 로 간다", async () => {
    vi.spyOn(window.history, "length", "get").mockReturnValue(1);
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(navigation.replace).toHaveBeenCalledWith("/");
    expect(navigation.back).not.toHaveBeenCalled();
  });

  it("TASK-31#7 히스토리가 있으면 취소는 뒤로 간다", async () => {
    vi.spyOn(window.history, "length", "get").mockReturnValue(3);
    server.use(meHandler(makeMe()), categoriesHandler());
    renderWithQuery(<PostEditor />);

    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(navigation.back).toHaveBeenCalledTimes(1);
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("TASK-31#7 TASK-31#B2 업로드 중 취소는 확인을 받고, 거절하면 머문다", async () => {
    vi.spyOn(window.history, "length", "get").mockReturnValue(3);
    const uploads = presignHandlers();
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), ...uploads.handlers);
    const xhrs = installFakeXhr();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    renderWithQuery(<PostEditor />);

    await userEvent.upload(screen.getByTestId("editor-file-input"), png());
    await waitFor(() => expect(xhrs.instances).toHaveLength(1));
    await userEvent.click(screen.getByRole("button", { name: "취소" }));

    expect(confirm).toHaveBeenCalled();
    expect(navigation.back).not.toHaveBeenCalled();
  });
});
