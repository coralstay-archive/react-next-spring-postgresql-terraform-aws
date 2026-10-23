import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    globals: false,
    setupFiles: ["./vitest.setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
    // 파일의 첫 테스트는 jsdom·모듈 로딩(콜드 스타트)까지 떠안아, make test 가 백엔드 빌드와 겹치면 기본 5초를 넘겼다(TASK-29 리뷰).
    // 실제로 멈춘 테스트는 그대로 잡히고 느린 머신에서만 여유를 준다. 워커 수도 코어 절반으로 묶어 부하를 줄인다.
    testTimeout: 15_000,
    hookTimeout: 15_000,
    maxWorkers: "50%",
    // next.config.ts 의 trailingSlash:true 를 next/link 에 알린다(빌드에서는 Next 가 이 값을 주입한다).
    env: { __NEXT_TRAILING_SLASH: "true" },
  },
});
