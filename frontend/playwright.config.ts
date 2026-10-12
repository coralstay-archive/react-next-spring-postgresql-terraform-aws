import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright E2E (TASK-18#5). 스택(PostgreSQL·MinIO·백엔드·next dev)은 `scripts/e2e.sh`(= `make e2e`)가 띄우고 내린다.
 * 그래서 webServer 를 두지 않는다. 시나리오는 한 흐름(가입 → 글 → 다른 계정 → 관리자 정지 → OTP → 삭제)이라 직렬로 돈다.
 */
export default defineConfig({
  testDir: "./e2e",
  outputDir: "./e2e/artifacts/test-results",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  // next dev 는 처음 여는 화면을 그때 컴파일한다. e2e.sh 가 미리 데워 두지만 여유를 둔다.
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [["list"], ["html", { outputFolder: "./e2e/artifacts/report", open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3100",
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
