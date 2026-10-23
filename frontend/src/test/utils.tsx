import { QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactElement } from "react";
import { vi } from "vitest";

import type { Me } from "@/lib/auth";
import { createQueryClient } from "@/lib/query";

export function makeMe(overrides: Partial<Me> = {}): Me {
  return {
    id: 1,
    nickname: "테스터",
    email: "tester@example.com",
    role: "USER",
    status: "ACTIVE",
    providers: ["LOCAL"],
    stepUpRequired: false,
    stepUpProviders: [],
    ...overrides,
  };
}

export function meHandler(me: Me) {
  return http.get("*/api/auth/me", () => HttpResponse.json(me));
}

export function anonymous() {
  return http.get("*/api/auth/me", () =>
    HttpResponse.json({ status: 401, error: "unauthorized", message: "인증이 필요합니다" }, { status: 401 }),
  );
}

export function renderWithQuery(ui: ReactElement) {
  const client = createQueryClient();
  // setDefaultOptions 는 통째로 바꾸므로 앱 기본값(뮤테이션 networkMode 등)을 합친 뒤 재시도·staleTime 만 덮는다.
  const defaults = client.getDefaultOptions();
  client.setDefaultOptions({
    queries: { ...defaults.queries, retry: false, staleTime: Infinity },
    mutations: { ...defaults.mutations, retry: false },
  });
  return { client, ...render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>) };
}

/** next/navigation 대역. 테스트에서 `vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock())`. */
export const navigation = {
  pathname: "/",
  search: new URLSearchParams(),
  push: vi.fn(),
  replace: vi.fn(),
  reset(pathname = "/", search = "") {
    this.pathname = pathname;
    this.search = new URLSearchParams(search);
    this.push.mockReset();
    this.replace.mockReset();
  },
};

export function nextNavigationMock() {
  return {
    useRouter: () => ({
      push: navigation.push,
      replace: navigation.replace,
      back: vi.fn(),
      forward: vi.fn(),
      refresh: vi.fn(),
      prefetch: vi.fn(),
    }),
    usePathname: () => navigation.pathname,
    useSearchParams: () => navigation.search,
  };
}
