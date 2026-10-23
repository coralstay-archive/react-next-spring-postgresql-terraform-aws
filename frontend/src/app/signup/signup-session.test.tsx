import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SignupView } from "@/app/signup/signup-view";
import { apiError, csrfHandler, server } from "@/test/server";
import { anonymous, makeMe, navigation, renderWithQuery } from "@/test/utils";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

/** 한 글자씩 치는 type 대신 붙여넣기(부하가 큰 make test 에서도 빠르게). */
async function fillForm() {
  for (const [label, value] of [
    ["이메일", "new@example.com"],
    ["비밀번호", "passw0rd"],
    ["닉네임", "새사람"],
  ]) {
    await userEvent.click(screen.getByLabelText(label));
    await userEvent.paste(value);
  }
}

function signupBackend(login: () => Response) {
  const calls = { signup: 0, login: 0 };
  return {
    calls,
    handlers: [
      anonymous(),
      csrfHandler(),
      http.post("*/api/auth/signup", () => {
        calls.signup++;
        return calls.signup === 1
          ? HttpResponse.json(makeMe({ nickname: "새사람" }), { status: 201 })
          : apiError(409, "email_taken", "이미 가입된 이메일입니다");
      }),
      http.post("*/api/auth/login", () => {
        calls.login++;
        return login();
      }),
    ],
  };
}

describe("TASK-29#8 가입 성공 후 자동 로그인이 실패하면 폼 대신 로그인 링크", () => {
  beforeEach(() => {
    navigation.reset("/signup/", `next=${encodeURIComponent("/posts/new/")}`);
  });

  it("TASK-29#8 자동 로그인이 실패하면 가입 폼을 없애고(재제출 409 방지) next 를 실은 로그인 링크를 보인다", async () => {
    const { calls, handlers } = signupBackend(() => apiError(503, "db_unavailable", "장애"));
    server.use(...handlers);
    renderWithQuery(<SignupView />);

    await fillForm();
    await userEvent.click(screen.getByRole("button", { name: "가입하기" }));

    const done = await screen.findByTestId("signup-login-failed");
    expect(done).toHaveTextContent("가입은 완료됐습니다");
    expect(done).toHaveTextContent("서비스에 일시적인 장애가 있습니다");
    expect(screen.getByRole("link", { name: "로그인하러 가기" })).toHaveAttribute(
      "href",
      `/login/?next=${encodeURIComponent("/posts/new/")}`,
    );
    expect(screen.queryByRole("button", { name: "가입하기" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("비밀번호")).not.toBeInTheDocument();
    expect(calls).toEqual({ signup: 1, login: 1 });
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("TASK-29#8 로그인 링크의 next 도 safeNext 를 거친다", async () => {
    navigation.reset("/signup/", `next=${encodeURIComponent("/\t/evil.com")}`);
    server.use(...signupBackend(() => HttpResponse.error()).handlers);
    renderWithQuery(<SignupView />);

    await fillForm();
    await userEvent.click(screen.getByRole("button", { name: "가입하기" }));

    expect(await screen.findByRole("link", { name: "로그인하러 가기" })).toHaveAttribute("href", "/login/");
  });

  it("TASK-29#8 같은 틱에 두 번 제출해도 가입 요청은 하나다(이중 제출 차단)", async () => {
    const { calls, handlers } = signupBackend(() => HttpResponse.json(makeMe()));
    server.use(...handlers);
    renderWithQuery(<SignupView />);

    await fillForm();
    const form = screen.getByRole("button", { name: "가입하기" }).closest("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/posts/new/"));
    expect(calls.signup).toBe(1);
  });
});
