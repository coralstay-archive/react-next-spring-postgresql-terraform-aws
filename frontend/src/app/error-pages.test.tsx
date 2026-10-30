import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import ErrorPage from "@/app/error";
import GlobalError from "@/app/global-error";
import NotFound from "@/app/not-found";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

const SECRET = "TypeError: Cannot read properties of undefined (reading 'token') at /secret/path.js";

function boom(): Error & { digest?: string } {
  return Object.assign(new Error(SECRET), { digest: "123" });
}

describe("TASK-34#1 오류 경계·404 화면(FE-34)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("TASK-34#1 error.tsx: 한국어 안내와 '다시 시도'를 보이고, 누르면 retry 를 부른다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const retry = vi.fn();
    const reset = vi.fn();
    render(<ErrorPage error={boom()} retry={retry} reset={reset} />);

    expect(screen.getByRole("heading", { name: "문제가 생겼습니다" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("화면을 보여 주는 중에 오류가 났습니다");
    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(retry).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("link", { name: "목록으로" })).toHaveAttribute("href", "/");
  });

  it("TASK-34#1 error.tsx: 원문 오류 메시지는 화면에 보이지 않고 콘솔에만 남긴다(TASK-28 B2)", () => {
    const log = vi.spyOn(console, "error").mockImplementation(() => {});
    const error = boom();
    const { container } = render(<ErrorPage error={error} retry={vi.fn()} reset={vi.fn()} />);

    expect(container).not.toHaveTextContent("TypeError");
    expect(container).not.toHaveTextContent("secret");
    expect(log).toHaveBeenCalledWith(error);
  });

  it("TASK-34#1 global-error.tsx: 자체 <html lang=ko>·<body> 를 그리고 한국어 안내와 '다시 시도'를 보인다", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const html = renderToStaticMarkup(<GlobalError error={boom()} retry={vi.fn()} reset={vi.fn()} />);

    expect(html).toMatch(/^<html lang="ko"[^>]*>.*<body/);
    expect(html).toContain("문제가 생겼습니다");
    expect(html).toContain("다시 시도");
    expect(html).not.toContain("TypeError");
    expect(html).not.toContain("secret");
  });

  it("TASK-34#1 global-error.tsx: '다시 시도'가 retry 를 부르고 원문은 콘솔에만 남긴다", async () => {
    const log = vi.spyOn(console, "error").mockImplementation(() => {});
    const retry = vi.fn();
    const error = boom();
    // <html>·<body> 중첩 경고는 console.error 목으로 막는다(구조는 위 정적 렌더가 검사한다).
    render(<GlobalError error={error} retry={retry} reset={vi.fn()} />);

    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(retry).toHaveBeenCalledTimes(1);
    expect(document.body).not.toHaveTextContent("TypeError");
    expect(log).toHaveBeenCalledWith(error);
  });

  it("TASK-34#1 not-found.tsx: 한국어 안내와 목록(/) 링크", () => {
    render(<NotFound />);

    expect(screen.getByRole("heading", { name: "페이지를 찾을 수 없습니다" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "목록으로" })).toHaveAttribute("href", "/");
  });
});
