// CloudFront Function (cloudfront-js-2.0). 기본 동작(web S3 버킷)의 viewer-request·viewer-response 에 같은 함수를 건다 — /api/*, /uploads/* 에는 붙이지 않는다.
//
// viewer-request (TASK-16#3, TASK-34#2)
//   Next static export(trailingSlash)는 페이지를 <경로>/index.html 로 만든다. OAC(REST) 오리진은 하위 index 를 붙여 주지 않으므로 여기서 붙인다.
//   /login/  → /login/index.html
//   /login   → /login/index.html   (마지막 경로 조각에 '.' 이 없으면 페이지로 본다)
//   /nope/   → /404.html           (PAGES 에 없는 페이지 경로. 전에는 S3 NoSuchKey XML 이 그대로 보였다 — FE-35)
//   /_next/static/x.js, /404.html, /favicon.ico → 그대로 (파일. 없으면 S3 404 그대로)
//   쿼리 문자열(/posts/view/?id=1)은 건드리지 않는다.
// viewer-response (TASK-34#2)
//   /404.html 로 보낸 응답(과 /404.html 직접 요청)의 200 을 404 로 바꾼다. 요청 uri 가 바뀐 값이든 원래 값이든 같은 답이 나오게 판단한다.
//
// 함수는 오브젝트가 있는지 알 수 없으므로 페이지 목록을 둔다. = frontend/src/app 의 page.tsx 목록(scripts/test/cf_function_test.mjs 가 같은지 검사한다).
// 페이지를 더하면 여기도 더하고 terraform apply 해야 한다(안 하면 새 페이지가 404).
// custom_error_response(403/404 → /404.html)는 쓰지 않는다: 배포 전체에 걸려 /api/* 의 404·403 JSON 까지 HTML 로 덮는다(TASK-34#B1).
var PAGES = [
  "/",
  "/account/",
  "/admin/",
  "/auth/callback/",
  "/login/",
  "/posts/edit/",
  "/posts/new/",
  "/posts/view/",
  "/signup/",
];
var NOT_FOUND = "/404.html";

// 이 함수는 /api/*·/uploads/* 동작에 붙지 않지만, 잘못 붙더라도 API·첨부 응답은 건드리지 않는다(이중 방어, TASK-34#B1).
function passThrough(uri) {
  return uri === "/api" || uri.startsWith("/api/") || uri.startsWith("/uploads/");
}

// 페이지 경로면 "/x/" 꼴로, 파일이면 null. ".../index.html" 은 그 디렉터리 페이지로 본다.
function pageOf(uri) {
  if (uri.endsWith("/")) return uri;
  var slash = uri.lastIndexOf("/");
  var last = uri.substring(slash + 1);
  if (last === "index.html") return uri.substring(0, slash + 1);
  if (last.indexOf(".") !== -1) return null;
  return uri + "/";
}

function isUnknownPage(uri) {
  var page = pageOf(uri);
  return page !== null && PAGES.indexOf(page) === -1;
}

function handler(event) {
  var request = event.request;
  var uri = request.uri;

  if (event.context.eventType === "viewer-response") {
    var response = event.response;
    if (!passThrough(uri) && response.statusCode === 200 && (uri === NOT_FOUND || isUnknownPage(uri))) {
      response.statusCode = 404;
      response.statusDescription = "Not Found";
    }
    return response;
  }

  if (passThrough(uri)) return request;
  var page = pageOf(uri);
  if (page !== null) {
    request.uri = PAGES.indexOf(page) === -1 ? NOT_FOUND : page + "index.html";
  }
  return request;
}
