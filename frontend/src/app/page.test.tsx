import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import Home from "./page";

// TASK-1#1 프론트 스캐폴드가 빌드·테스트된다.
describe("TASK-1#1 홈 페이지", () => {
  it("TASK-1#1 게시판 제목을 렌더링한다", () => {
    render(<Home />);
    expect(screen.getByRole("heading", { level: 1, name: "게시판" })).toBeInTheDocument();
  });
});
