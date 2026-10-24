import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AdminView } from "@/app/admin/admin-view";
import { Pager } from "@/components/admin/pager";
import type { AdminActionEntry, AdminUser } from "@/lib/admin";
import { SORT_ORDER_MAX, SORT_ORDER_MESSAGE, updatePayload, validateCategoryForm } from "@/lib/admin";
import type { Category } from "@/lib/board";
import { queryKeys } from "@/lib/query";
import { CATEGORIES, makeSummary, pageOf } from "@/test/board";
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
// 재시도는 정지를 다시 보내지 않고 세션 끊기 전용 API(POST /admin/users/{id}/sessions/revoke)를 부른다.
describe("TASK-33#5 정지 경고(warnings)", () => {
  beforeEach(() => navigation.reset("/admin/"));

  function suspendFlow(suspendWarnings: string[], revokeResponses: string[][] = [[]]) {
    const calls = { suspend: [] as unknown[], revoke: [] as unknown[] };
    const state = { status: "ACTIVE" as AdminUser["status"] };
    server.use(
      meHandler(ADMIN),
      csrfHandler(),
      http.get("*/api/admin/users", () => HttpResponse.json(pageOf([makeUser({ status: state.status })]))),
      http.post("*/api/admin/users/:id/suspend", async ({ request }) => {
        calls.suspend.push(await request.json());
        state.status = "SUSPENDED";
        return HttpResponse.json({ ...makeUser({ status: state.status }), warnings: suspendWarnings });
      }),
      http.post("*/api/admin/users/:id/sessions/revoke", async ({ request, params }) => {
        calls.revoke.push({ id: params.id, body: await request.json() });
        const warnings = revokeResponses[Math.min(calls.revoke.length - 1, revokeResponses.length - 1)];
        return HttpResponse.json({ warnings });
      }),
    );
    return { calls, state };
  }

  async function suspend(reason: string) {
    const rendered = renderAdmin("users");
    await userEvent.click(await screen.findByTestId("admin-user-suspend"));
    const dialog = await screen.findByRole("alertdialog");
    if (reason) await userEvent.type(within(dialog).getByTestId("admin-reason-input"), reason);
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    return rendered;
  }

  it("TASK-33#5 session_revoke_failed 면 다이얼로그를 닫은 뒤 정지는 적용됐고 세션이 남았을 수 있다고 안내한다", async () => {
    suspendFlow(["session_revoke_failed"]);
    await suspend("스팸");
    const warning = await screen.findByTestId("admin-suspend-warning");
    expect(warning).toHaveTextContent("회원(#5)");
    expect(warning).toHaveTextContent("정지는 적용됐지만");
    expect(warning).toHaveTextContent("세션");
    expect(warning).toHaveTextContent("정지 상태는 그대로");
    expect(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" })).toBeInTheDocument();
  });

  it("TASK-33#5 다시 시도는 정지가 아니라 세션 끊기 API 를 같은 사유로 부르고, 경고가 없으면 안내를 닫는다", async () => {
    const { calls } = suspendFlow(["session_revoke_failed"], [[]]);
    await suspend("스팸");
    const warning = await screen.findByTestId("admin-suspend-warning");
    await userEvent.click(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" }));
    await waitFor(() => expect(screen.queryByTestId("admin-suspend-warning")).not.toBeInTheDocument());
    expect(calls.suspend).toEqual([{ reason: "스팸" }]);
    expect(calls.revoke).toEqual([{ id: "5", body: { reason: "스팸" } }]);
  });

  it("TASK-33#5 다시 시도도 경고면 안내를 그대로 둔다", async () => {
    const { calls } = suspendFlow(["session_revoke_failed"], [["session_revoke_failed"]]);
    await suspend("스팸");
    const warning = await screen.findByTestId("admin-suspend-warning");
    await userEvent.click(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" }));
    await waitFor(() => expect(calls.revoke).toHaveLength(1));
    await waitFor(() => expect(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" })).toBeEnabled());
    expect(screen.getByTestId("admin-suspend-warning")).toHaveTextContent("정지는 적용됐지만");
    expect(calls.suspend).toHaveLength(1);
  });

  it("TASK-33#5 최신 목록에서 그 사용자가 더는 정지 상태가 아니면 다시 시도하지 않고 안내를 닫는다", async () => {
    const { calls, state } = suspendFlow(["session_revoke_failed"]);
    const { client } = await suspend("스팸");
    const warning = await screen.findByTestId("admin-suspend-warning");
    expect(await screen.findByTestId("admin-user-unsuspend")).toBeInTheDocument();

    // 다른 관리자가 그사이 정지를 해제했다 — 목록을 다시 받으면 ACTIVE.
    state.status = "ACTIVE";
    await client.invalidateQueries({ queryKey: queryKeys.adminUsers });
    expect(await screen.findByTestId("admin-user-suspend")).toBeInTheDocument();

    await userEvent.click(within(warning).getByRole("button", { name: "세션 끊기 다시 시도" }));
    await waitFor(() => expect(screen.queryByTestId("admin-suspend-warning")).not.toBeInTheDocument());
    expect(calls.revoke).toHaveLength(0);
  });

  it("TASK-33#5 모르는 경고 코드는 일반 문장만 보이고 코드는 콘솔로 남기며, 닫을 수 있다", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    suspendFlow(["something_new"]);
    await suspend("");
    const warning = await screen.findByTestId("admin-suspend-warning");
    expect(warning).toHaveTextContent("서버가 확인이 필요한 경고를 보냈습니다");
    expect(warning).not.toHaveTextContent("something_new");
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("something_new"));
    await userEvent.click(within(warning).getByRole("button", { name: "닫기" }));
    expect(screen.queryByTestId("admin-suspend-warning")).not.toBeInTheDocument();
    warn.mockRestore();
  });

  it("TASK-34#3 session_revoke_failed(세션이 남았을 수 있음)가 든 정지 경고는 role=alert 로 알린다", async () => {
    suspendFlow(["session_revoke_failed"]);
    await suspend("스팸");
    expect(await screen.findByTestId("admin-suspend-warning")).toHaveAttribute("role", "alert");
  });

  it("TASK-34#3 session_revoke_failed 가 없는(모르는 코드만 있는) 정지 경고는 role=status 로 둔다", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    suspendFlow(["something_new"]);
    await suspend("");
    expect(await screen.findByTestId("admin-suspend-warning")).toHaveAttribute("role", "status");
    warn.mockRestore();
  });

  it("TASK-33#5 warnings 가 비었으면 안내가 없다", async () => {
    suspendFlow([]);
    await suspend("스팸");
    expect(await screen.findByTestId("admin-user-unsuspend")).toBeInTheDocument();
    expect(screen.queryByTestId("admin-suspend-warning")).not.toBeInTheDocument();
  });
});

// TASK-33#6 카테고리 순서는 서버 범위(0~10000 정수, TASK-27#4)와 같게 미리 검사한다.
describe("TASK-33#6 카테고리 순서 검사", () => {
  beforeEach(() => navigation.reset("/admin/"));

  const ok = { slug: "free-talk", name: "자유", sortOrder: "", adminOnly: false };

  it("TASK-33#6 validateCategoryForm: 0~10000 정수만 통과, 그 밖은 한국어 오류", () => {
    for (const value of ["0", "10000", "5", " 7 "]) {
      expect(validateCategoryForm({ ...ok, sortOrder: value }).sortOrder, value).toBeUndefined();
    }
    for (const value of ["-1", "10001", "1.5", "1e3", "abc", "99999999999", "+3"]) {
      expect(validateCategoryForm({ ...ok, sortOrder: value }).sortOrder, value).toBe(SORT_ORDER_MESSAGE);
    }
    expect(SORT_ORDER_MESSAGE).toBe("순서는 0~10000 사이의 정수여야 합니다");
    expect(SORT_ORDER_MAX).toBe(10000);
  });

  it("TASK-33#6 수정에서 순서를 바꾸지 않았으면(PATCH 에서 빠짐) 범위 밖 기존 값도 검사하지 않는다", () => {
    const legacy: Category = { id: 9, slug: "old", name: "옛", sortOrder: 20000, adminOnly: false };
    const form = { slug: "old", name: "새", sortOrder: "20000", adminOnly: false };
    expect(validateCategoryForm(form, legacy).sortOrder).toBeUndefined();
    expect(validateCategoryForm({ ...form, sortOrder: "20001" }, legacy).sortOrder).toBe(SORT_ORDER_MESSAGE);
    expect(validateCategoryForm(form).sortOrder).toBe(SORT_ORDER_MESSAGE);
  });

  it("TASK-33#6 '바꾸지 않음'은 검사와 PATCH 본문 모두 숫자로 비교한다(020000 = 20000)", () => {
    const legacy: Category = { id: 9, slug: "old", name: "옛", sortOrder: 20000, adminOnly: false };
    const form = { slug: "old", name: "새", sortOrder: "020000", adminOnly: false };
    expect(validateCategoryForm(form, legacy).sortOrder).toBeUndefined();
    expect(updatePayload(legacy, form)).toEqual({ name: "새" });
  });

  it("TASK-33#6 폼: 10001 이면 요청하지 않고 순서 칸에 오류를 보인다", async () => {
    const posts: unknown[] = [];
    server.use(
      meHandler(ADMIN),
      csrfHandler(),
      http.get("*/api/categories", () => HttpResponse.json(CATEGORIES)),
      http.post("*/api/admin/categories", async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json(CATEGORIES[0], { status: 201 });
      }),
    );
    renderAdmin("categories");
    await screen.findAllByTestId("admin-category-row");
    await userEvent.type(screen.getByTestId("admin-category-slug"), "qna");
    await userEvent.type(screen.getByTestId("admin-category-name"), "질문");
    await userEvent.type(screen.getByTestId("admin-category-sort-order"), "10001");
    await userEvent.click(screen.getByTestId("admin-category-submit"));
    expect(await screen.findByTestId("admin-category-sort-order-error")).toHaveTextContent(SORT_ORDER_MESSAGE);
    expect(screen.getByTestId("admin-category-sort-order")).toHaveAttribute("aria-invalid", "true");
    expect(posts).toHaveLength(0);
  });

  it("TASK-33#6 폼: 범위 밖 기존 순서의 카테고리도 이름만 바꾸면 순서 없이 PATCH 한다", async () => {
    const patches: unknown[] = [];
    const legacy: Category = { id: 9, slug: "old", name: "옛", sortOrder: 20000, adminOnly: false };
    server.use(
      meHandler(ADMIN),
      csrfHandler(),
      http.get("*/api/categories", () => HttpResponse.json([legacy])),
      http.patch("*/api/admin/categories/:id", async ({ request }) => {
        patches.push(await request.json());
        return HttpResponse.json({ ...legacy, name: "새" });
      }),
    );
    renderAdmin("categories");
    await userEvent.click(await screen.findByTestId("admin-category-edit"));
    const name = screen.getByTestId("admin-category-name");
    await userEvent.clear(name);
    await userEvent.type(name, "새");
    await userEvent.click(screen.getByTestId("admin-category-submit"));
    await waitFor(() => expect(patches).toEqual([{ name: "새" }]));
  });
});
