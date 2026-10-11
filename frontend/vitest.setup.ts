import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll } from "vitest";

import { clearCookies, server } from "@/test/server";

beforeAll(() => {
  // 핸들러가 없는 요청은 테스트 실패로 본다(모르는 API 호출을 숨기지 않는다).
  server.listen({ onUnhandledRequest: "error" });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  clearCookies();
});

afterAll(() => {
  server.close();
});
