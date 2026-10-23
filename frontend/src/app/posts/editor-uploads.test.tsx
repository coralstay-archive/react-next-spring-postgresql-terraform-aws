import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PostEditor } from "@/components/board/post-editor";
import { UPLOAD_STALL_MS } from "@/lib/uploads";
import { categoriesHandler, makePost, presignHandlers } from "@/test/board";
import { csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";
import { installFakeXhr } from "@/test/xhr";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

function png(name: string) {
  return new File([new Uint8Array(8)], name, { type: "image/png" });
}

async function fill(element: HTMLElement, text: string) {
  await userEvent.click(element);
  await userEvent.paste(text);
}

function attachmentCells() {
  return within(screen.getByTestId("editor-attachments")).queryAllByRole("listitem");
}

describe("TASK-31#5 편집기 업로드 취소·빼기·정체·언마운트 abort(FE-26)", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("TASK-31#5 업로드 중인 이미지는 '업로드 취소'로 PUT 을 끊고 목록에서 뺀다(등록 버튼이 다시 켜진다)", async () => {
    const uploads = presignHandlers();
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), ...uploads.handlers);
    const xhrs = installFakeXhr();
    renderWithQuery(<PostEditor />);

    await userEvent.upload(screen.getByTestId("editor-file-input"), png("a.png"));
    await waitFor(() => expect(xhrs.instances).toHaveLength(1));
    expect(screen.getByTestId("editor-submit")).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "첨부 이미지 1 업로드 취소" }));
    expect(xhrs.instances[0].aborted).toBe(true);
    expect(attachmentCells()).toHaveLength(0);
    expect(uploads.confirmed).toEqual([]);
    expect(screen.getByTestId("editor-submit")).toBeEnabled();
  });

  it("TASK-31#5 차례를 기다리는 이미지를 취소하면 presign 도 하지 않는다", async () => {
    const uploads = presignHandlers();
    let presigns = 0;
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/attachments/presign", () => {
        presigns++;
        return undefined;
      }),
      ...uploads.handlers,
    );
    const xhrs = installFakeXhr();
    renderWithQuery(<PostEditor />);

    await userEvent.upload(screen.getByTestId("editor-file-input"), [png("a.png"), png("b.png")]);
    await waitFor(() => expect(xhrs.instances).toHaveLength(1));
    await userEvent.click(screen.getByRole("button", { name: "첨부 이미지 2 업로드 취소" }));

    xhrs.instances[0].respond(200);
    await waitFor(() => expect(within(screen.getByTestId("editor-attachments")).getAllByRole("img")).toHaveLength(1));
    expect(attachmentCells()).toHaveLength(1);
    expect(presigns).toBe(1);
    expect(xhrs.instances).toHaveLength(1);
  });

  it("TASK-31#5 30초 동안 진행이 없으면 그 이미지에 오류를 보이고, 빼면 나머지로 등록할 수 있다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setTimeout", "clearTimeout"] });
    const uploads = presignHandlers();
    const bodies: unknown[] = [];
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      ...uploads.handlers,
      http.post("*/api/posts", async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(makePost({ id: 5, editable: true }), { status: 201 });
      }),
    );
    const xhrs = installFakeXhr();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithQuery(<PostEditor />);

    await user.upload(screen.getByTestId("editor-file-input"), png("a.png"));
    await vi.waitFor(() => expect(xhrs.instances).toHaveLength(1));
    await act(() => vi.advanceTimersByTimeAsync(UPLOAD_STALL_MS));

    expect(await screen.findByText(/30초 동안 진행되지 않아/)).toBeInTheDocument();
    expect(xhrs.instances[0].aborted).toBe(true);
    await user.click(screen.getByRole("button", { name: "첨부 이미지 1 빼기" }));
    expect(attachmentCells()).toHaveLength(0);

    await user.click(screen.getByTestId("editor-title"));
    await user.paste("제목");
    await user.click(screen.getByTestId("editor-content"));
    await user.paste("본문");
    await user.click(screen.getByTestId("editor-submit"));
    await vi.waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ attachmentIds: [] });
  });

  it("TASK-31#5 편집기가 언마운트되면 진행 중인 업로드를 abort 한다", async () => {
    const uploads = presignHandlers();
    server.use(meHandler(makeMe()), categoriesHandler(), csrfHandler(), ...uploads.handlers);
    const xhrs = installFakeXhr();
    const { unmount } = renderWithQuery(<PostEditor />);

    await fill(screen.getByTestId("editor-title"), "제목");
    await userEvent.upload(screen.getByTestId("editor-file-input"), png("a.png"));
    await waitFor(() => expect(xhrs.instances).toHaveLength(1));

    unmount();
    expect(xhrs.instances[0].aborted).toBe(true);
    expect(uploads.confirmed).toEqual([]);
  });
});

describe("TASK-31#6 편집기: confirm 만 다시 시도(FE-27)", () => {
  beforeEach(() => {
    navigation.reset("/posts/new/");
  });

  it("TASK-31#6 PUT 뒤 confirm 이 503 이면 '확인 다시 시도'로 confirm 만 다시 해서 붙인다(presign·PUT 은 한 번)", async () => {
    const uploads = presignHandlers();
    let presigns = 0;
    let puts = 0;
    let confirms = 0;
    const bodies: unknown[] = [];
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/attachments/presign", () => {
        presigns++;
        return undefined;
      }),
      http.put("http://storage.test/bucket/*", () => {
        puts++;
        return undefined;
      }),
      http.post("*/api/attachments/:id/confirm", () =>
        confirms++ === 0 ? HttpResponse.json({ status: 503, error: "storage_unavailable", message: "x" }, { status: 503 }) : undefined,
      ),
      ...uploads.handlers,
      http.post("*/api/posts", async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(makePost({ id: 8, editable: true }), { status: 201 });
      }),
    );
    renderWithQuery(<PostEditor />);

    await userEvent.upload(screen.getByTestId("editor-file-input"), png("a.png"));
    expect(await screen.findByText(/이미지 저장소에 일시적인 문제가 있습니다/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "첨부 이미지 1 확인 다시 시도" }));

    await waitFor(() => expect(within(screen.getByTestId("editor-attachments")).getAllByRole("img")).toHaveLength(1));
    expect({ presigns, puts, confirms }).toEqual({ presigns: 1, puts: 1, confirms: 2 });

    await fill(screen.getByTestId("editor-title"), "제목");
    await fill(screen.getByTestId("editor-content"), "본문");
    await userEvent.click(screen.getByTestId("editor-submit"));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ attachmentIds: [100] });
  });

  it("TASK-31#6 confirm 이 410 attachment_expired 면 다시 올리라고 안내하고 '확인 다시 시도'는 없다", async () => {
    const uploads = presignHandlers();
    server.use(
      meHandler(makeMe()),
      categoriesHandler(),
      csrfHandler(),
      http.post("*/api/attachments/:id/confirm", () =>
        HttpResponse.json({ status: 410, error: "attachment_expired", message: "x" }, { status: 410 }),
      ),
      ...uploads.handlers,
    );
    renderWithQuery(<PostEditor />);

    await userEvent.upload(screen.getByTestId("editor-file-input"), png("a.png"));
    expect(await screen.findByText(/만료된 이미지입니다. 다시 올려 주세요/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /확인 다시 시도/ })).not.toBeInTheDocument();
  });
});
