// TASK-16#3·TASK-34#2 CloudFront Function(viewer-request·viewer-response) 테스트. Node 로 함수 코드를 그대로 불러 handler 를 실행한다.
// CloudFront Functions 는 모듈 문법이 없으므로 파일 끝에 return 을 붙여 handler·PAGES 를 꺼낸다.
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const code = readFileSync(path.join(root, "infra/main/modules/frontend_cdn/functions/index_rewrite.js"), "utf8");
const { handler, PAGES } = new Function(`${code}\nreturn { handler: handler, PAGES: typeof PAGES === "undefined" ? undefined : PAGES };`)();

let failures = 0;
function report(ok, label) {
  console.log(`  ${ok ? "ok  " : "FAIL"} - ${label}`);
  if (!ok) failures++;
}

function viewerRequest(uri) {
  const request = { method: "GET", uri, querystring: { id: { value: "1" } }, headers: {}, cookies: {} };
  return handler({ version: "1.0", context: { eventType: "viewer-request" }, viewer: { ip: "198.51.100.1" }, request });
}

function check(id, name, uri, expected) {
  const out = viewerRequest(uri);
  report(out.uri === expected && out.querystring?.id?.value === "1", `${id} ${name}: ${uri} → ${out.uri}`);
}

/** viewer-response: 요청 uri 는 viewer-request 가 바꾼 값이든 원래 값이든 같은 답이어야 한다(어느 쪽을 받을지 함수가 기대지 않는다). */
function checkStatus(id, name, uri, status, expected) {
  for (const seen of new Set([uri, viewerRequest(uri).uri])) {
    const response = { statusCode: status, statusDescription: "OK", headers: {}, cookies: {} };
    const request = { method: "GET", uri: seen, querystring: {}, headers: {}, cookies: {} };
    let out;
    try {
      out = handler({ version: "1.0", context: { eventType: "viewer-response" }, viewer: { ip: "198.51.100.1" }, request, response });
    } catch (e) {
      out = { error: String(e) };
    }
    report(out?.statusCode === expected, `${id} ${name}: ${seen} ${status} → ${out?.statusCode ?? out?.error}`);
  }
}

console.log("cf function tests");
check("TASK-16#3", "루트", "/", "/index.html");
check("TASK-16#3", "트레일링 슬래시 디렉터리", "/login/", "/login/index.html");
check("TASK-16#3", "중첩 디렉터리 + 쿼리 유지", "/posts/view/", "/posts/view/index.html");
check("TASK-16#3", "슬래시 없는 경로도 index", "/account", "/account/index.html");
check("TASK-16#3", "확장자 있는 정적 파일은 그대로", "/_next/static/chunks/app.js", "/_next/static/chunks/app.js");
check("TASK-16#3", "404 페이지 파일은 그대로", "/404.html", "/404.html");
check("TASK-16#3", "파비콘은 그대로", "/favicon.ico", "/favicon.ico");
check("TASK-16#3", "index.html 직접 요청은 그대로", "/login/index.html", "/login/index.html");

// TASK-34#2 모르는 페이지 경로(확장자 없는 경로·디렉터리)는 S3 NoSuchKey XML 대신 /404.html 로
check("TASK-34#2", "모르는 경로(슬래시)", "/nope/", "/404.html");
check("TASK-34#2", "모르는 경로(슬래시 없음)", "/nope", "/404.html");
check("TASK-34#2", "모르는 중첩 경로", "/posts/123/", "/404.html");
check("TASK-34#2", "페이지가 아닌 중간 디렉터리", "/posts/", "/404.html");
check("TASK-34#2", "모르는 경로의 index.html", "/nope/index.html", "/404.html");
check("TASK-34#2", "Next 가 만든 /404/ 도 404 페이지로", "/404/", "/404.html");
check("TASK-34#2", "아는 페이지는 그대로 index", "/auth/callback", "/auth/callback/index.html");

// TASK-34#B1 /api 응답은 HTML 404 로 바뀌지 않는다 — 함수는 /api/* 에 붙지 않지만(tftest), 붙더라도 건드리지 않는다(이중 방어).
check("TASK-34#B1", "/api/* 는 리라이트하지 않는다", "/api/posts/9999", "/api/posts/9999");
check("TASK-34#B1", "/api/* 트레일링 슬래시도 그대로", "/api/auth/csrf/", "/api/auth/csrf/");
check("TASK-34#B1", "/api 자체도 그대로", "/api", "/api");
check("TASK-34#2", "/uploads/* 도 리라이트하지 않는다", "/uploads/2026/x", "/uploads/2026/x");

// TASK-34#2 viewer-response: /404.html 로 보낸 응답은 상태 404(soft 200 이 아니다). 아는 페이지·파일·/api 는 그대로.
checkStatus("TASK-34#2", "모르는 경로는 404 상태", "/nope/", 200, 404);
checkStatus("TASK-34#2", "/404.html 직접 요청도 404 상태", "/404.html", 200, 404);
checkStatus("TASK-34#2", "아는 페이지는 200 유지", "/login/", 200, 200);
checkStatus("TASK-34#2", "루트는 200 유지", "/", 200, 200);
checkStatus("TASK-34#2", "정적 파일은 200 유지", "/_next/static/chunks/app.js", 200, 200);
checkStatus("TASK-34#2", "304 는 건드리지 않는다", "/nope/", 304, 304);
checkStatus("TASK-34#B1", "/api 404 JSON 상태는 그대로", "/api/posts/9999", 404, 404);
checkStatus("TASK-34#B1", "/api 200 은 404 로 바뀌지 않는다", "/api/nope/", 200, 200);
checkStatus("TASK-34#2", "/uploads 200 은 그대로", "/uploads/2026/x", 200, 200);

// TASK-34#2 함수의 페이지 목록 = frontend/src/app 의 page.tsx 목록. 페이지를 더하고 목록을 안 고치면 새 페이지가 404 가 된다.
function appPages(dir, rel = "") {
  const found = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) found.push(...appPages(path.join(dir, entry.name), `${rel}${entry.name}/`));
    else if (/^page\.(tsx|ts|jsx|js|mdx)$/.test(entry.name)) found.push(`/${rel}`);
  }
  return found;
}
const pages = appPages(path.join(root, "frontend/src/app")).sort();
const dynamic = pages.filter((p) => /[[(@]/.test(p));
report(dynamic.length === 0, `TASK-34#2 정적 export 페이지에 동적·그룹 세그먼트가 없다(있으면 함수의 경로 판단을 다시 설계): ${dynamic.join(", ") || "-"}`);
report(
  Array.isArray(PAGES) && JSON.stringify([...PAGES].sort()) === JSON.stringify(pages),
  `TASK-34#2 함수 PAGES 가 app 의 page.tsx 목록과 같다: app=${pages.join(",")} fn=${Array.isArray(PAGES) ? [...PAGES].sort().join(",") : PAGES}`,
);
for (const page of pages) check("TASK-34#2", "app 페이지는 index 로", page, `${page}index.html`);

process.exit(failures === 0 ? 0 : 1);
