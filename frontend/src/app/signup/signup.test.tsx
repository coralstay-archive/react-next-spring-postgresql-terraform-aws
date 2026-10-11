import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SignupView } from "@/app/signup/signup-view";
import { validateSignup } from "@/lib/validation";
import { queryKeys } from "@/lib/query";
import { apiError, csrfHandler, server } from "@/test/server";
import { anonymous, makeMe, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

// TASK-13#4 가입 화면: 서버와 같은 검증, 409 email_taken, 가입 후 자동 로그인.
describe("TASK-13#4 validateSignup: 서버 규칙과 같은 클라이언트 검증", () => {
  const ok = { email: "a@example.com", password: "passw0rd", nickname: "철수" };

  it("TASK-13#4 올바른 값은 오류가 없다", () => {
    expect(validateSignup(ok)).toEqual({});
    expect(validateSignup({ ...ok, email: "  a@example.com  ", nickname: "  철수  " })).toEqual({});
  });

  it("TASK-13#4 이메일: 비었거나 모양이 틀리면 오류", () => {
    expect(validateSignup({ ...ok, email: "   " }).email).toBe("이메일을 입력하세요");
    expect(validateSignup({ ...ok, email: "a@b" }).email).toBe("이메일 형식이 아닙니다");
    expect(validateSignup({ ...ok, email: "a b@c.d" }).email).toBe("이메일 형식이 아닙니다");
    expect(validateSignup({ ...ok, email: `${"a".repeat(310)}@example.com` }).email).toBe("이메일이 너무 깁니다");
  });

  it("TASK-13#4 비밀번호: 8자 이상, UTF-8 72바이트 이하, 문자와 숫자 모두", () => {
    const message = "비밀번호는 8자 이상 72바이트 이하이고 문자와 숫자를 모두 포함해야 합니다";
    expect(validateSignup({ ...ok, password: "abc1234" }).password).toBe(message); // 7자
    expect(validateSignup({ ...ok, password: "abcdefgh" }).password).toBe(message); // 숫자 없음
    expect(validateSignup({ ...ok, password: "12345678" }).password).toBe(message); // 문자 없음
    expect(validateSignup({ ...ok, password: "비밀번호입니다12" }).password).toBeUndefined(); // 한글도 문자
    expect(validateSignup({ ...ok, password: `${"가".repeat(24)}1` }).password).toBe(message); // 73바이트
    expect(validateSignup({ ...ok, password: `${"가".repeat(23)}1` }).password).toBeUndefined(); // 70바이트
    expect(validateSignup({ ...ok, password: `${"a".repeat(71)}1` }).password).toBeUndefined(); // 72바이트
  });

  it("TASK-13#4 닉네임: 앞뒤 공백을 뗀 뒤 2~20자", () => {
    expect(validateSignup({ ...ok, nickname: " " }).nickname).toBe("닉네임을 입력하세요");
    expect(validateSignup({ ...ok, nickname: " 가 " }).nickname).toBe("닉네임은 2~20자입니다");
    expect(validateSignup({ ...ok, nickname: "가".repeat(21) }).nickname).toBe("닉네임은 2~20자입니다");
    expect(validateSignup({ ...ok, nickname: "가".repeat(20) }).nickname).toBeUndefined();
  });
});

describe("TASK-13#4 가입 화면 /signup/", () => {
  beforeEach(() => {
    navigation.reset("/signup/");
  });

  async function fill(email: string, password: string, nickname: string) {
    if (email) await userEvent.type(screen.getByLabelText("이메일"), email);
    if (password) await userEvent.type(screen.getByLabelText("비밀번호"), password);
    if (nickname) await userEvent.type(screen.getByLabelText("닉네임"), nickname);
    await userEvent.click(screen.getByRole("button", { name: "가입하기" }));
  }

  it("TASK-13#4 클라이언트 검증에 걸리면 요청을 보내지 않고 필드 오류를 보인다", async () => {
    let calls = 0;
    server.use(
      anonymous(),
      http.post("*/api/auth/signup", () => {
        calls += 1;
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    renderWithQuery(<SignupView />);

    await fill("bad", "short", "가");
    expect(await screen.findByText("이메일 형식이 아닙니다")).toBeInTheDocument();
    expect(screen.getByText(/비밀번호는 8자 이상/)).toBeInTheDocument();
    expect(screen.getByText("닉네임은 2~20자입니다")).toBeInTheDocument();
    expect(screen.getByLabelText("이메일")).toHaveAttribute("aria-invalid", "true");
    expect(calls).toBe(0);
  });

  it("TASK-13#4 409 email_taken 은 이메일 필드 오류로 보인다", async () => {
    server.use(anonymous(), csrfHandler(), http.post("*/api/auth/signup", () => apiError(409, "email_taken")));
    renderWithQuery(<SignupView />);

    await fill("dup@example.com", "passw0rd", "철수");
    expect(await screen.findByText("이미 가입된 이메일입니다.")).toBeInTheDocument();
    expect(screen.getByLabelText("이메일")).toHaveAttribute("aria-invalid", "true");
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("TASK-13#4 서버 400 validation 의 fields 를 필드 오류로 보인다", async () => {
    server.use(
      anonymous(),
      csrfHandler(),
      http.post("*/api/auth/signup", () =>
        apiError(400, "validation", "입력값이 올바르지 않습니다", { fields: { nickname: "서버 닉네임 오류" } }),
      ),
    );
    renderWithQuery(<SignupView />);

    await fill("a@example.com", "passw0rd", "철수");
    expect(await screen.findByText("서버 닉네임 오류")).toBeInTheDocument();
  });

  it("TASK-13#4 가입(201) 뒤 같은 이메일·비밀번호로 /api/auth/login 을 불러 자동 로그인하고 홈으로 간다", async () => {
    const calls: string[] = [];
    let loginBody: unknown;
    server.use(
      anonymous(),
      csrfHandler(),
      http.post("*/api/auth/signup", async ({ request }) => {
        calls.push("signup");
        expect(await request.json()).toEqual({ email: "new@example.com", password: "passw0rd", nickname: "새사람" });
        return HttpResponse.json(makeMe({ nickname: "새사람" }), { status: 201 });
      }),
      http.post("*/api/auth/login", async ({ request }) => {
        calls.push("login");
        loginBody = await request.json();
        return HttpResponse.json(makeMe({ nickname: "새사람" }));
      }),
    );
    const { client } = renderWithQuery(<SignupView />);

    await fill(" new@example.com ", "passw0rd", " 새사람 ");
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/"));
    expect(calls).toEqual(["signup", "login"]);
    expect(loginBody).toEqual({ email: "new@example.com", password: "passw0rd" });
    expect(client.getQueryData(queryKeys.me)).toMatchObject({ nickname: "새사람" });
  });
});
