import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AccountView } from "@/app/account/account-view";
import type { Identity } from "@/lib/identities";
import { ERROR_MESSAGES, OAUTH_LINK_FALLBACK, OAUTH_LINK_MESSAGES } from "@/lib/errors";
import { queryKeys } from "@/lib/query";
import { apiError, csrfHandler, server } from "@/test/server";
import { makeMe, meHandler, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

const LOCAL: Identity = { id: 1, provider: "LOCAL", email: "me@example.com", linkedAt: "2026-10-01T00:00:00Z" };
const GOOGLE: Identity = { id: 2, provider: "GOOGLE", email: "me@gmail.com", linkedAt: "2026-10-02T00:00:00Z" };
const PHONE: Identity = { id: 3, provider: "PHONE", email: null, linkedAt: "2026-10-03T00:00:00Z" };

function identitiesHandler(get: () => Identity[]) {
  return http.get("*/api/me/identities", () => HttpResponse.json(get()));
}

// TASK-13#5 내 계정: 연결된 수단 목록·연결·해제 (+ step-up).
describe("TASK-13#5 내 계정 /account/: 목록·해제", () => {
  beforeEach(() => {
    navigation.reset("/account/");
  });

  it("TASK-13#5 연결된 로그인 수단을 이름·이메일과 함께 보인다", async () => {
    server.use(meHandler(makeMe({ providers: ["GOOGLE", "LOCAL"] })), identitiesHandler(() => [LOCAL, GOOGLE]));
    renderWithQuery(<AccountView />);

    const list = await screen.findByRole("list", { name: "연결된 로그인 수단" });
    await waitFor(() => expect(within(list).getAllByRole("listitem")).toHaveLength(2));
    expect(within(list).getByText("이메일·비밀번호")).toBeInTheDocument();
    expect(within(list).getByText("me@gmail.com")).toBeInTheDocument();
  });

  it("TASK-13#5 해제는 CSRF 헤더와 함께 DELETE 하고 목록을 다시 받는다", async () => {
    let current = [LOCAL, GOOGLE];
    let header: string | null = null;
    server.use(
      meHandler(makeMe({ providers: ["GOOGLE", "LOCAL"] })),
      csrfHandler("tok"),
      identitiesHandler(() => current),
      http.delete("*/api/me/identities/2", ({ request }) => {
        header = request.headers.get("X-XSRF-TOKEN");
        current = [LOCAL];
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithQuery(<AccountView />);

    await userEvent.click(await screen.findByRole("button", { name: "Google 연결 해제" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Google 연결 해제" })).not.toBeInTheDocument());
    expect(header).toBe("tok");
    expect(await screen.findByText("Google 연결을 해제했습니다.")).toBeInTheDocument();
  });

  it("TASK-13#5 마지막 수단 해제(409 last_identity)는 한국어 안내를 보이고 목록은 그대로다", async () => {
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      identitiesHandler(() => [LOCAL]),
      http.delete("*/api/me/identities/1", () => apiError(409, "last_identity", "마지막 로그인 수단은 해제할 수 없습니다")),
    );
    renderWithQuery(<AccountView />);

    await userEvent.click(await screen.findByRole("button", { name: "이메일·비밀번호 연결 해제" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("마지막 로그인 수단은 해제할 수 없습니다");
    expect(screen.getByRole("button", { name: "이메일·비밀번호 연결 해제" })).toBeInTheDocument();
  });
});

describe("TASK-13#5 내 계정: 연결", () => {
  beforeEach(() => {
    navigation.reset("/account/");
  });

  it("TASK-13#5 아직 없는 소셜 수단만 /api/me/identities/link/{p} 링크로 보인다", async () => {
    server.use(meHandler(makeMe({ providers: ["GOOGLE", "LOCAL"] })), identitiesHandler(() => [LOCAL, GOOGLE]));
    renderWithQuery(<AccountView />);

    expect(await screen.findByRole("link", { name: "GitHub 연결" })).toHaveAttribute(
      "href",
      "/api/me/identities/link/github",
    );
    expect(screen.getByRole("link", { name: "네이버 연결" })).toHaveAttribute("href", "/api/me/identities/link/naver");
    expect(screen.getByRole("link", { name: "카카오 연결" })).toHaveAttribute("href", "/api/me/identities/link/kakao");
    expect(screen.getByRole("link", { name: "Cognito 연결" })).toHaveAttribute(
      "href",
      "/api/me/identities/link/cognito",
    );
    expect(screen.queryByRole("link", { name: "Google 연결" })).not.toBeInTheDocument();
  });

  it("TASK-13#5 ?linked=<p> 는 연결 완료, ?error=<코드> 는 한국어 오류를 보인다", async () => {
    navigation.reset("/account/", "linked=github");
    server.use(meHandler(makeMe()), identitiesHandler(() => [LOCAL]));
    const { unmount } = renderWithQuery(<AccountView />);
    expect(await screen.findByRole("status", { name: "알림" })).toHaveTextContent("GitHub 계정을 연결했습니다.");
    unmount();

    navigation.reset("/account/", "error=identity_in_use");
    renderWithQuery(<AccountView />);
    expect(await screen.findByRole("alert")).toHaveTextContent("이미 다른 계정에 연결된 로그인 수단입니다.");
  });

  it("TASK-13#5 휴대폰 연결은 OTP 를 받아 /api/auth/phone/verify?link=true 로 확인한다", async () => {
    let current = [LOCAL];
    let verifyUrl = "";
    server.use(
      meHandler(makeMe()),
      csrfHandler(),
      identitiesHandler(() => current),
      http.post("*/api/auth/phone/otp", () =>
        HttpResponse.json({ challengeId: "ch-9", expiresIn: 180 }, { status: 202 }),
      ),
      http.post("*/api/auth/phone/verify", ({ request }) => {
        verifyUrl = request.url;
        current = [LOCAL, PHONE];
        return HttpResponse.json(makeMe({ providers: ["LOCAL", "PHONE"] }));
      }),
    );
    renderWithQuery(<AccountView />);

    await userEvent.type(await screen.findByLabelText("휴대폰 번호"), "01012345678");
    await userEvent.click(screen.getByRole("button", { name: "인증번호 받기" }));
    await userEvent.type(await screen.findByLabelText("인증번호"), "654321");
    await userEvent.click(screen.getByRole("button", { name: "연결" }));

    expect(await screen.findByText("휴대폰 번호를 연결했습니다.")).toBeInTheDocument();
    expect(new URL(verifyUrl).searchParams.get("link")).toBe("true");
    const list = screen.getByRole("list", { name: "연결된 로그인 수단" });
    await waitFor(() => expect(within(list).getByText("휴대폰")).toBeInTheDocument());
  });
});

describe("TASK-13#5 내 계정: step-up(제한 세션 다시 인증)", () => {
  beforeEach(() => {
    navigation.reset("/account/");
  });

  it("TASK-13#5 LOCAL 이 있으면 비밀번호로 POST /api/auth/step-up/local 하고 제한이 풀린다", async () => {
    let body: unknown;
    let restricted = true;
    const me = () =>
      makeMe({
        providers: ["GOOGLE", "LOCAL", "PHONE"],
        stepUpRequired: restricted,
        stepUpProviders: restricted ? ["GOOGLE", "LOCAL"] : [],
      });
    server.use(
      http.get("*/api/auth/me", () => HttpResponse.json(me())),
      csrfHandler(),
      identitiesHandler(() => [LOCAL, GOOGLE, PHONE]),
      http.post("*/api/auth/step-up/local", async ({ request }) => {
        body = await request.json();
        restricted = false;
        return HttpResponse.json(me());
      }),
    );
    const { client } = renderWithQuery(<AccountView />);

    const section = await screen.findByRole("region", { name: "다시 인증" });
    expect(within(section).getByRole("link", { name: "Google로 다시 로그인" })).toHaveAttribute(
      "href",
      "/api/oauth2/authorization/google",
    );
    expect(screen.getByRole("link", { name: "GitHub 연결" })).toHaveAttribute("aria-disabled", "true");

    await userEvent.type(within(section).getByLabelText("비밀번호"), "passw0rd");
    await userEvent.click(within(section).getByRole("button", { name: "다시 인증" }));

    await waitFor(() => expect(screen.queryByRole("region", { name: "다시 인증" })).not.toBeInTheDocument());
    expect(body).toEqual({ password: "passw0rd" });
    expect(client.getQueryData(queryKeys.me)).toMatchObject({ stepUpRequired: false });
  });

  it("TASK-13#5 step-up 비밀번호가 틀리면 안내만 하고 로그인 상태는 유지한다", async () => {
    server.use(
      meHandler(makeMe({ providers: ["LOCAL", "PHONE"], stepUpRequired: true, stepUpProviders: ["LOCAL"] })),
      csrfHandler(),
      identitiesHandler(() => [LOCAL, PHONE]),
      http.post("*/api/auth/step-up/local", () => apiError(401, "invalid_credentials")),
    );
    const { client } = renderWithQuery(<AccountView />);

    const section = await screen.findByRole("region", { name: "다시 인증" });
    await userEvent.type(within(section).getByLabelText("비밀번호"), "wrong1234");
    await userEvent.click(within(section).getByRole("button", { name: "다시 인증" }));

    expect(await within(section).findByRole("alert")).toHaveTextContent("이메일 또는 비밀번호가 올바르지 않습니다.");
    expect(client.getQueryData(queryKeys.me)).toMatchObject({ stepUpRequired: true });
  });
});

describe("TASK-28#4 계정 연결 실패 ?error=", () => {
  it("TASK-28#4 oauth2_failed 는 연결 전용 문장, 모르는 코드는 연결 전용 fallback(코드 원문은 보이지 않는다)", async () => {
    navigation.reset("/account/", "error=oauth2_failed");
    server.use(meHandler(makeMe()), identitiesHandler(() => [LOCAL]));
    const { unmount } = renderWithQuery(<AccountView />);
    expect(await screen.findByRole("alert")).toHaveTextContent(OAUTH_LINK_MESSAGES.oauth2_failed);
    expect(screen.getByRole("alert")).not.toHaveTextContent("로그인");
    unmount();

    navigation.reset("/account/", "error=Weird-Provider-Error");
    renderWithQuery(<AccountView />);
    expect(await screen.findByRole("alert")).toHaveTextContent(OAUTH_LINK_FALLBACK);
    expect(screen.getByRole("alert")).not.toHaveTextContent("Weird");
  });
});

// TASK-28#5 FE-17: ?linked= 는 알려진 provider 만 알리고, 읽은 뒤 URL 에서 지워 새로고침해도 반복되지 않는다.
describe("TASK-28#5 ?linked= 처리", () => {
  it.each([
    ["google", "Google"],
    ["github", "GitHub"],
    ["naver", "네이버"],
    ["kakao", "카카오"],
    ["cognito", "Cognito"],
  ])("TASK-28#5 linked=%s 는 '%s 계정을 연결했습니다.'를 보이고 쿼리를 지운다", async (id, label) => {
    navigation.reset("/account/", `linked=${id}`);
    server.use(meHandler(makeMe()), identitiesHandler(() => [LOCAL]));
    renderWithQuery(<AccountView />);

    expect(await screen.findByRole("status", { name: "알림" })).toHaveTextContent(`${label} 계정을 연결했습니다.`);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/account/", { scroll: false }));
  });

  it.each(["evil", "<b>해킹</b> 성공! 비밀번호를 입력하세요", "GitHub", "phone", "local", ""])(
    "TASK-28#5 모르는 linked=%j 는 표시하지 않고 쿼리만 지운다",
    async (value) => {
      navigation.reset("/account/", `linked=${encodeURIComponent(value)}`);
      server.use(meHandler(makeMe()), identitiesHandler(() => [LOCAL]));
      renderWithQuery(<AccountView />);

      await screen.findByRole("list", { name: "연결된 로그인 수단" });
      expect(screen.queryByRole("status", { name: "알림" })).not.toBeInTheDocument();
      expect(screen.queryByText(/계정을 연결했습니다/)).not.toBeInTheDocument();
      await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/account/", { scroll: false }));
    },
  );

  it("TASK-28#5 linked 만 지우고 다른 쿼리는 남긴다, linked 가 없으면 URL 을 건드리지 않는다", async () => {
    navigation.reset("/account/", "linked=kakao&tab=x");
    server.use(meHandler(makeMe()), identitiesHandler(() => [LOCAL]));
    const { unmount } = renderWithQuery(<AccountView />);
    await screen.findByRole("status", { name: "알림" });
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/account/?tab=x", { scroll: false }));
    unmount();

    navigation.reset("/account/", "tab=x");
    renderWithQuery(<AccountView />);
    await screen.findByRole("list", { name: "연결된 로그인 수단" });
    expect(navigation.replace).not.toHaveBeenCalled();
  });
});

// TASK-28#5: linked·error 를 지울 때 다른 쿼리와 hash 는 남긴다. ?error= 도 표시 후 지워 새로고침에 반복되지 않는다.
describe("TASK-28#5 URL 정리(hash 보존, ?error= 제거)", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("TASK-28#5 linked 를 지울 때 hash 를 남긴다", async () => {
    window.history.replaceState(null, "", "/account/?linked=kakao&tab=x#step-up");
    navigation.reset("/account/", "linked=kakao&tab=x");
    server.use(meHandler(makeMe()), identitiesHandler(() => [LOCAL]));
    renderWithQuery(<AccountView />);
    await screen.findByRole("status", { name: "알림" });
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledWith("/account/?tab=x#step-up", { scroll: false }),
    );
  });

  it("TASK-28#5 ?error= 는 보여 준 뒤 주소에서 지운다(다른 쿼리·hash 유지)", async () => {
    window.history.replaceState(null, "", "/account/?error=identity_in_use&tab=x#step-up");
    navigation.reset("/account/", "error=identity_in_use&tab=x");
    server.use(meHandler(makeMe()), identitiesHandler(() => [LOCAL]));
    renderWithQuery(<AccountView />);
    expect(await screen.findByRole("alert")).toHaveTextContent(ERROR_MESSAGES.identity_in_use);
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledWith("/account/?tab=x#step-up", { scroll: false }),
    );
  });

  it("TASK-28#5 linked 와 error 가 함께 오면 둘 다 지운다", async () => {
    navigation.reset("/account/", "linked=github&error=oauth2_failed");
    server.use(meHandler(makeMe()), identitiesHandler(() => [LOCAL]));
    renderWithQuery(<AccountView />);
    await screen.findByText(OAUTH_LINK_MESSAGES.oauth2_failed);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/account/", { scroll: false }));
    for (const [url] of navigation.replace.mock.calls) expect(url).toBe("/account/");
  });
});
