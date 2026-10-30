import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterAll, afterEach, beforeAll } from "vitest";

import { clearCookies, server } from "@/test/server";

// findBy*/waitFor 기본 대기(1초)는 전체 스위트를 병렬로 돌릴 때(make test 중 백엔드 빌드와 겹칠 때 등)
// 모자라서 간헐 실패가 났다(TASK-13#5 계정 목록). 실제 실패는 그대로 잡히고 느린 머신에서만 여유를 준다.
configure({ asyncUtilTimeout: 5000 });

beforeAll(() => {
  // 핸들러가 없는 요청은 테스트 실패로 본다(모르는 API 호출을 숨기지 않는다).
  server.listen({ onUnhandledRequest: "error" });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  clearCookies();
  // 초안(TASK-29#3)·auth.next 가 다음 테스트로 새지 않게.
  window.sessionStorage.clear();
});

afterAll(() => {
  server.close();
});
