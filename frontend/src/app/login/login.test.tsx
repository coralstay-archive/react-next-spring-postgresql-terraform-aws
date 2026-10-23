import { act, renderHook, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthCallbackView } from "@/app/auth/callback/callback-view";
import { LoginView } from "@/app/login/login-view";
import { PhoneOtpForm } from "@/components/auth/phone-otp-form";
import { formatCountdown, useCountdown } from "@/hooks/use-countdown";
import { ApiError } from "@/lib/api";
import { errorMessage, NEXT_STORAGE_KEY } from "@/lib/errors";
import { queryKeys } from "@/lib/query";
import { apiError, csrfHandler, server } from "@/test/server";
import { anonymous, makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

// TASK-13#3 로그인 화면: LOCAL, 소셜 5종 버튼, 휴대폰 OTP (+ /auth/callback/, 한국어 오류 메시지).
describe("TASK-13#3 오류 코드 → 한국어 메시지", () => {
  it("TASK-13#3 알려진 코드는 매핑표의 한국어 문장", () => {
    expect(errorMessage("invalid_credentials")).toBe("이메일 또는 비밀번호가 올바르지 않습니다.");
    expect(errorMessage("access_denied")).toContain("취소");
    expect(errorMessage("identity_in_use")).toContain("다른 계정");
    expect(errorMessage(new ApiError(401, "invalid_otp", "server"))).toContain("인증번호");
  });

  it("TASK-13#3 429 는 남은 초를, 모르는 코드는 서버 message 를, 그것도 없으면 일반 문장을 쓴다", () => {
    expect(errorMessage(new ApiError(429, "rate_limited", "x", { retryAfter: 30 }))).toContain("30초");
    expect(errorMessage(new ApiError(400, "weird_code", "서버가 준 문장"))).toBe("서버가 준 문장");
    expect(errorMessage("totally_unknown")).toBe("요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.");
    expect(errorMessage(new Error("boom"))).toBe("요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.");
  });
});

describe("TASK-13#3 useCountdown (가짜 타이머)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("TASK-13#3 시작하면 1초마다 줄고 0에서 멈춘다, m:ss 로 표시한다", () => {
    const { result } = renderHook(() => useCountdown());
    expect(result.current.remaining).toBe(0);
    act(() => result.current.start(180));
    expect(formatCountdown(result.current.remaining)).toBe("3:00");
    act(() => vi.advanceTimersByTime(1000));
    expect(formatCountdown(result.current.remaining)).toBe("2:59");
    act(() => vi.advanceTimersByTime(119_000));
    expect(formatCountdown(result.current.remaining)).toBe("1:00");
    act(() => vi.advanceTimersByTime(70_000));
    expect(result.current.remaining).toBe(0);
    expect(formatCountdown(5)).toBe("0:05");
  });
});

describe("TASK-13#3 로그인 화면 /login/", () => {
  beforeEach(() => {
    navigation.reset("/login/", "next=/account/");
    window.sessionStorage.clear();
  });

  it("TASK-13#3 이메일·비밀번호 로그인: CSRF 를 받아 POST 하고 me 를 채운 뒤 next 로 이동한다", async () => {
    let body: unknown;
    let header: string | null = null;
    server.use(
      anonymous(),
      csrfHandler("t1"),
      http.post("*/api/auth/login", async ({ request }) => {
        body = await request.json();
        header = request.headers.get("X-XSRF-TOKEN");
        return HttpResponse.json(makeMe({ nickname: "로그인됨" }));
      }),
    );
    const { client } = renderWithQuery(<LoginView />);

    await userEvent.type(screen.getByLabelText("이메일"), "a@example.com");
    await userEvent.type(screen.getByLabelText("비밀번호"), "passw0rd!");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/account/"));
    expect(body).toEqual({ email: "a@example.com", password: "passw0rd!" });
    expect(header).toBe("t1");
    expect(client.getQueryData(queryKeys.me)).toMatchObject({ nickname: "로그인됨" });
  });

  it("TASK-13#3 로그인 실패(401 invalid_credentials)는 한국어 메시지를 보인다", async () => {
    server.use(
      anonymous(),
      csrfHandler(),
      http.post("*/api/auth/login", () => apiError(401, "invalid_credentials")),
    );
    renderWithQuery(<LoginView />);

    await userEvent.type(screen.getByLabelText("이메일"), "a@example.com");
    await userEvent.type(screen.getByLabelText("비밀번호"), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("이메일 또는 비밀번호가 올바르지 않습니다.");
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("TASK-13#3 소셜 5종은 /api/oauth2/authorization/{p} 로 페이지 이동하는 링크이고 next 를 기억한다", async () => {
    server.use(anonymous());
    renderWithQuery(<LoginView />);

    const expected: [string, string][] = [
      ["Google로 로그인", "google"],
      ["GitHub로 로그인", "github"],
      ["네이버로 로그인", "naver"],
      ["카카오로 로그인", "kakao"],
      ["Cognito로 로그인", "cognito"],
    ];
    for (const [name, id] of expected) {
      expect(screen.getByRole("link", { name })).toHaveAttribute("href", `/api/oauth2/authorization/${id}`);
    }
    screen.getByRole("link", { name: "GitHub로 로그인" }).addEventListener("click", (e) => e.preventDefault());
    await userEvent.click(screen.getByRole("link", { name: "GitHub로 로그인" }));
    expect(window.sessionStorage.getItem(NEXT_STORAGE_KEY)).toBe("/account/");
  });

  it("TASK-13#3 ?error= 로 들어오면 한국어 오류를 보인다", async () => {
    navigation.reset("/login/", "error=suspended");
    server.use(anonymous());
    renderWithQuery(<LoginView />);
    expect(await screen.findByRole("alert")).toHaveTextContent("정지");
  });

  it("TASK-13#3 휴대폰 탭: 번호 → 인증번호 → 로그인", async () => {
    let verifyBody: unknown;
    server.use(
      anonymous(),
      csrfHandler(),
      http.post("*/api/auth/phone/otp", () =>
        HttpResponse.json({ challengeId: "ch-1", expiresIn: 180 }, { status: 202 }),
      ),
      http.post("*/api/auth/phone/verify", async ({ request }) => {
        verifyBody = await request.json();
        return HttpResponse.json(makeMe({ providers: ["PHONE"] }));
      }),
    );
    renderWithQuery(<LoginView />);

    await userEvent.click(screen.getByRole("tab", { name: "휴대폰" }));
    await userEvent.type(screen.getByLabelText("휴대폰 번호"), "010-1234-5678");
    await userEvent.click(screen.getByRole("button", { name: "인증번호 받기" }));
    await userEvent.type(await screen.findByLabelText("인증번호"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "확인" }));

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/account/"));
    expect(verifyBody).toEqual({ challengeId: "ch-1", code: "123456" });
  });
});

describe("TASK-13#3 휴대폰 OTP 카운트다운 (가짜 타이머)", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("TASK-13#3 3분 카운트다운이 끝나면 만료 안내를 보이고 확인을 막는다", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    server.use(
      csrfHandler(),
      http.post("*/api/auth/phone/otp", () =>
        HttpResponse.json({ challengeId: "ch-1", expiresIn: 180 }, { status: 202 }),
      ),
    );
    renderWithQuery(<PhoneOtpForm onVerified={vi.fn()} />);

    await user.type(screen.getByLabelText("휴대폰 번호"), "01012345678");
    await user.click(screen.getByRole("button", { name: "인증번호 받기" }));
    const timer = await screen.findByRole("timer");
    expect(timer).toHaveTextContent("3:00");

    act(() => vi.advanceTimersByTime(60_000));
    expect(screen.getByRole("timer")).toHaveTextContent("2:00");

    act(() => vi.advanceTimersByTime(120_000));
    expect(await screen.findByText(/인증번호가 만료되었습니다/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "확인" })).toBeDisabled();
  });

  it("TASK-13#3 429 면 Retry-After 초만큼 다시 받기를 막고 남은 시간을 보인다", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    server.use(
      csrfHandler(),
      http.post("*/api/auth/phone/otp", () =>
        HttpResponse.json(
          { status: 429, error: "rate_limited", message: "잠시 후" },
          { status: 429, headers: { "Retry-After": "45" } },
        ),
      ),
    );
    renderWithQuery(<PhoneOtpForm onVerified={vi.fn()} />);

    await user.type(screen.getByLabelText("휴대폰 번호"), "01012345678");
    await user.click(screen.getByRole("button", { name: "인증번호 받기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("요청이 너무 많습니다");
    const send = screen.getByRole("button", { name: /다시 시도/ });
    expect(send).toBeDisabled();
    expect(send).toHaveTextContent("45초 후 다시 시도");

    act(() => vi.advanceTimersByTime(15_000));
    expect(screen.getByRole("button", { name: /다시 시도/ })).toHaveTextContent("30초 후 다시 시도");

    act(() => vi.advanceTimersByTime(30_000));
    expect(await screen.findByRole("button", { name: "인증번호 받기" })).toBeEnabled();
  });
});

describe("TASK-13#3 /auth/callback/", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it("TASK-13#3 status=ok 면 me 를 다시 받아 기억한 next 로 이동한다", async () => {
    navigation.reset("/auth/callback/", "status=ok");
    window.sessionStorage.setItem(NEXT_STORAGE_KEY, "/posts/");
    let meCalls = 0;
    server.use(
      http.get("*/api/auth/me", () => {
        meCalls += 1;
        return HttpResponse.json(makeMe({ nickname: "소셜" }));
      }),
    );
    const { client } = renderWithQuery(<AuthCallbackView />);
    client.setQueryData(queryKeys.me, null); // 콜백 전의 익명 캐시

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/posts/"));
    expect(meCalls).toBeGreaterThan(0);
    expect(client.getQueryData(queryKeys.me)).toMatchObject({ nickname: "소셜" });
    expect(window.sessionStorage.getItem(NEXT_STORAGE_KEY)).toBeNull();
  });

  it("TASK-13#3 error=<코드> 면 한국어 오류와 로그인으로 돌아가는 링크", async () => {
    navigation.reset("/auth/callback/", "error=access_denied");
    renderWithQuery(<AuthCallbackView />);

    expect(await screen.findByRole("alert")).toHaveTextContent("취소");
    expect(screen.getByRole("link", { name: "로그인으로 돌아가기" })).toHaveAttribute("href", "/login/");
    expect(navigation.replace).not.toHaveBeenCalled();
  });
});

// TASK-28#1 FE-1: next 를 쓰는 모든 곳(이메일·휴대폰 로그인, 소셜 시작 시 기억, OAuth 콜백)이 같은 오리진만 허용한다.
describe("TASK-28#1 next 소비처의 오픈 리다이렉트 차단", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it("TASK-28#1 TASK-28#B1 ?next= 에 탭을 넣은 외부 주소는 로그인 뒤 / 로 간다", async () => {
    navigation.reset("/login/", `next=${encodeURIComponent("/\t/evil.com")}`);
    server.use(
      anonymous(),
      csrfHandler(),
      http.post("*/api/auth/login", () => HttpResponse.json(makeMe())),
    );
    renderWithQuery(<LoginView />);

    await userEvent.type(screen.getByLabelText("이메일"), "a@example.com");
    await userEvent.type(screen.getByLabelText("비밀번호"), "passw0rd!");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/"));
  });

  it("TASK-28#1 TASK-28#B1 소셜 로그인 전에 기억하는 next 도 검사한 값이다", async () => {
    navigation.reset("/login/", `next=${encodeURIComponent("/\\evil.com")}`);
    server.use(anonymous());
    renderWithQuery(<LoginView />);

    const link = screen.getByRole("link", { name: "GitHub로 로그인" });
    link.addEventListener("click", (e) => e.preventDefault());
    await userEvent.click(link);
    expect(window.sessionStorage.getItem(NEXT_STORAGE_KEY)).toBe("/");
  });

  it("TASK-28#1 TASK-28#B1 OAuth 콜백은 sessionStorage 의 next 를 다시 검사한다", async () => {
    navigation.reset("/auth/callback/", "status=ok");
    window.sessionStorage.setItem(NEXT_STORAGE_KEY, "/%2F/evil.com");
    server.use(meHandler(makeMe()));
    renderWithQuery(<AuthCallbackView />);

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/"));
  });
});
