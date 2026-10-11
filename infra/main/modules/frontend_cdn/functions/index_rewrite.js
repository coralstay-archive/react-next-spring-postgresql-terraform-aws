// CloudFront Function (viewer-request, cloudfront-js-2.0). 기본 동작(web S3 버킷)에만 붙는다 — /api/*, /uploads/* 에는 붙이지 않는다.
// Next static export(trailingSlash)는 페이지를 <경로>/index.html 로 만든다. OAC(REST) 오리진은 하위 index 를 붙여 주지 않으므로 여기서 붙인다.
//   /login/  → /login/index.html
//   /login   → /login/index.html   (마지막 경로 조각에 '.' 이 없으면 페이지로 본다)
//   /_next/static/x.js, /404.html → 그대로
// 쿼리 문자열(/posts/view/?id=1)은 건드리지 않는다.
function handler(event) {
  var request = event.request;
  var uri = request.uri;

  if (uri.endsWith("/")) {
    request.uri = uri + "index.html";
  } else {
    var last = uri.substring(uri.lastIndexOf("/") + 1);
    if (last.indexOf(".") === -1) {
      request.uri = uri + "/index.html";
    }
  }
  return request;
}
