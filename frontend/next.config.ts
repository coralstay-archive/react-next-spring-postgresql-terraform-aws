import type { NextConfig } from "next";
import { PHASE_DEVELOPMENT_SERVER } from "next/constants";

// 정적 export → S3 + CloudFront (decision-10).
// cacheComponents/partialPrefetching(PPR)은 export 모드와 호환되지 않아 끈다.
// trailingSlash: /posts → /posts/index.html 로 내보내서 CloudFront Function의 index 리라이트와 맞춘다.
const base: NextConfig = {
  trailingSlash: true,
  images: { unoptimized: true },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

/** 개발 서버가 /api 를 넘길 백엔드 주소. 기본은 `make backend-run`(http://localhost:8080). */
function backendUrl(): string {
  const url = process.env.BACKEND_URL?.trim() || "http://localhost:8080";
  return url.replace(/\/+$/, "");
}

/**
 * - `next build`: 정적 export(`out/`). rewrites 는 export 와 같이 쓸 수 없다.
 * - `next dev`: export 를 끄고 `/api/*` 를 로컬 백엔드로 프록시한다. 운영의 CloudFront `/api/*` 처럼 브라우저 입장에서는
 *   같은 오리진이라 `SESSION`·`XSRF-TOKEN` 쿠키(path=/, SameSite=Lax)와 OAuth 리다이렉트(`PUBLIC_BASE_URL=http://localhost:3000`)가
 *   그대로 동작한다. trailing slash 리다이렉트는 끈다(`/api/auth/csrf` → `/api/auth/csrf/` 로 바뀌면 Spring 이 404).
 */
export default function config(phase: string): NextConfig {
  if (phase === PHASE_DEVELOPMENT_SERVER) {
    return {
      ...base,
      skipTrailingSlashRedirect: true,
      async rewrites() {
        return [{ source: "/api/:path*", destination: `${backendUrl()}/api/:path*` }];
      },
    };
  }
  return { ...base, output: "export" };
}
