#!/usr/bin/env bash
# Playwright E2E (TASK-18#5): 실제 로컬 스택을 띄워 frontend/e2e 시나리오를 돌리고 내린다. make e2e 가 부른다(make test 에는 없다 — 느리다).
#   1) PostgreSQL + MinIO: docker compose 별도 프로젝트(board-e2e, 포트 55432·59000) — 개발용 컨테이너와 안 겹치고, 매번 빈 DB
#   2) 백엔드: bootJar → java -jar (:18080, SMS_PROVIDER=console, ADMIN_EMAIL). 로그는 frontend/e2e/artifacts/backend.log
#      (휴대폰 OTP 시나리오가 이 로그의 "[SMS console] ... 인증번호 NNNNNN" 줄에서 코드를 읽는다)
#      관리자: ADMIN_EMAIL 로 API 가입 → 백엔드 재시작(기동 시 승격, TASK-21#3). 시나리오는 그 계정으로 로그인한다
#   3) 프론트: next dev (:3100, /api → :18080 프록시). 같은 frontend 디렉터리의 next dev 는 하나만 뜬다 — make frontend-dev 는 먼저 끈다
#   4) npx playwright test (chromium) → 결과·스크린샷 frontend/e2e/artifacts/ (gitignore)
#   끝나면(성공·실패 모두) 프로세스를 끄고 compose down -v. 백엔드·next dev 는 각자 프로세스 그룹으로 띄워 그 그룹만
#   TERM → E2E_STOP_TIMEOUT(기본 10초) 안에 안 끝나면 KILL 한다(scripts/_proc.sh, TASK-35#2). 포트로 찾아 죽이지 않는다.
# 인자는 playwright test 에 그대로 넘긴다. 예: make e2e E2E_ARGS="--headed"   ./scripts/e2e.sh -g "OTP"
# 선택: E2E_KEEP=1 이면 끝나도 스택을 내리지 않는다(디버깅). 남긴 프로세스 그룹은 frontend/e2e/artifacts/kept-pids 에 적고,
#   다음 실행이 시작할 때 그 그룹(시작 시각이 같은 것만)을 끄고 compose down -v 한 뒤 진행한다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND="$ROOT/frontend"
ART="$FRONTEND/e2e/artifacts"
KEPT="$ART/kept-pids"
# shellcheck source=scripts/_proc.sh
source "$ROOT/scripts/_proc.sh"
PROC_STOP_TIMEOUT="${E2E_STOP_TIMEOUT:-10}"

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
export E2E_ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD:-e2e-admin-1234}"
export E2E_ADMIN_NICKNAME="${E2E_ADMIN_NICKNAME:-관리자e2e}"
export E2E_BACKEND_LOG="$ART/backend.log"
# MinIO CORS(MINIO_API_CORS_ALLOW_ORIGIN)와 OAuth redirect 기준 주소
export PUBLIC_BASE_URL="$E2E_BASE_URL"

log() { echo "==> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

compose() { docker compose -p board-e2e -f "$ROOT/docker-compose.yml" -f "$FRONTEND/e2e/docker-compose.e2e.yml" "$@"; }

# TASK-36#6 로컬 k3d 클러스터가 떠 있으면 먼저 멈춘다(compose·백엔드·next dev 와 동시에 띄우지 않는다 — 8GB)
"$ROOT/scripts/k3d.sh" stop-if-running "make e2e"

# 이전 E2E_KEEP=1 실행이 남긴 스택
if [ -f "$KEPT" ]; then
  log "이전 E2E_KEEP=1 실행이 남긴 스택을 정리한다 ($KEPT)"
  proc_stop_recorded "$KEPT"
  compose down -v --remove-orphans >/dev/null 2>&1 || true
fi

port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
for p in "$E2E_WEB_PORT" "$E2E_API_PORT" "$POSTGRES_PORT" "$MINIO_PORT" "$MINIO_CONSOLE_PORT"; do
  port_busy "$p" && die "포트 $p 를 이미 쓰고 있다(이전 e2e 가 남았으면 docker compose -p board-e2e down -v)"
done
# node_modules 가 없거나 lock 이 바뀌었으면 npm ci (TASK-35#3)
"$ROOT/scripts/frontend-deps.sh"

rm -rf "$ART"
mkdir -p "$ART"
cleanup() {
  local rc=$?
  if [ "${E2E_KEEP:-0}" = 1 ]; then
    proc_record "$KEPT"
    log "E2E_KEEP=1: 스택을 남긴다 (프로세스 그룹: ${PROC_PIDS[*]:-} → $KEPT, 다음 실행이 정리한다)"
    exit "$rc"
  fi
  log "정리"
  # 이 실행이 띄운 프로세스 그룹만 끈다(next dev 가 띄운 하위 서버도 같은 그룹이다). TERM → 시간 초과 시 KILL
  proc_stop_all
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

# 백엔드를 띄운다. 로그는 이어 쓴다(관리자 시드 뒤 재시작해도 OTP 시나리오가 같은 파일을 읽는다).
start_backend() {
  log "백엔드 :$E2E_API_PORT (SMS_PROVIDER=console, ADMIN_EMAIL=$E2E_ADMIN_EMAIL) → $E2E_BACKEND_LOG"
  # 셸에 남은 값이 섞이지 않게 앱 설정은 여기서 모두 정한다(env -i). 시크릿은 E2E 전용 더미 값이다.
  # PUBLIC_BASE_URL 이 http 라 운영 모드가 아니다(운영 안전 검사·OTP 로그 숨김이 꺼진다, TASK-21).
  proc_start env -i PATH="$PATH" HOME="$HOME" ${JAVA_HOME:+JAVA_HOME="$JAVA_HOME"} \
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
    java -jar "$jar" >>"$E2E_BACKEND_LOG" 2>&1
  backend_pid=$PROC_LAST
}

start_backend
wait_http "백엔드" "http://localhost:$E2E_API_PORT/api/actuator/health" 180

# 첫 ADMIN (TASK-21#3): 가입만으로는 ADMIN 이 되지 않고 기동 시 이미 있는 계정만 올린다. 운영 절차(관리자 가입 → 확인 → 재시작)
# 그대로 ADMIN_EMAIL 로 가입한 뒤 백엔드를 다시 띄운다. 시나리오는 이 계정으로 로그인한다.
log "관리자 시드: $E2E_ADMIN_EMAIL 가입 → 백엔드 재시작"
# 가입은 CSRF 를 요구한다(SPA 와 같이 XSRF-TOKEN 쿠키 값을 헤더로 보낸다)
cookies="$ART/admin-cookies.txt"
curl -fsS -o /dev/null -c "$cookies" "http://localhost:$E2E_API_PORT/api/auth/csrf"
xsrf="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$cookies")"
[ -n "$xsrf" ] || die "XSRF-TOKEN 쿠키를 받지 못했다"
code=$(curl -sS -o "$ART/admin-signup.json" -w '%{http_code}' -b "$cookies" -X POST "http://localhost:$E2E_API_PORT/api/auth/signup" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $xsrf" \
  --data "{\"email\":\"$E2E_ADMIN_EMAIL\",\"password\":\"$E2E_ADMIN_PASSWORD\",\"nickname\":\"$E2E_ADMIN_NICKNAME\"}")
[ "$code" = 201 ] || [ "$code" = 409 ] || die "관리자 가입 실패: HTTP $code ($(cat "$ART/admin-signup.json"))"
proc_stop "$backend_pid"
start_backend

log "프론트 next dev :$E2E_WEB_PORT → $ART/next.log"
run_next_dev() {
  cd "$FRONTEND" && BACKEND_URL="http://localhost:$E2E_API_PORT" NEXT_TELEMETRY_DISABLED=1 \
    exec node node_modules/next/dist/bin/next dev -p "$E2E_WEB_PORT"
}
proc_start run_next_dev >"$ART/next.log" 2>&1

wait_http "백엔드(재시작)" "http://localhost:$E2E_API_PORT/api/actuator/health" 180
# 승격은 ApplicationRunner 라 헬스가 UP 이 된 뒤에 돌 수 있다 — 30초까지 기다린다
promoted=0
for _ in $(seq 1 30); do
  if grep -q "ADMIN 으로 올렸다" "$E2E_BACKEND_LOG"; then promoted=1; break; fi
  sleep 1
done
[ "$promoted" = 1 ] || die "재시작 뒤 30초 안에 관리자 승격 로그가 없다 ($E2E_BACKEND_LOG)"
wait_http "프론트" "$E2E_BASE_URL/" 180
# 첫 방문 컴파일(next dev)을 미리 해 둬서 테스트 타임아웃에 걸리지 않게 한다
for path in /login/ /signup/ /posts/new/ "/posts/view/?id=1" "/posts/edit/?id=1" /admin/ /account/ /auth/callback/; do
  curl -fsS -o /dev/null --max-time 120 "$E2E_BASE_URL$path" || log "warm-up 실패(무시): $path"
done
wait_http "프록시(/api)" "$E2E_BASE_URL/api/actuator/health" 30

log "playwright test"
cd "$FRONTEND"
npx playwright test "$@"
