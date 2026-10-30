import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthCallbackView } from "@/app/auth/callback/callback-view";
import { NEXT_STORAGE_KEY } from "@/lib/errors";
import { apiError, server } from "@/test/server";
import { anonymous, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

describe("TASK-29#7 OAuth 콜백 실패: 저장된 next 정리, 돌아가기 링크에 next 유지", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it("TASK-29#7 ?error= 면 sessionStorage 의 next 를 지우고 돌아가기 링크에 next 를 싣는다", async () => {
    navigation.reset("/auth/callback/", "error=access_denied");
    window.sessionStorage.setItem(NEXT_STORAGE_KEY, "/posts/view/?id=3");
    renderWithQuery(<AuthCallbackView />);

    const link = await screen.findByRole("link", { name: "로그인으로 돌아가기" });
    await waitFor(() =>
      expect(link).toHaveAttribute("href", `/login/?next=${encodeURIComponent("/posts/view/?id=3")}`),
    );
    expect(window.sessionStorage.getItem(NEXT_STORAGE_KEY)).toBeNull();
  });

  it("TASK-29#7 돌아가기 링크의 next 도 safeNext 를 거친다(외부 주소면 next 없이)", async () => {
    navigation.reset("/auth/callback/", "error=oauth2_failed");
    window.sessionStorage.setItem(NEXT_STORAGE_KEY, "/%2F/evil.com");
    renderWithQuery(<AuthCallbackView />);

    const link = await screen.findByRole("link", { name: "로그인으로 돌아가기" });
    await waitFor(() => expect(window.sessionStorage.getItem(NEXT_STORAGE_KEY)).toBeNull());
    expect(link).toHaveAttribute("href", "/login/");
  });

  it("TASK-29#7 status=ok 인데 me 확인에 실패해도 next 를 지우고 링크에 싣는다", async () => {
    navigation.reset("/auth/callback/", "status=ok");
    window.sessionStorage.setItem(NEXT_STORAGE_KEY, "/admin/?tab=users");
    server.use(anonymous());
    renderWithQuery(<AuthCallbackView />);

    const link = await screen.findByRole("link", { name: "로그인으로 돌아가기" });
    expect(link).toHaveAttribute("href", `/login/?next=${encodeURIComponent("/admin/?tab=users")}`);
    expect(window.sessionStorage.getItem(NEXT_STORAGE_KEY)).toBeNull();
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("TASK-29#7 status=ok 인데 me 가 5xx 여도 같다", async () => {
    navigation.reset("/auth/callback/", "status=ok");
    window.sessionStorage.setItem(NEXT_STORAGE_KEY, "/account/");
    server.use(
      (await import("msw")).http.get("*/api/auth/me", () => apiError(503, "db_unavailable", "장애")),
    );
    renderWithQuery(<AuthCallbackView />);

    const link = await screen.findByRole("link", { name: "로그인으로 돌아가기" });
    expect(link).toHaveAttribute("href", `/login/?next=${encodeURIComponent("/account/")}`);
    expect(window.sessionStorage.getItem(NEXT_STORAGE_KEY)).toBeNull();
  });
});
