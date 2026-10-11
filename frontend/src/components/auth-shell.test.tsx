import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { RequireAdmin, RequireAuth } from "@/components/guards";
import { SiteHeader } from "@/components/site-header";
import { StepUpBanner } from "@/components/step-up-banner";
import { csrfHandler, server } from "@/test/server";
import { anonymous, makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

// TASK-13#2 레이아웃·헤더·useMe 가드.
describe("TASK-13#2 RequireAuth / RequireAdmin 가드", () => {
  beforeEach(() => {
    navigation.reset("/account/");
  });

  it("TASK-13#2 익명이면 /login/?next=<현재 경로> 로 replace 하고 내용은 그리지 않는다", async () => {
    server.use(anonymous());
    renderWithQuery(
      <RequireAuth>
        <p>비밀 내용</p>
      </RequireAuth>,
    );

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login/?next=%2Faccount%2F"));
    expect(screen.queryByText("비밀 내용")).not.toBeInTheDocument();
  });

  it("TASK-13#2 로그인 상태면 내용을 그린다", async () => {
    server.use(meHandler(makeMe()));
    renderWithQuery(
      <RequireAuth>
        <p>비밀 내용</p>
      </RequireAuth>,
    );

    expect(await screen.findByText("비밀 내용")).toBeInTheDocument();
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("TASK-13#2 RequireAdmin: USER 는 권한 없음 안내, ADMIN 은 내용", async () => {
    server.use(meHandler(makeMe({ role: "USER" })));
    const { unmount } = renderWithQuery(
      <RequireAdmin>
        <p>관리 화면</p>
      </RequireAdmin>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("관리자만");
    expect(screen.queryByText("관리 화면")).not.toBeInTheDocument();
    unmount();

    server.use(meHandler(makeMe({ role: "ADMIN" })));
    renderWithQuery(
      <RequireAdmin>
        <p>관리 화면</p>
      </RequireAdmin>,
    );
    expect(await screen.findByText("관리 화면")).toBeInTheDocument();
  });

  it("TASK-13#2 RequireAdmin: 익명이면 로그인으로 보낸다", async () => {
    navigation.reset("/admin/");
    server.use(anonymous());
    renderWithQuery(
      <RequireAdmin>
        <p>관리 화면</p>
      </RequireAdmin>,
    );
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login/?next=%2Fadmin%2F"));
  });
});

describe("TASK-13#2 헤더: 로그인 상태·닉네임·관리자 링크·로그아웃", () => {
  beforeEach(() => {
    navigation.reset("/");
  });

  it("TASK-13#2 익명이면 로그인·가입 링크", async () => {
    server.use(anonymous());
    renderWithQuery(<SiteHeader />);

    expect(await screen.findByRole("link", { name: "로그인" })).toHaveAttribute("href", "/login/");
    expect(screen.getByRole("link", { name: "가입" })).toHaveAttribute("href", "/signup/");
    expect(screen.queryByRole("button", { name: "로그아웃" })).not.toBeInTheDocument();
  });

  it("TASK-13#2 ADMIN 은 닉네임·내 계정·관리자 링크, USER 는 관리자 링크가 없다", async () => {
    server.use(meHandler(makeMe({ nickname: "운영자", role: "ADMIN" })));
    const { unmount } = renderWithQuery(<SiteHeader />);
    expect(await screen.findByText("운영자")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "내 계정" })).toHaveAttribute("href", "/account/");
    expect(screen.getByRole("link", { name: "관리자" })).toHaveAttribute("href", "/admin/");
    unmount();

    server.use(meHandler(makeMe({ nickname: "일반", role: "USER" })));
    renderWithQuery(<SiteHeader />);
    expect(await screen.findByText("일반")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "관리자" })).not.toBeInTheDocument();
  });

  it("TASK-13#2 로그아웃은 CSRF 헤더와 함께 POST 하고 익명 헤더로 바뀐다", async () => {
    let loggedIn = true;
    let header: string | null = null;
    server.use(
      csrfHandler("tok"),
      http.get("*/api/auth/me", () =>
        loggedIn
          ? HttpResponse.json(makeMe({ nickname: "철수" }))
          : HttpResponse.json({ status: 401, error: "unauthorized", message: "인증이 필요합니다" }, { status: 401 }),
      ),
      http.post("*/api/auth/logout", ({ request }) => {
        header = request.headers.get("X-XSRF-TOKEN");
        loggedIn = false;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithQuery(<SiteHeader />);

    await userEvent.click(await screen.findByRole("button", { name: "로그아웃" }));
    expect(await screen.findByRole("link", { name: "로그인" })).toBeInTheDocument();
    expect(header).toBe("tok");
    expect(navigation.push).toHaveBeenCalledWith("/");
  });
});

describe("TASK-13#2 step-up 배너", () => {
  it("TASK-13#2 me.stepUpRequired 면 다시 인증하라는 배너와 내 계정 링크를 보인다", async () => {
    server.use(meHandler(makeMe({ stepUpRequired: true, stepUpProviders: ["LOCAL", "GOOGLE"] })));
    renderWithQuery(<StepUpBanner />);

    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("다시 인증");
    expect(screen.getByRole("link", { name: /다시 인증하기/ })).toHaveAttribute("href", "/account/#step-up");
  });

  it("TASK-13#2 제한이 없거나 익명이면 배너가 없다", async () => {
    server.use(meHandler(makeMe()));
    const { unmount } = renderWithQuery(<StepUpBanner />);
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    unmount();
    server.use(anonymous());
    renderWithQuery(<StepUpBanner />);
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });
});
