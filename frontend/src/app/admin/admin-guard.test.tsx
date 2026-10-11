import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import AdminPage from "@/app/admin/page";
import { pageOf } from "@/test/board";
import { server } from "@/test/server";
import { anonymous, makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** 관리 API 를 부르면 기록한다(USER·익명에게는 부르지 않아야 한다). */
function adminApiSpy() {
  const calls: string[] = [];
  server.use(
    http.get("*/api/admin/*", ({ request }) => {
      calls.push(new URL(request.url).pathname);
      return HttpResponse.json(pageOf([]));
    }),
  );
  return calls;
}

// TASK-15#2 ADMIN 이 아니면 접근 차단 화면(실제 차단은 서버 /api/admin/** 가 한다).
describe("TASK-15#2 /admin/ 가드", () => {
  beforeEach(() => navigation.reset("/admin/", "tab=posts"));

  it("TASK-15#2 익명은 현재 경로(쿼리 포함)를 next 로 로그인 화면에 보낸다", async () => {
    const calls = adminApiSpy();
    server.use(anonymous());
    renderWithQuery(<AdminPage />);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login/?next=%2Fadmin%2F%3Ftab%3Dposts"));
    expect(screen.queryByTestId("admin-view")).not.toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it("TASK-15#2 USER 는 403 권한 없음 안내만 보고 관리 API 를 부르지 않는다", async () => {
    const calls = adminApiSpy();
    server.use(meHandler(makeMe({ role: "USER" })));
    renderWithQuery(<AdminPage />);
    const notice = await screen.findByTestId("admin-forbidden");
    expect(notice).toHaveTextContent("권한이 없습니다");
    expect(notice).toHaveTextContent("403");
    expect(screen.queryByTestId("admin-view")).not.toBeInTheDocument();
    expect(navigation.replace).not.toHaveBeenCalled();
    expect(calls).toEqual([]);
  });

  it("TASK-15#2 ADMIN 은 관리자 화면을 본다", async () => {
    navigation.reset("/admin/", "tab=actions");
    const calls = adminApiSpy();
    server.use(meHandler(makeMe({ role: "ADMIN" })));
    renderWithQuery(<AdminPage />);
    expect(await screen.findByTestId("admin-view")).toBeInTheDocument();
    expect(screen.queryByTestId("admin-forbidden")).not.toBeInTheDocument();
    await waitFor(() => expect(calls).toEqual(["/api/admin/actions"]));
  });
});
