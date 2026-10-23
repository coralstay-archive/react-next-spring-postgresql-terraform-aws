import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AdminView } from "@/app/admin/admin-view";
import { Pager } from "@/components/admin/pager";
import type { AdminActionEntry, AdminUser } from "@/lib/admin";
import { queryKeys } from "@/lib/query";
import { makeSummary, pageOf } from "@/test/board";
import { server } from "@/test/server";
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
