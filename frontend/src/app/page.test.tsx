import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { categoriesHandler, postsPageHandler, tagsHandler } from "@/test/board";
import { server } from "@/test/server";
import { anonymous, navigation, renderWithQuery } from "@/test/utils";

import Home from "./page";

vi.mock("next/navigation", async () => (await import("@/test/utils")).nextNavigationMock());

// TASK-1#1 프론트 스캐폴드가 빌드·테스트된다. TASK-14#1 부터 홈(/)은 게시판 목록이다.
describe("TASK-1#1 홈 페이지", () => {
  it("TASK-1#1 게시판 제목을 렌더링한다 (TASK-14#1 홈 = 목록)", async () => {
    navigation.reset("/");
    server.use(anonymous(), categoriesHandler(), tagsHandler(), postsPageHandler());
    renderWithQuery(<Home />);
    expect(await screen.findByRole("heading", { level: 1, name: "게시판" })).toBeInTheDocument();
    expect(await screen.findByTestId("post-list")).toBeInTheDocument();
  });
});
