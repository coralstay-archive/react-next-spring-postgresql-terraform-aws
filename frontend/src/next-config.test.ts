import { PHASE_DEVELOPMENT_SERVER, PHASE_PRODUCTION_BUILD } from "next/constants";
import { afterEach, describe, expect, it, vi } from "vitest";

import nextConfig from "../next.config";

// TASK-13#6 next dev 에서는 /api 를 로컬 백엔드로 rewrite 하고, next build 는 정적 export 를 유지한다.
describe("TASK-13#6 next.config: dev 프록시와 정적 export", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("TASK-13#6 next build(production)는 output:'export'이고 rewrites 가 없다", () => {
    const config = nextConfig(PHASE_PRODUCTION_BUILD);
    expect(config.output).toBe("export");
    expect(config.trailingSlash).toBe(true);
    expect(config.images?.unoptimized).toBe(true);
    expect(config.rewrites).toBeUndefined();
  });

  it("TASK-13#6 next dev 는 export 를 끄고 /api/:path* 를 기본 http://localhost:8080 으로 rewrite 한다", async () => {
    vi.stubEnv("BACKEND_URL", "");
    const config = nextConfig(PHASE_DEVELOPMENT_SERVER);
    expect(config.output).toBeUndefined();
    // /api 는 trailing slash 리다이렉트 없이 그대로 백엔드로 넘긴다(운영 CloudFront /api/* 와 같은 모양).
    expect(config.skipTrailingSlashRedirect).toBe(true);
    const rewrites = await config.rewrites!();
    expect(rewrites).toEqual([
      { source: "/api/:path*", destination: "http://localhost:8080/api/:path*" },
    ]);
  });

  it("TASK-13#6 BACKEND_URL 로 프록시 대상을 바꿀 수 있다(끝 / 는 떼어 낸다)", async () => {
    vi.stubEnv("BACKEND_URL", "http://127.0.0.1:9090/");
    const config = nextConfig(PHASE_DEVELOPMENT_SERVER);
    const rewrites = await config.rewrites!();
    expect(rewrites).toEqual([
      { source: "/api/:path*", destination: "http://127.0.0.1:9090/api/:path*" },
    ]);
  });
});
