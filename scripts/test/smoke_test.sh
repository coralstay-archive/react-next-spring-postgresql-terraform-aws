#!/usr/bin/env bash
# 배포 스모크 테스트 (TASK-18#1). 배포된 환경(CloudFront 주소)을 읽기 위주로 확인한다.
#   SMOKE_BASE_URL=https://dxxxx.cloudfront.net make smoke
# SMOKE_BASE_URL 이 없으면 SKIP 을 출력하고 0 으로 끝난다(make test 에서는 deploy_test.sh 가 가짜 서버로 이 스크립트를 검증한다).
#
# TASK-18#1 확인 항목:
#   1) GET /api/actuator/health → 200 + "status":"UP"            (CloudFront /api/* → EC2 → Spring)
#   2) GET /                      → 200 HTML + Cache-Control no-cache (deploy-frontend 의 HTML 캐시 헤더)
#   3) / 가 참조하는 /_next/static/* 자산 → 200 + Cache-Control immutable
#   4) GET /api/auth/csrf         → Set-Cookie XSRF-TOKEN          (쿠키가 CloudFront 를 통과한다)
#   5) GET /api/posts             → 200 JSON(items)                (DB 까지 왕복)
# 선택: SMOKE_TIMEOUT(초, 기본 20)
set -uo pipefail

base="${SMOKE_BASE_URL:-}"
base="${base%/}"
if [ -z "$base" ]; then
  echo "SKIP - SMOKE_BASE_URL 이 없다 (예: SMOKE_BASE_URL=https://dxxxx.cloudfront.net make smoke)"
  exit 0
fi
timeout="${SMOKE_TIMEOUT:-20}"

failures=0
passes=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok() { passes=$((passes + 1)); echo "  ok   - $1"; }
fail() {
  failures=$((failures + 1)); echo "  FAIL - $1"
  shift
  [ $# -gt 0 ] && printf '%s\n' "$@" | sed 's/^/         | /'
  return 0
}

# fetch <이름> <경로> — 헤더는 $TMP/<이름>.h, 본문은 $TMP/<이름>.b, 상태 코드는 표준출력
fetch() {
  curl -sS --max-time "$timeout" -o "$TMP/$1.b" -D "$TMP/$1.h" -w '%{http_code}' "$base$2" 2>"$TMP/$1.err" || true
}
# header <이름> <헤더 이름> — 마지막 응답의 헤더 값(대소문자 무시, 여러 줄이면 모두)
header() { grep -i "^$2:" "$TMP/$1.h" | sed -E "s/^[^:]+:[[:space:]]*//" | tr -d '\r'; }

echo "smoke: $base"

# 1) health
code="$(fetch health /api/actuator/health)"
if [ "$code" = 200 ] && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$TMP/health.b"; then
  ok "TASK-18#1 GET /api/actuator/health → 200 UP"
else
  fail "TASK-18#1 GET /api/actuator/health → 200 UP" "status=$code" "$(head -c 300 "$TMP/health.b" 2>/dev/null)" "$(cat "$TMP/health.err")"
fi

# 2) index HTML
code="$(fetch index /)"
ctype="$(header index content-type)"
cache="$(header index cache-control)"
if [ "$code" = 200 ] && grep -qi 'text/html' <<<"$ctype" && grep -qi 'no-cache' <<<"$cache"; then
  ok "TASK-18#1 GET / → 200 HTML + Cache-Control no-cache"
else
  fail "TASK-18#1 GET / → 200 HTML + Cache-Control no-cache" "status=$code content-type=$ctype cache-control=$cache"
fi

# 3) 해시가 붙은 정적 자산
asset="$(grep -Eo '/_next/static/[^"'"'"' )]+\.(js|css)' "$TMP/index.b" 2>/dev/null | head -n 1)"
if [ -z "$asset" ]; then
  fail "TASK-18#1 /_next/static 자산 → 200 + immutable" "/ 의 HTML 에서 /_next/static/*.js|css 참조를 찾지 못했다"
else
  code="$(fetch asset "$asset")"
  cache="$(header asset cache-control)"
  if [ "$code" = 200 ] && grep -qi 'immutable' <<<"$cache"; then
    ok "TASK-18#1 $asset → 200 + immutable"
  else
    fail "TASK-18#1 /_next/static 자산 → 200 + immutable" "$asset status=$code cache-control=$cache"
  fi
fi

# 4) CSRF 쿠키
code="$(fetch csrf /api/auth/csrf)"
if [ "$code" = 200 ] && header csrf set-cookie | grep -q '^XSRF-TOKEN='; then
  ok "TASK-18#1 GET /api/auth/csrf → Set-Cookie XSRF-TOKEN"
else
  fail "TASK-18#1 GET /api/auth/csrf → Set-Cookie XSRF-TOKEN" "status=$code set-cookie=$(header csrf set-cookie | cut -d= -f1 | tr '\n' ' ')"
fi

# 5) 글 목록 JSON
code="$(fetch posts /api/posts)"
ctype="$(header posts content-type)"
if [ "$code" = 200 ] && grep -qi 'application/json' <<<"$ctype" && grep -q '"items"' "$TMP/posts.b"; then
  ok "TASK-18#1 GET /api/posts → 200 JSON"
else
  fail "TASK-18#1 GET /api/posts → 200 JSON" "status=$code content-type=$ctype" "$(head -c 300 "$TMP/posts.b" 2>/dev/null)"
fi

echo "smoke: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
