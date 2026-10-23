import { act, renderHook, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthCallbackView } from "@/app/auth/callback/callback-view";
import { LoginView } from "@/app/login/login-view";
import { PhoneOtpForm } from "@/components/auth/phone-otp-form";
import { formatCountdown, useCountdown } from "@/hooks/use-countdown";
import { ApiError } from "@/lib/api";
import {
  ERROR_MESSAGES,
  errorMessage,
  NEXT_STORAGE_KEY,
  OAUTH_LINK_FALLBACK,
  OAUTH_LOGIN_FALLBACK,
  oauthErrorMessage,
} from "@/lib/errors";
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

// TASK-28#4 FE-12: 콜백 ?error= 의 oauth2_failed·표준 OAuth 오류 코드는 한국어로, 모르는 코드는 콜백 전용 fallback.
describe("TASK-28#4 OAuth 콜백 오류 코드", () => {
  it.each([
    ["oauth2_failed", "소셜 로그인"],
    ["server_error", "다시 시도"],
    ["temporarily_unavailable", "일시적"],
    ["invalid_request", "요청"],
    ["unauthorized_client", "설정"],
    ["unsupported_response_type", "설정"],
    ["invalid_scope", "권한"],
    ["invalid_client", "설정"],
    ["unsupported_grant_type", "설정"],
    ["invalid_state_parameter", "만료"],
    ["client_registration_not_found", "지원하지 않"],
    ["login_required", "로그인"],
    ["consent_required", "동의"],
    ["interaction_required", "로그인"],
  ])("TASK-28#4 %s 는 전용 한국어 문장", (code, text) => {
    const message = oauthErrorMessage(code);
    expect(ERROR_MESSAGES[code]).toBe(message);
    expect(message).toContain(text);
    expect(message).not.toContain(code);
  });

  it.each(["made_up_code", "Access-Denied", "<b>x</b>", "", "constructor", "__proto__"])(
    "TASK-28#4 모르거나 형식이 틀린 코드 %j 는 콜백 전용 fallback",
    (code) => {
      expect(oauthErrorMessage(code)).toBe(OAUTH_LOGIN_FALLBACK);
      expect(oauthErrorMessage(code, "link")).toBe(OAUTH_LINK_FALLBACK);
    },
  );

  it("TASK-28#4 콜백 화면: oauth2_failed 는 전용 문장, 모르는 코드는 콜백 fallback 을 보인다", async () => {
    navigation.reset("/auth/callback/", "error=oauth2_failed");
    const { unmount } = renderWithQuery(<AuthCallbackView />);
    expect(await screen.findByRole("alert")).toHaveTextContent(ERROR_MESSAGES.oauth2_failed);
    unmount();

    navigation.reset("/auth/callback/", "error=brand_new_provider_error");
    renderWithQuery(<AuthCallbackView />);
    expect(await screen.findByRole("alert")).toHaveTextContent(OAUTH_LOGIN_FALLBACK);
    expect(screen.getByRole("alert")).not.toHaveTextContent("brand_new_provider_error");
  });
});

// TASK-32#2 FE-18: 서버는 틀린 시도 5번에 챌린지를 폐기하지만 응답은 계속 같은 401 invalid_otp 다. 폼이 지금 챌린지의 실패를 세어
// 5번째면 인증번호 칸을 비우고 막은 뒤 다시 받기를 안내한다.
describe("TASK-32#2 OTP 5회 실패 뒤 다시 받기 안내", () => {
  function otpHandlers({ verify }: { verify: () => Response }) {
    const sent: string[] = [];
    const verified: unknown[] = [];
    let next = 1;
    return {
      sent,
      verified,
      handlers: [
        csrfHandler(),
        http.post("*/api/auth/phone/otp", () => {
          const challengeId = `ch-${next++}`;
          sent.push(challengeId);
          return HttpResponse.json({ challengeId, expiresIn: 180 }, { status: 202 });
        }),
        http.post("*/api/auth/phone/verify", async ({ request }) => {
          verified.push(await request.json());
          return verify();
        }),
      ],
    };
  }

  async function failTimes(times: number) {
    for (let i = 0; i < times; i++) {
      const input = screen.getByLabelText("인증번호");
      await userEvent.clear(input);
      await userEvent.type(input, "000000");
      await userEvent.click(screen.getByRole("button", { name: "확인" }));
      await screen.findByRole("alert");
    }
  }

  it("TASK-32#2 5번째 invalid_otp 면 인증번호 칸을 비우고 막으며 다시 받으라고 안내한다", async () => {
    const otp = otpHandlers({ verify: () => apiError(401, "invalid_otp", "인증번호가 올바르지 않습니다") });
    server.use(...otp.handlers);
    renderWithQuery(<PhoneOtpForm onVerified={vi.fn()} />);

    await userEvent.type(screen.getByLabelText("휴대폰 번호"), "01012345678");
    await userEvent.click(screen.getByRole("button", { name: "인증번호 받기" }));
    await screen.findByLabelText("인증번호");

    await failTimes(4);
    expect(screen.getByLabelText("인증번호")).toBeEnabled();
    expect(screen.queryByTestId("otp-locked")).not.toBeInTheDocument();

    await failTimes(1);
    const locked = await screen.findByTestId("otp-locked");
    expect(locked).toHaveTextContent("5번 틀려");
    expect(locked).toHaveTextContent("인증번호를 다시 받으세요");
    expect(screen.getByLabelText("인증번호")).toHaveValue("");
    expect(screen.getByLabelText("인증번호")).toBeDisabled();
    expect(screen.getByRole("button", { name: "확인" })).toBeDisabled();
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
    expect(otp.verified).toHaveLength(5);
  });

  it("TASK-32#2 잠긴 뒤 인증번호를 다시 받으면 새 챌린지로 다시 확인할 수 있다", async () => {
    let ok = false;
    const otp = otpHandlers({
      verify: () => (ok ? HttpResponse.json(makeMe({ providers: ["PHONE"] })) : apiError(401, "invalid_otp", "x")),
    });
    server.use(...otp.handlers);
    const onVerified = vi.fn();
    renderWithQuery(<PhoneOtpForm onVerified={onVerified} />);

    await userEvent.type(screen.getByLabelText("휴대폰 번호"), "01012345678");
    await userEvent.click(screen.getByRole("button", { name: "인증번호 받기" }));
    await screen.findByLabelText("인증번호");
    await failTimes(5);
    await screen.findByTestId("otp-locked");

    await userEvent.click(screen.getByRole("button", { name: "인증번호 다시 받기" }));
    await waitFor(() => expect(screen.queryByTestId("otp-locked")).not.toBeInTheDocument());
    expect(otp.sent).toEqual(["ch-1", "ch-2"]);
    const input = screen.getByLabelText("인증번호");
    expect(input).toBeEnabled();

    ok = true;
    await userEvent.type(input, "123456");
    await userEvent.click(screen.getByRole("button", { name: "확인" }));
    await waitFor(() => expect(onVerified).toHaveBeenCalled());
    expect(otp.verified.at(-1)).toEqual({ challengeId: "ch-2", code: "123456" });
  });

  it("TASK-32#2 잠긴 뒤 다시 받기가 429 면 남은 시간 동안 버튼을 막고 잠금 안내는 남긴다", async () => {
    const otp = otpHandlers({ verify: () => apiError(401, "invalid_otp", "x") });
    let limited = false;
    server.use(...otp.handlers);
    // 나중에 넣은 핸들러가 먼저 맞는다(발송을 429 로 덮는다).
    server.use(
      http.post("*/api/auth/phone/otp", () =>
        limited
          ? HttpResponse.json(
              { status: 429, error: "rate_limited", message: "잠시 후" },
              { status: 429, headers: { "Retry-After": "40" } },
            )
          : HttpResponse.json({ challengeId: "ch-1", expiresIn: 180 }, { status: 202 }),
      ),
    );
    renderWithQuery(<PhoneOtpForm onVerified={vi.fn()} />);

    await userEvent.type(screen.getByLabelText("휴대폰 번호"), "01012345678");
    await userEvent.click(screen.getByRole("button", { name: "인증번호 받기" }));
    await screen.findByLabelText("인증번호");
    await failTimes(5);
    await screen.findByTestId("otp-locked");

    limited = true;
    await userEvent.click(screen.getByRole("button", { name: "인증번호 다시 받기" }));
    const send = await screen.findByRole("button", { name: /초 후 다시 시도/ });
    expect(send).toBeDisabled();
    expect(screen.getByTestId("otp-locked")).toBeInTheDocument();
    expect(screen.getByLabelText("인증번호")).toBeDisabled();
  });

  it("TASK-32#2 인증번호 오류가 아닌 실패(503 등)는 세지 않는다", async () => {
    const otp = otpHandlers({ verify: () => apiError(503, "db_unavailable", "x") });
    server.use(...otp.handlers);
    renderWithQuery(<PhoneOtpForm onVerified={vi.fn()} />);

    await userEvent.type(screen.getByLabelText("휴대폰 번호"), "01012345678");
    await userEvent.click(screen.getByRole("button", { name: "인증번호 받기" }));
    await screen.findByLabelText("인증번호");
    await failTimes(5);

    expect(screen.queryByTestId("otp-locked")).not.toBeInTheDocument();
    expect(screen.getByLabelText("인증번호")).toBeEnabled();
    expect(screen.getByLabelText("인증번호")).toHaveValue("000000");
  });
});
