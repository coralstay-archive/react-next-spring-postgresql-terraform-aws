#!/usr/bin/env bash
# Playwright E2E (TASK-18#5): 실제 로컬 스택을 띄워 frontend/e2e 시나리오를 돌리고 내린다. make e2e 가 부른다(make test 에는 없다 — 느리다).
#   1) PostgreSQL + MinIO: docker compose 별도 프로젝트(board-e2e, 포트 55432·59000) — 개발용 컨테이너와 안 겹치고, 매번 빈 DB
#   2) 백엔드: bootJar → java -jar (:18080, SMS_PROVIDER=console, ADMIN_EMAIL). 로그는 frontend/e2e/artifacts/backend.log
#      (휴대폰 OTP 시나리오가 이 로그의 "[SMS console] ... 인증번호 NNNNNN" 줄에서 코드를 읽는다)
#   3) 프론트: next dev (:3100, /api → :18080 프록시). 같은 frontend 디렉터리의 next dev 는 하나만 뜬다 — make frontend-dev 는 먼저 끈다
#   4) npx playwright test (chromium) → 결과·스크린샷 frontend/e2e/artifacts/ (gitignore)
#   끝나면(성공·실패 모두) 프로세스를 끄고 compose down -v.
# 인자는 playwright test 에 그대로 넘긴다. 예: make e2e E2E_ARGS="--headed"   ./scripts/e2e.sh -g "OTP"
# 선택: E2E_KEEP=1 이면 끝나도 스택을 내리지 않는다(디버깅. 다음 실행 전에 docker compose -p board-e2e down -v).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND="$ROOT/frontend"
ART="$FRONTEND/e2e/artifacts"

E2E_WEB_PORT="${E2E_WEB_PORT:-3100}"
E2E_API_PORT="${E2E_API_PORT:-18080}"
export POSTGRES_PORT="${E2E_POSTGRES_PORT:-55432}"
export MINIO_PORT="${E2E_MINIO_PORT:-59000}"
export MINIO_CONSOLE_PORT="${E2E_MINIO_CONSOLE_PORT:-59001}"
export POSTGRES_DB=board POSTGRES_USER=board POSTGRES_PASSWORD=board
export MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin
export UPLOADS_BUCKET=board-uploads
export E2E_BASE_URL="http://localhost:$E2E_WEB_PORT"
export E2E_ADMIN_EMAIL="${E2E_ADMIN_EMAIL:-admin-e2e@example.com}"
export E2E_BACKEND_LOG="$ART/backend.log"
# MinIO CORS(MINIO_API_CORS_ALLOW_ORIGIN)와 OAuth redirect 기준 주소
export PUBLIC_BASE_URL="$E2E_BASE_URL"

log() { echo "==> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

compose() { docker compose -p board-e2e -f "$ROOT/docker-compose.yml" -f "$FRONTEND/e2e/docker-compose.e2e.yml" "$@"; }

port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
for p in "$E2E_WEB_PORT" "$E2E_API_PORT" "$POSTGRES_PORT" "$MINIO_PORT" "$MINIO_CONSOLE_PORT"; do
  port_busy "$p" && die "포트 $p 를 이미 쓰고 있다(이전 e2e 가 남았으면 docker compose -p board-e2e down -v)"
done
[ -d "$FRONTEND/node_modules/@playwright/test" ] || die "frontend 의존성이 없다: make frontend-install"

rm -rf "$ART"
mkdir -p "$ART"
pids=()
cleanup() {
  local rc=$?
  if [ "${E2E_KEEP:-0}" = 1 ]; then
    log "E2E_KEEP=1: 스택을 남긴다 (pids: ${pids[*]:-})"
    exit "$rc"
  fi
  log "정리"
  local pid
  for pid in "${pids[@]:-}"; do
    [ -n "$pid" ] || continue
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
  done
  sleep 1
  # next dev 가 띄운 하위 서버 등 남은 리스너 (시작할 때 비어 있던 포트라 이 실행이 띄운 것이다)
  for p in "$E2E_WEB_PORT" "$E2E_API_PORT"; do
    lsof -nP -t -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | xargs kill -TERM 2>/dev/null || true
  done
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# wait_http <이름> <url> <초>
wait_http() {
  local name="$1" url="$2" limit="$3" start
  start=$(date +%s)
  until curl -fsS -o /dev/null --max-time 60 "$url" 2>/dev/null; do
    [ $(( $(date +%s) - start )) -lt "$limit" ] || die "$name 이 ${limit}초 안에 뜨지 않았다: $url (로그: $ART)"
    sleep 2
  done
  log "$name ok ($(( $(date +%s) - start ))s)"
}

log "PostgreSQL + MinIO (board-e2e, :$POSTGRES_PORT, :$MINIO_PORT)"
compose up -d --wait postgres minio
compose run --rm minio-init >/dev/null

log "백엔드 빌드 (bootJar)"
(cd "$ROOT/backend" && ./gradlew bootJar --console=plain -q)
jar="$(find "$ROOT/backend/build/libs" -name '*.jar' ! -name '*-plain.jar' | head -n 1)"
[ -n "$jar" ] || die "bootJar 결과가 없다"

log "백엔드 :$E2E_API_PORT (SMS_PROVIDER=console, ADMIN_EMAIL=$E2E_ADMIN_EMAIL) → $E2E_BACKEND_LOG"
# 로컬 env 파일(Makefile 이 export)의 값이 섞이지 않게 앱 설정은 여기서 모두 정한다. 시크릿은 E2E 전용 더미 값이다.
env -i PATH="$PATH" HOME="$HOME" ${JAVA_HOME:+JAVA_HOME="$JAVA_HOME"} \
  SERVER_PORT="$E2E_API_PORT" \
  DB_URL="jdbc:postgresql://localhost:$POSTGRES_PORT/board" DB_USERNAME=board DB_PASSWORD=board \
  JWT_SECRET="e2e-only-jwt-signing-key-0123456789abcdef" \
  OTP_HASH_KEY="e2e-only-otp-hash-key-0123456789abcdef0123" \
  COOKIE_SECURE=false \
  ADMIN_EMAIL="$E2E_ADMIN_EMAIL" \
  PUBLIC_BASE_URL="$PUBLIC_BASE_URL" \
  SMS_PROVIDER=console SMS_DEFAULT_REGION=KR \
  UPLOADS_BUCKET="$UPLOADS_BUCKET" UPLOADS_REGION=us-east-1 \
  UPLOADS_ENDPOINT="http://localhost:$MINIO_PORT" UPLOADS_PATH_STYLE=true \
  UPLOADS_ACCESS_KEY=minioadmin UPLOADS_SECRET_KEY=minioadmin \
  UPLOADS_PUBLIC_BASE_PATH="http://localhost:$MINIO_PORT/$UPLOADS_BUCKET/uploads/" \
  java -jar "$jar" >"$E2E_BACKEND_LOG" 2>&1 &
pids+=($!)

log "프론트 next dev :$E2E_WEB_PORT → $ART/next.log"
(cd "$FRONTEND" && BACKEND_URL="http://localhost:$E2E_API_PORT" NEXT_TELEMETRY_DISABLED=1 \
  exec node node_modules/next/dist/bin/next dev -p "$E2E_WEB_PORT") >"$ART/next.log" 2>&1 &
pids+=($!)

wait_http "백엔드" "http://localhost:$E2E_API_PORT/api/actuator/health" 180
wait_http "프론트" "$E2E_BASE_URL/" 180
# 첫 방문 컴파일(next dev)을 미리 해 둬서 테스트 타임아웃에 걸리지 않게 한다
for path in /login/ /signup/ /posts/new/ "/posts/view/?id=1" "/posts/edit/?id=1" /admin/ /account/ /auth/callback/; do
  curl -fsS -o /dev/null --max-time 120 "$E2E_BASE_URL$path" || log "warm-up 실패(무시): $path"
done
wait_http "프록시(/api)" "$E2E_BASE_URL/api/actuator/health" 30

log "playwright test"
cd "$FRONTEND"
npx playwright test "$@"
