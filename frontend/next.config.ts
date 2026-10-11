import type { NextConfig } from "next";

// 정적 export → S3 + CloudFront (decision-10).
// cacheComponents/partialPrefetching(PPR)은 export 모드와 호환되지 않아 끈다.
// trailingSlash: /posts → /posts/index.html 로 내보내서 CloudFront Function의 index 리라이트와 맞춘다.
const nextConfig: NextConfig = {
  output: "export",
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

export default nextConfig;
