// TASK-16#3 CloudFront Function(viewer-request) index 리라이트 테스트. Node 로 함수 코드를 그대로 불러 handler 를 실행한다.
// CloudFront Functions 는 모듈 문법이 없으므로 파일 끝에 return 을 붙여 handler 를 꺼낸다.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const code = readFileSync(path.join(root, "infra/main/modules/frontend_cdn/functions/index_rewrite.js"), "utf8");
const handler = new Function(`${code}\nreturn handler;`)();

let failures = 0;
function check(name, uri, expected) {
  const request = { method: "GET", uri, querystring: { id: { value: "1" } }, headers: {}, cookies: {} };
  const out = handler({ version: "1.0", context: { eventType: "viewer-request" }, viewer: { ip: "198.51.100.1" }, request });
  const ok = out.uri === expected && out.querystring?.id?.value === "1";
  console.log(`  ${ok ? "ok  " : "FAIL"} - TASK-16#3 ${name}: ${uri} → ${out.uri}`);
  if (!ok) failures++;
}

console.log("cf function tests");
check("루트", "/", "/index.html");
check("트레일링 슬래시 디렉터리", "/login/", "/login/index.html");
check("중첩 디렉터리 + 쿼리 유지", "/posts/view/", "/posts/view/index.html");
check("슬래시 없는 경로도 index", "/account", "/account/index.html");
check("확장자 있는 정적 파일은 그대로", "/_next/static/chunks/app.js", "/_next/static/chunks/app.js");
check("404 페이지 파일은 그대로", "/404.html", "/404.html");
check("파비콘은 그대로", "/favicon.ico", "/favicon.ico");
process.exit(failures === 0 ? 0 : 1);
