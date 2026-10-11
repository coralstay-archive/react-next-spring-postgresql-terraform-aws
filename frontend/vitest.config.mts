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
    // next.config.ts 의 trailingSlash:true 를 next/link 에 알린다(빌드에서는 Next 가 이 값을 주입한다).
    env: { __NEXT_TRAILING_SLASH: "true" },
  },
});
