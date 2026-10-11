import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AdminView } from "@/app/admin/admin-view";
import type { AdminActionEntry, AdminUser } from "@/lib/admin";
import { validateCategoryForm } from "@/lib/admin";
import { ERROR_MESSAGES } from "@/lib/errors";
import { queryKeys } from "@/lib/query";
import { CATEGORIES, makeSummary, pageOf } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
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
    providers: ["LOCAL", "GOOGLE"],
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
    reason: "스팸",
    createdAt: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

interface Recorded {
  method: string;
  path: string;
  query: Record<string, string>;
  body: unknown;
  csrf: string | null;
}

/** 요청을 기록한다(쓰기 요청의 메서드·경로·본문·CSRF 헤더 확인용). */
function recorder() {
  const calls: Recorded[] = [];
  const record = async (request: Request) => {
    const url = new URL(request.url);
    const text = request.method === "GET" ? "" : await request.text();
    calls.push({
      method: request.method,
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      body: text ? JSON.parse(text) : undefined,
      csrf: request.headers.get("X-XSRF-TOKEN"),
    });
  };
  const of = (method: string, path: string) => calls.filter((c) => c.method === method && c.path === path);
  return { calls, record, of };
}

function renderAdmin(tab?: string) {
  navigation.reset("/admin/", tab ? `tab=${tab}` : "");
  return renderWithQuery(<AdminView />);
}

describe("TASK-15#1 관리자 화면 탭", () => {
  beforeEach(() => navigation.reset("/admin/"));

  it("TASK-15#1 탭은 URL ?tab= 과 동기화한다(기본 users, 클릭하면 replace)", async () => {
    const rec = recorder();
    server.use(
      meHandler(ADMIN),
      http.get("*/api/admin/users", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json(pageOf([makeUser()]));
      }),
    );
    renderAdmin();
    expect(await screen.findByTestId("admin-user-row")).toBeInTheDocument();
    expect(screen.getByTestId("admin-tab-users")).toHaveAttribute("aria-selected", "true");

    await userEvent.click(screen.getByTestId("admin-tab-categories"));
    expect(navigation.replace).toHaveBeenCalledWith("/admin/?tab=categories", { scroll: false });
  });

  it("TASK-15#1 모르는 tab 값은 사용자 탭으로 본다", async () => {
    server.use(meHandler(ADMIN), http.get("*/api/admin/users", () => HttpResponse.json(pageOf([]))));
    renderAdmin("nope");
    expect(await screen.findByTestId("admin-users-empty")).toBeInTheDocument();
    expect(screen.getByTestId("admin-tab-users")).toHaveAttribute("aria-selected", "true");
  });
});

describe("TASK-15#1 사용자 탭: 검색·상태 필터·페이징·정지/해제", () => {
  beforeEach(() => navigation.reset("/admin/"));

  it("TASK-15#1 목록 요청은 page=0&size=20, 검색어·상태 필터를 쿼리로 보낸다", async () => {
    const rec = recorder();
    server.use(
      meHandler(ADMIN),
      http.get("*/api/admin/users", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json(pageOf([makeUser()], { totalPages: 3, totalElements: 41 }));
      }),
    );
    renderAdmin("users");
    const row = await screen.findByTestId("admin-user-row");
    expect(rec.calls[0].query).toEqual({ page: "0", size: "20" });
    // 제공자 배지와 상태 배지
    expect(within(row).getAllByTestId("admin-user-provider").map((b) => b.textContent)).toEqual(["LOCAL", "GOOGLE"]);
    expect(within(row).getByTestId("admin-user-status")).toHaveTextContent("활성");

    await userEvent.type(screen.getByTestId("admin-user-search-input"), "  kim ");
    await userEvent.click(screen.getByTestId("admin-user-search-submit"));
    await waitFor(() => expect(rec.calls.at(-1)!.query).toEqual({ q: "kim", page: "0", size: "20" }));

    await userEvent.selectOptions(screen.getByTestId("admin-user-status-filter"), "SUSPENDED");
    await waitFor(() =>
      expect(rec.calls.at(-1)!.query).toEqual({ q: "kim", status: "SUSPENDED", page: "0", size: "20" }),
    );

    await userEvent.click(await screen.findByTestId("admin-users-next"));
    await waitFor(() => expect(rec.calls.at(-1)!.query).toMatchObject({ page: "1" }));
  });

  it("TASK-15#1 정지: 확인 다이얼로그에서 사유와 함께 POST suspend(CSRF 헤더) 후 목록을 다시 받는다", async () => {
    const rec = recorder();
    let status: AdminUser["status"] = "ACTIVE";
    server.use(
      meHandler(ADMIN),
      csrfHandler("csrf-token"),
      http.get("*/api/admin/users", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json(pageOf([makeUser({ status })]));
      }),
      http.post("*/api/admin/users/:id/suspend", async ({ request }) => {
        await rec.record(request);
        status = "SUSPENDED";
        return HttpResponse.json(makeUser({ status }));
      }),
    );
    renderAdmin("users");

    await userEvent.click(await screen.findByTestId("admin-user-suspend"));
    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).getByText(/회원/)).toBeInTheDocument();
    await userEvent.type(within(dialog).getByTestId("admin-reason-input"), "스팸");
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));

    await waitFor(() => expect(rec.of("POST", "/api/admin/users/5/suspend")).toHaveLength(1));
    const call = rec.of("POST", "/api/admin/users/5/suspend")[0];
    expect(call.body).toEqual({ reason: "스팸" });
    expect(call.csrf).toBe("csrf-token");
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    await waitFor(() => expect(rec.of("GET", "/api/admin/users").length).toBeGreaterThanOrEqual(2));
    expect(await screen.findByTestId("admin-user-unsuspend")).toBeInTheDocument();
  });

  it("TASK-15#1 해제: 사유를 비우면 빈 본문 {} 로 POST unsuspend", async () => {
    const rec = recorder();
    server.use(
      meHandler(ADMIN),
      csrfHandler(),
      http.get("*/api/admin/users", () => HttpResponse.json(pageOf([makeUser({ status: "SUSPENDED" })]))),
      http.post("*/api/admin/users/:id/unsuspend", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json(makeUser());
      }),
    );
    renderAdmin("users");
    expect(await screen.findByTestId("admin-user-status")).toHaveTextContent("정지");
    await userEvent.click(screen.getByTestId("admin-user-unsuspend"));
    await userEvent.click(within(await screen.findByRole("alertdialog")).getByTestId("admin-confirm-submit"));
    await waitFor(() => expect(rec.of("POST", "/api/admin/users/5/unsuspend")).toHaveLength(1));
    expect(rec.calls[0].body).toEqual({});
  });

  it("TASK-15#1 취소하면 요청하지 않는다", async () => {
    server.use(meHandler(ADMIN), http.get("*/api/admin/users", () => HttpResponse.json(pageOf([makeUser()]))));
    renderAdmin("users");
    await userEvent.click(await screen.findByTestId("admin-user-suspend"));
    await userEvent.click(within(await screen.findByRole("alertdialog")).getByTestId("admin-confirm-cancel"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });

  it("TASK-15#1 사유가 500자를 넘으면 요청하지 않고 안내한다", async () => {
    server.use(meHandler(ADMIN), http.get("*/api/admin/users", () => HttpResponse.json(pageOf([makeUser()]))));
    renderAdmin("users");
    await userEvent.click(await screen.findByTestId("admin-user-suspend"));
    const dialog = await screen.findByRole("alertdialog");
    const input = within(dialog).getByTestId("admin-reason-input");
    await userEvent.click(input);
    await userEvent.paste("가".repeat(501));
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));
    expect(await within(dialog).findByTestId("admin-confirm-error")).toHaveTextContent("500자");
  });

  it.each([
    ["cannot_suspend_self", "자기 자신"],
    ["cannot_suspend_admin", "관리자"],
  ])("TASK-15#1 409 %s 는 한국어 안내를 다이얼로그에 보인다", async (code, text) => {
    server.use(
      meHandler(ADMIN),
      csrfHandler(),
      http.get("*/api/admin/users", () => HttpResponse.json(pageOf([makeUser({ role: "ADMIN" })]))),
      http.post("*/api/admin/users/:id/suspend", () => apiError(409, code, "server message")),
    );
    renderAdmin("users");
    await userEvent.click(await screen.findByTestId("admin-user-suspend"));
    const dialog = await screen.findByRole("alertdialog");
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));
    const error = await within(dialog).findByTestId("admin-confirm-error");
    expect(error).toHaveTextContent(ERROR_MESSAGES[code]);
    expect(error).toHaveTextContent(text);
  });
});

describe("TASK-15#1 글 탭: includeHidden 목록, 숨김·해제·복구", () => {
  beforeEach(() => navigation.reset("/admin/"));

  it("TASK-15#1 includeHidden=true 로 목록을 받고 상태 배지·상세 링크·알맞은 액션만 보인다", async () => {
    const rec = recorder();
    server.use(
      meHandler(ADMIN),
      http.get("*/api/posts", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json(
          pageOf([
            makeSummary({ id: 1, title: "보통 글" }),
            makeSummary({ id: 2, title: "숨긴 글", hidden: true }),
            makeSummary({ id: 3, title: "지운 글", deleted: true }),
          ]),
        );
      }),
    );
    renderAdmin("posts");
    const rows = await screen.findAllByTestId("admin-post-row");
    expect(rec.calls[0].query).toMatchObject({ includeHidden: "true", page: "0", size: "20", sort: "latest" });
    expect(within(rows[0]).getByRole("link", { name: "보통 글" })).toHaveAttribute("href", "/posts/view/?id=1");
    expect(within(rows[0]).getByTestId("admin-post-hide")).toBeInTheDocument();
    expect(within(rows[0]).queryByTestId("admin-post-restore")).not.toBeInTheDocument();
    expect(within(rows[1]).getByTestId("badge-hidden")).toBeInTheDocument();
    expect(within(rows[1]).getByTestId("admin-post-unhide")).toBeInTheDocument();
    expect(within(rows[2]).getByTestId("badge-deleted")).toBeInTheDocument();
    expect(within(rows[2]).getByTestId("admin-post-restore")).toBeInTheDocument();
  });

  it.each([
    ["hide", 1, { hidden: false, deleted: false }],
    ["unhide", 2, { hidden: true, deleted: false }],
    ["restore", 3, { hidden: false, deleted: true }],
  ] as const)("TASK-15#1 %s: 사유와 함께 POST /api/admin/posts/{id}/%s 후 글 목록을 다시 받는다", async (action, id, flags) => {
    const rec = recorder();
    server.use(
      meHandler(ADMIN),
      csrfHandler("csrf-token"),
      http.get("*/api/posts", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json(pageOf([makeSummary({ id, ...flags })]));
      }),
      http.post(`*/api/admin/posts/:id/${action}`, async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json({ id, hidden: false, deleted: false, hiddenAt: null, deletedAt: null });
      }),
    );
    renderAdmin("posts");
    await userEvent.click(await screen.findByTestId(`admin-post-${action}`));
    const dialog = await screen.findByRole("alertdialog");
    await userEvent.type(within(dialog).getByTestId("admin-reason-input"), "신고");
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));

    await waitFor(() => expect(rec.of("POST", `/api/admin/posts/${id}/${action}`)).toHaveLength(1));
    const call = rec.of("POST", `/api/admin/posts/${id}/${action}`)[0];
    expect(call.body).toEqual({ reason: "신고" });
    expect(call.csrf).toBe("csrf-token");
    await waitFor(() => expect(rec.of("GET", "/api/posts").length).toBeGreaterThanOrEqual(2));
  });

  it("TASK-15#1 404 post_not_found 는 한국어 안내", async () => {
    server.use(
      meHandler(ADMIN),
      csrfHandler(),
      http.get("*/api/posts", () => HttpResponse.json(pageOf([makeSummary({ id: 9 })]))),
      http.post("*/api/admin/posts/:id/hide", () => apiError(404, "post_not_found")),
    );
    renderAdmin("posts");
    await userEvent.click(await screen.findByTestId("admin-post-hide"));
    const dialog = await screen.findByRole("alertdialog");
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));
    expect(await within(dialog).findByTestId("admin-confirm-error")).toHaveTextContent(ERROR_MESSAGES.post_not_found);
  });
});

describe("TASK-15#1 카테고리 탭: 생성·수정·삭제", () => {
  beforeEach(() => navigation.reset("/admin/"));

  function categoryServer(rec: ReturnType<typeof recorder>) {
    server.use(
      meHandler(ADMIN),
      csrfHandler("csrf-token"),
      http.get("*/api/categories", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json(CATEGORIES);
      }),
    );
  }

  it("TASK-15#1 목록은 slug·이름·순서·관리자 전용을 보인다", async () => {
    const rec = recorder();
    categoryServer(rec);
    renderAdmin("categories");
    const rows = await screen.findAllByTestId("admin-category-row");
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("notice")).toBeInTheDocument();
    expect(within(rows[0]).getByText("공지")).toBeInTheDocument();
    expect(within(rows[0]).getByTestId("admin-category-admin-only-badge")).toBeInTheDocument();
    expect(within(rows[1]).queryByTestId("admin-category-admin-only-badge")).not.toBeInTheDocument();
  });

  it("TASK-15#1 생성: POST /api/admin/categories 본문(slug·name·sortOrder·adminOnly)과 CSRF, 성공하면 공개 카테고리 쿼리를 무효화", async () => {
    const rec = recorder();
    categoryServer(rec);
    server.use(
      http.post("*/api/admin/categories", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json({ id: 3, slug: "qna", name: "질문", sortOrder: 5, adminOnly: true }, { status: 201 });
      }),
    );
    const { client } = renderAdmin("categories");
    await screen.findAllByTestId("admin-category-row");
    const invalidate = vi.spyOn(client, "invalidateQueries");

    await userEvent.type(screen.getByTestId("admin-category-slug"), "qna");
    await userEvent.type(screen.getByTestId("admin-category-name"), " 질문 ");
    await userEvent.type(screen.getByTestId("admin-category-sort-order"), "5");
    await userEvent.click(screen.getByTestId("admin-category-admin-only"));
    await userEvent.click(screen.getByTestId("admin-category-submit"));

    await waitFor(() => expect(rec.of("POST", "/api/admin/categories")).toHaveLength(1));
    const call = rec.of("POST", "/api/admin/categories")[0];
    expect(call.body).toEqual({ slug: "qna", name: "질문", sortOrder: 5, adminOnly: true });
    expect(call.csrf).toBe("csrf-token");
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.categories }));
    await waitFor(() => expect(rec.of("GET", "/api/categories").length).toBeGreaterThanOrEqual(2));
    // 폼은 비워진다
    expect(screen.getByTestId("admin-category-slug")).toHaveValue("");
  });

  it("TASK-15#1 생성: 순서를 비우면 sortOrder 를 보내지 않는다(서버가 맨 뒤로)", async () => {
    const rec = recorder();
    categoryServer(rec);
    server.use(
      http.post("*/api/admin/categories", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json({ id: 3, slug: "qna", name: "질문", sortOrder: 2, adminOnly: false }, { status: 201 });
      }),
    );
    renderAdmin("categories");
    await screen.findAllByTestId("admin-category-row");
    await userEvent.type(screen.getByTestId("admin-category-slug"), "qna");
    await userEvent.type(screen.getByTestId("admin-category-name"), "질문");
    await userEvent.click(screen.getByTestId("admin-category-submit"));
    await waitFor(() => expect(rec.of("POST", "/api/admin/categories")).toHaveLength(1));
    expect(rec.of("POST", "/api/admin/categories")[0].body).toEqual({ slug: "qna", name: "질문", adminOnly: false });
  });

  it("TASK-15#1 폼 검증: slug 규칙·이름 길이를 어기면 요청하지 않는다", async () => {
    const rec = recorder();
    categoryServer(rec);
    renderAdmin("categories");
    await screen.findAllByTestId("admin-category-row");
    await userEvent.type(screen.getByTestId("admin-category-slug"), "Bad Slug");
    await userEvent.click(screen.getByTestId("admin-category-submit"));
    expect(await screen.findByTestId("admin-category-slug-error")).toHaveTextContent("소문자·숫자·하이픈 2~30자");
    expect(screen.getByTestId("admin-category-name-error")).toHaveTextContent("이름");
    expect(rec.calls.filter((c) => c.method !== "GET")).toHaveLength(0);
  });

  it("TASK-15#1 validateCategoryForm 은 서버 규칙(slug ^[a-z0-9-]{2,30}$, 이름 1~50, 정수 순서)을 따른다", () => {
    const ok = { slug: "free-talk", name: "자유", sortOrder: "", adminOnly: false };
    expect(validateCategoryForm(ok)).toEqual({});
    expect(validateCategoryForm({ ...ok, slug: "a" }).slug).toBeDefined();
    expect(validateCategoryForm({ ...ok, slug: "a".repeat(31) }).slug).toBeDefined();
    expect(validateCategoryForm({ ...ok, slug: "a".repeat(30) }).slug).toBeUndefined();
    expect(validateCategoryForm({ ...ok, slug: "ABC" }).slug).toBeDefined();
    expect(validateCategoryForm({ ...ok, slug: "a_b" }).slug).toBeDefined();
    expect(validateCategoryForm({ ...ok, name: "   " }).name).toBeDefined();
    expect(validateCategoryForm({ ...ok, name: "가".repeat(51) }).name).toBeDefined();
    expect(validateCategoryForm({ ...ok, name: "가".repeat(50) }).name).toBeUndefined();
    expect(validateCategoryForm({ ...ok, sortOrder: "1.5" }).sortOrder).toBeDefined();
    expect(validateCategoryForm({ ...ok, sortOrder: "-3" }).sortOrder).toBeUndefined();
    expect(validateCategoryForm({ ...ok, sortOrder: "99999999999" }).sortOrder).toBeDefined();
  });

  it("TASK-15#1 409 slug_taken 은 한국어 안내", async () => {
    const rec = recorder();
    categoryServer(rec);
    server.use(http.post("*/api/admin/categories", () => apiError(409, "slug_taken")));
    renderAdmin("categories");
    await screen.findAllByTestId("admin-category-row");
    await userEvent.type(screen.getByTestId("admin-category-slug"), "free");
    await userEvent.type(screen.getByTestId("admin-category-name"), "자유");
    await userEvent.click(screen.getByTestId("admin-category-submit"));
    expect(await screen.findByTestId("admin-category-error")).toHaveTextContent(ERROR_MESSAGES.slug_taken);
  });

  it("TASK-15#1 수정: 바뀐 필드만 PATCH /api/admin/categories/{id} 로 보내고 카테고리를 다시 받는다", async () => {
    const rec = recorder();
    categoryServer(rec);
    server.use(
      http.patch("*/api/admin/categories/:id", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json({ ...CATEGORIES[1], name: "자유", adminOnly: true });
      }),
    );
    renderAdmin("categories");
    const rows = await screen.findAllByTestId("admin-category-row");
    await userEvent.click(within(rows[1]).getByTestId("admin-category-edit"));
    const name = screen.getByTestId("admin-category-name");
    expect(name).toHaveValue("자유게시판");
    expect(screen.getByTestId("admin-category-slug")).toHaveValue("free");
    await userEvent.clear(name);
    await userEvent.type(name, "자유");
    await userEvent.click(screen.getByTestId("admin-category-admin-only"));
    await userEvent.click(screen.getByTestId("admin-category-submit"));

    await waitFor(() => expect(rec.of("PATCH", "/api/admin/categories/2")).toHaveLength(1));
    const call = rec.of("PATCH", "/api/admin/categories/2")[0];
    expect(call.body).toEqual({ name: "자유", adminOnly: true });
    expect(call.csrf).toBe("csrf-token");
    await waitFor(() => expect(rec.of("GET", "/api/categories").length).toBeGreaterThanOrEqual(2));
  });

  it("TASK-15#1 삭제: 확인 다이얼로그 뒤 DELETE /api/admin/categories/{id}, 카테고리와 글 목록 쿼리를 무효화", async () => {
    const rec = recorder();
    categoryServer(rec);
    server.use(
      http.delete("*/api/admin/categories/:id", async ({ request }) => {
        await rec.record(request);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { client } = renderAdmin("categories");
    const rows = await screen.findAllByTestId("admin-category-row");
    const invalidate = vi.spyOn(client, "invalidateQueries");
    await userEvent.click(within(rows[1]).getByTestId("admin-category-delete"));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("자유게시판");
    expect(within(dialog).queryByTestId("admin-reason-input")).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));

    await waitFor(() => expect(rec.of("DELETE", "/api/admin/categories/2")).toHaveLength(1));
    expect(rec.of("DELETE", "/api/admin/categories/2")[0].csrf).toBe("csrf-token");
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.categories }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.posts });
  });

  it("TASK-15#1 404 category_not_found 는 한국어 안내", async () => {
    const rec = recorder();
    categoryServer(rec);
    server.use(http.delete("*/api/admin/categories/:id", () => apiError(404, "category_not_found")));
    renderAdmin("categories");
    const rows = await screen.findAllByTestId("admin-category-row");
    await userEvent.click(within(rows[0]).getByTestId("admin-category-delete"));
    const dialog = await screen.findByRole("alertdialog");
    await userEvent.click(within(dialog).getByTestId("admin-confirm-submit"));
    expect(await within(dialog).findByTestId("admin-confirm-error")).toHaveTextContent(
      ERROR_MESSAGES.category_not_found,
    );
  });
});

describe("TASK-15#1 감사 로그 탭", () => {
  beforeEach(() => navigation.reset("/admin/"));

  it("TASK-15#1 GET /api/admin/actions 를 페이지로 보여 준다", async () => {
    const rec = recorder();
    server.use(
      meHandler(ADMIN),
      http.get("*/api/admin/actions", async ({ request }) => {
        await rec.record(request);
        return HttpResponse.json(
          pageOf([makeAction(), makeAction({ id: 2, action: "CATEGORY_DELETE", targetType: "CATEGORY", targetId: 4, reason: "slug=qna" })], {
            totalPages: 2,
            totalElements: 25,
          }),
        );
      }),
    );
    renderAdmin("actions");
    const rows = await screen.findAllByTestId("admin-action-row");
    expect(rec.calls[0].query).toEqual({ page: "0", size: "20" });
    expect(rows[0]).toHaveTextContent("관리자");
    expect(rows[0]).toHaveTextContent("사용자 정지");
    expect(rows[0]).toHaveTextContent("스팸");
    expect(rows[1]).toHaveTextContent("카테고리 삭제");
    expect(rows[1]).toHaveTextContent("slug=qna");

    await userEvent.click(screen.getByTestId("admin-actions-next"));
    await waitFor(() => expect(rec.calls.at(-1)!.query).toEqual({ page: "1", size: "20" }));
  });

  it("TASK-15#1 관리 쓰기 뒤 감사 로그 쿼리도 무효화한다", async () => {
    server.use(
      meHandler(ADMIN),
      csrfHandler(),
      http.get("*/api/admin/users", () => HttpResponse.json(pageOf([makeUser()]))),
      http.post("*/api/admin/users/:id/suspend", () => HttpResponse.json(makeUser({ status: "SUSPENDED" }))),
    );
    const { client } = renderAdmin("users");
    await userEvent.click(await screen.findByTestId("admin-user-suspend"));
    const invalidate = vi.spyOn(client, "invalidateQueries");
    await userEvent.click(within(await screen.findByRole("alertdialog")).getByTestId("admin-confirm-submit"));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.adminActions }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.adminUsers });
  });
});
