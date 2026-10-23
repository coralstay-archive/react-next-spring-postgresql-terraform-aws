import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { RequireAuth } from "@/components/guards";
import { SiteHeader } from "@/components/site-header";
import { queryKeys } from "@/lib/query";
import { apiError, server } from "@/test/server";
import { makeMe, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** 처음에는 me 가 실패하고(`fail`), `recover()` 뒤에는 사용자를 준다. */
function flakyMe(fail: () => Response) {
  const state = { ok: false, calls: 0 };
  return {
    state,
    handler: http.get("*/api/auth/me", () => {
      state.calls++;
      return state.ok ? HttpResponse.json(makeMe({ nickname: "철수" })) : fail();
    }),
  };
}

describe("TASK-29#6 me 로딩 실패: 헤더 오류 상태와 다시 시도", () => {
  beforeEach(() => {
    navigation.reset("/");
  });

  it("TASK-29#6 me 가 5xx 면 로그아웃 상태(로그인·가입 링크)가 아니라 오류와 다시 시도 버튼을 보인다", async () => {
    const { state, handler } = flakyMe(() => apiError(503, "db_unavailable", "장애"));
    server.use(handler);
    renderWithQuery(<SiteHeader />);

    expect(await screen.findByTestId("me-error")).toHaveTextContent("사용자 정보를 불러오지 못했습니다");
    expect(screen.queryByRole("link", { name: "로그인" })).not.toBeInTheDocument();

    state.ok = true;
    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("철수")).toBeInTheDocument();
    expect(screen.queryByTestId("me-error")).not.toBeInTheDocument();
  });

  it("TASK-29#6 네트워크 오류도 같은 오류 상태다", async () => {
    server.use(flakyMe(() => HttpResponse.error()).handler);
    renderWithQuery(<SiteHeader />);
    expect(await screen.findByTestId("me-error")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
  });

  it("TASK-29#6 이미 사용자를 아는 상태에서 재조회만 실패하면 사용자 표시를 유지한다", async () => {
    const { state, handler } = flakyMe(() => apiError(500, "internal_error", "오류"));
    state.ok = true;
    server.use(handler);
    const { client } = renderWithQuery(<SiteHeader />);
    expect(await screen.findByText("철수")).toBeInTheDocument();

    state.ok = false;
    await act(() => client.refetchQueries({ queryKey: queryKeys.me }));
    expect(screen.getByText("철수")).toBeInTheDocument();
    expect(screen.queryByTestId("me-error")).not.toBeInTheDocument();
  });

  it("TASK-29#6 RequireAuth 의 불러오기 오류에도 다시 시도가 있다", async () => {
    const { state, handler } = flakyMe(() => apiError(502, "http_502", "bad gateway"));
    server.use(handler);
    renderWithQuery(
      <RequireAuth>
        <p>비밀 내용</p>
      </RequireAuth>,
    );
    expect(await screen.findByText("사용자 정보를 불러오지 못했습니다")).toBeInTheDocument();

    state.ok = true;
    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("비밀 내용")).toBeInTheDocument();
  });
});
