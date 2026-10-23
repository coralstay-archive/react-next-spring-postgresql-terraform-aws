import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AdminView } from "@/app/admin/admin-view";
import { Pager } from "@/components/admin/pager";
import type { AdminActionEntry, AdminUser } from "@/lib/admin";
import { queryKeys } from "@/lib/query";
import { makeSummary, pageOf } from "@/test/board";
import { csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

const ADMIN = makeMe({ id: 1, nickname: "관리자", role: "ADMIN" });

function makeUser(overrides: Partial<AdminUser> = {}): AdminUser {
  return {
    id: 5,
    nickname: "회원",
    email: "member@example.com",
    phone: null,
    role: "USER",
    status: "ACTIVE",
    providers: ["LOCAL"],
    createdAt: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

function makeAction(overrides: Partial<AdminActionEntry> = {}): AdminActionEntry {
  return {
    id: 1,
    adminId: 1,
    adminNickname: "관리자",
    action: "USER_SUSPEND",
    targetType: "USER",
    targetId: 5,
    reason: null,
    createdAt: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

function renderAdmin(tab: string) {
  navigation.reset("/admin/", `tab=${tab}`);
  return renderWithQuery(<AdminView />);
}

/** 요청 page 를 기록하고, totalPages 를 넘는 page 는 빈 목록을 준다(서버와 같다). */
function pagedHandler<T>(path: string, pages: number[], total: () => number, item: () => T) {
  return http.get(path, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get("page"));
    pages.push(page);
    const totalPages = total();
    return HttpResponse.json(pageOf(page < totalPages ? [item()] : [], { page, totalPages, totalElements: totalPages * 20 }));
  });
}

// TASK-33#3 (FE-33) 관리자 페이저: 처리·재조회로 결과가 줄어 지금 쪽이 범위 밖이 되면 마지막 쪽으로 옮긴다.
describe("TASK-33#3 관리자 페이저 범위 클램프", () => {
  beforeEach(() => navigation.reset("/admin/"));

  it("TASK-33#3 사용자 탭: 3쪽에서 결과가 2쪽으로 줄면 2쪽으로 옮겨 다시 받는다", async () => {
    const pages: number[] = [];
    let total = 3;
    server.use(meHandler(ADMIN), pagedHandler("*/api/admin/users", pages, () => total, () => makeUser()));
    const { client } = renderAdmin("users");

    await userEvent.click(await screen.findByTestId("admin-users-next"));
    await screen.findByText("2 / 3");
    await userEvent.click(screen.getByTestId("admin-users-next"));
    await screen.findByText("3 / 3");

    total = 2;
    await client.invalidateQueries({ queryKey: queryKeys.adminUsers });
    expect(await screen.findByText("2 / 2")).toBeInTheDocument();
    await waitFor(() => expect(pages.at(-1)).toBe(1));
    expect(await screen.findByTestId("admin-user-row")).toBeInTheDocument();
  });

  it("TASK-33#3 결과가 0건이 되면 1쪽으로 돌아간다", async () => {
    const pages: number[] = [];
    let total = 2;
    server.use(meHandler(ADMIN), pagedHandler("*/api/admin/users", pages, () => total, () => makeUser()));
    const { client } = renderAdmin("users");

    await userEvent.click(await screen.findByTestId("admin-users-next"));
    await screen.findByText("2 / 2");
    total = 0;
    await client.invalidateQueries({ queryKey: queryKeys.adminUsers });
    await waitFor(() => expect(pages.at(-1)).toBe(0));
    expect(await screen.findByTestId("admin-users-empty")).toBeInTheDocument();
  });

  it("TASK-33#3 감사 로그 탭도 범위 밖 쪽을 마지막 쪽으로 옮긴다", async () => {
    const pages: number[] = [];
    let total = 2;
    server.use(meHandler(ADMIN), pagedHandler("*/api/admin/actions", pages, () => total, () => makeAction()));
    const { client } = renderAdmin("actions");

    await userEvent.click(await screen.findByTestId("admin-actions-next"));
    await screen.findByText("2 / 2");
    total = 1;
    await client.invalidateQueries({ queryKey: queryKeys.adminActions });
    await waitFor(() => expect(pages.at(-1)).toBe(0));
    expect(await screen.findByTestId("admin-action-row")).toBeInTheDocument();
  });

  it("TASK-33#3 글 탭도 범위 밖 쪽을 마지막 쪽으로 옮긴다", async () => {
    const pages: number[] = [];
    let total = 2;
    server.use(meHandler(ADMIN), pagedHandler("*/api/posts", pages, () => total, () => makeSummary()));
    const { client } = renderAdmin("posts");

    await userEvent.click(await screen.findByTestId("admin-posts-next"));
    await screen.findByText("2 / 2");
    total = 1;
    await client.invalidateQueries({ queryKey: queryKeys.postLists });
    await waitFor(() => expect(pages.at(-1)).toBe(0));
    expect(await screen.findByTestId("admin-post-row")).toBeInTheDocument();
  });
});

// TASK-33#4 관리자 목록도 서버 OFFSET 상한(page × size ≤ 10,000)이 있다(docs/api.md): 20개씩이면 0부터 500쪽까지.
describe("TASK-33#4 관리자 페이저 OFFSET 상한", () => {
  it("TASK-33#4 상한 쪽에서는 totalPages 가 더 있어도 '다음' 을 막고 안내한다", () => {
    const onChange = vi.fn();
    render(<Pager page={500} totalPages={900} onChange={onChange} testId="t" />);
    expect(screen.getByTestId("t-next")).toBeDisabled();
    expect(screen.getByTestId("t-cap")).toHaveTextContent("검색");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("TASK-33#4 상한 바로 앞 쪽은 '다음' 을 누를 수 있다", () => {
    render(<Pager page={499} totalPages={900} onChange={vi.fn()} testId="t" />);
    expect(screen.getByTestId("t-next")).toBeEnabled();
    expect(screen.queryByTestId("t-cap")).not.toBeInTheDocument();
  });

  it("TASK-33#4 상한을 넘는 쪽이면 상한 쪽으로 옮긴다", () => {
    const onChange = vi.fn();
    render(<Pager page={600} totalPages={900} onChange={onChange} testId="t" />);
    expect(onChange).toHaveBeenCalledWith(500);
  });
});

// TASK-33#5 정지 응답의 warnings(TASK-27#3): session_revoke_failed 면 정지는 됐지만 세션을 다 끊지 못했을 수 있음을 알리고 재시도를 준다.
describe("TASK-33#5 정지 경고(warnings)", () => {
  beforeEach(() => navigation.reset("/admin/"));

  function suspendFlow(responses: string[][]) {
    const bodies: unknown[] = [];
    let status: AdminUser["status"] = "ACTIVE";
    server.use(
      meHandler(ADMIN),
      csrfHandler(),
      http.get("*/api/admin/users", () => HttpResponse.json(pageOf([makeUser({ status })]))),
      http.post("*/api/admin/users/:id/suspend", async ({ request }) => {
        bodies.push(await request.json());
        status = "SUSPENDED";
        const warnings = responses[Math.min(bodies.length - 1, responses.length - 1)];
        return HttpResponse.json({ ...makeUser({ status }), warnings });
      }),
    );
    return bodies;
  }

  async function suspend(reason: string) {
    renderAdmin("users");
    await userEvent.click(await screen.findByTestId("admin-user-suspend"));
    const dialog = await screen.findByRole("alertdialog");
    if (reason) await userEvent.type(within(dialog).getByTestId("admin-reason-input"), reason);
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  }

  it("TASK-33#5 session_revoke_failed 면 다이얼로그를 닫은 뒤 정지는 적용됐고 세션이 남았을 수 있다고 안내한다", async () => {
    suspendFlow([["session_revoke_failed"]]);
    await suspend("스팸");
    const warning = await screen.findByTestId("admin-suspend-warning");
    expect(warning).toHaveTextContent("회원(#5)");
    expect(warning).toHaveTextContent("정지는 적용됐지만");
    expect(warning).toHaveTextContent("세션");
    expect(warning).toHaveTextContent("다시 정지");
    expect(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" })).toBeInTheDocument();
  });

  it("TASK-33#5 다시 시도는 같은 사유로 정지를 다시 보내고(멱등), 경고가 없으면 안내를 닫는다", async () => {
    const bodies = suspendFlow([["session_revoke_failed"], []]);
    await suspend("스팸");
    const warning = await screen.findByTestId("admin-suspend-warning");
    await userEvent.click(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" }));
    await waitFor(() => expect(screen.queryByTestId("admin-suspend-warning")).not.toBeInTheDocument());
    expect(bodies).toEqual([{ reason: "스팸" }, { reason: "스팸" }]);
  });

  it("TASK-33#5 다시 시도도 경고면 안내를 그대로 둔다", async () => {
    const bodies = suspendFlow([["session_revoke_failed"]]);
    await suspend("스팸");
    const warning = await screen.findByTestId("admin-suspend-warning");
    await userEvent.click(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    await waitFor(() => expect(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" })).toBeEnabled());
    expect(screen.getByTestId("admin-suspend-warning")).toHaveTextContent("정지는 적용됐지만");
  });

  it("TASK-33#5 모르는 경고 코드는 코드를 그대로 알리고, 닫을 수 있다", async () => {
    suspendFlow([["something_new"]]);
    await suspend("");
    const warning = await screen.findByTestId("admin-suspend-warning");
    expect(warning).toHaveTextContent("something_new");
    await userEvent.click(within(warning).getByRole("button", { name: "닫기" }));
    expect(screen.queryByTestId("admin-suspend-warning")).not.toBeInTheDocument();
  });

  it("TASK-33#5 warnings 가 비었으면 안내가 없다", async () => {
    suspendFlow([[]]);
    await suspend("스팸");
    expect(await screen.findByTestId("admin-user-unsuspend")).toBeInTheDocument();
    expect(screen.queryByTestId("admin-suspend-warning")).not.toBeInTheDocument();
  });
});
