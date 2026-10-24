#!/usr/bin/env bash
# shellcheck disable=SC2016 # sh -c '...' 안의 $ 는 하위 셸에서 풀린다
# 로컬 k3d 플랫폼 테스트 (TASK-36). scripts/test.sh 가 호출한다.
#   - 매니페스트: kubectl kustomize 렌더 + kubeconform 오프라인 검증(scripts/k8s-validate.sh), 모든 컨테이너의 requests/limits,
#     프로필 예산(deploy/k8s/budgets.txt) ≥ 매니페스트의 메모리 한도 합계, Flyway 마이그레이션 Job
#   - 절차 강제: scripts/k3d.sh 의 사전 검사(Docker VM 메모리·Testcontainers·예산), make test·test-integration 의 k3d 자동 정지
# 실제 클러스터·Docker 는 부르지 않는다(PATH 앞에 둔 가짜 docker·k3d·kubectl·curl). 렌더 검사는 진짜 kubectl(kustomize 내장)을 쓰고,
# kubectl·kubeconform 이 없으면 경고 후 건너뛴다(REQUIRE_LINTERS=1 이면 실패 — CI).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

failures=0
passes=0
OUT=""
RC=0

# check <이름> <명령...> — 명령 출력은 실패할 때만 보인다
check() {
  local name="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then
    passes=$((passes + 1)); echo "  ok   - $name"
  else
    failures=$((failures + 1)); echo "  FAIL - $name"
    printf '%s\n' "$out" | sed 's/^/         | /' | tail -40
  fi
}
run_capture() { OUT="$("$@" 2>&1)"; RC=$?; }
has_text() { grep -qF -- "$1" <<<"$OUT" || { echo "missing text: $1"; echo "--- output:"; echo "$OUT"; return 1; }; }
lacks_text() { ! grep -qF -- "$1" <<<"$OUT" || { echo "unexpected text: $1"; echo "--- output:"; echo "$OUT"; return 1; }; }
rc_is() { [ "$RC" = "$1" ] || { echo "rc=$RC (기대 $1)"; echo "$OUT"; return 1; }; }
need_tool() {
  if command -v "$1" >/dev/null 2>&1; then return 0; fi
  if [ "${REQUIRE_LINTERS:-0}" = 1 ]; then echo "  FAIL - $1 이 없다(REQUIRE_LINTERS=1)"; failures=$((failures + 1)); else echo "  WARNING: $1 이 없어 건너뛴다" >&2; fi
  return 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "k8s tests"

# ---------------------------------------------------------------- 가짜 명령 (PATH 앞에 둔다)
FAKE="$TMP/fake"
mkdir -p "$FAKE/bin" "$FAKE/state"
export FAKE_STATE="$FAKE/state"
REAL_KUBECTL="$(command -v kubectl || true)"
export REAL_KUBECTL

# 가짜 docker: info → FAKE_DOCKER_MEM(바이트, 기본 5GB 설정의 실측 ≈ 4.8GiB), ps(Testcontainers 라벨) → FAKE_TC 의 id 들,
# ps(label=k3d.cluster=…) → FAKE_K3D_CONTAINERS 의 id 들, stop → FAKE_DOCKER_STOP_FAIL=1 이면 실패
cat >"$FAKE/bin/docker" <<'SH'
#!/usr/bin/env bash
set -u
printf 'docker %s\n' "$*" >>"$FAKE_STATE/calls.log"
case "${1:-}" in
  info) [ "${FAKE_DOCKER_DOWN:-0}" = 1 ] && { echo "Cannot connect to the Docker daemon" >&2; exit 1; }
        echo "${FAKE_DOCKER_MEM:-5150000000}" ;;
  ps) case " $* " in
        *"label=k3d.cluster="*) for id in ${FAKE_K3D_CONTAINERS:-}; do echo "$id"; done ;;
        *) for id in ${FAKE_TC:-}; do echo "$id"; done ;;
      esac ;;
  stop) if [ "${FAKE_DOCKER_STOP_FAIL:-0}" = 1 ]; then echo "Error response from daemon: fake stop failed" >&2; exit 1; fi ;;
  *) : ;;
esac
SH

# 가짜 k3d: 클러스터 상태를 state/k3d 파일(running|stopped|없음)로 흉내 낸다
cat >"$FAKE/bin/k3d" <<'SH'
#!/usr/bin/env bash
set -u
S="$FAKE_STATE"
printf 'k3d %s\n' "$*" >>"$S/calls.log"
st="$(cat "$S/k3d" 2>/dev/null || true)"
case "${1:-} ${2:-}" in
  "cluster list")
    case "$st" in running) echo "board   1/1   0/0   true" ;; stopped) echo "board   0/1   0/0   true" ;; esac ;;
  "cluster create") echo running >"$S/k3d" ;;
  "cluster start") echo running >"$S/k3d" ;;
  "cluster stop") [ -n "$st" ] && echo stopped >"$S/k3d" ;;
  "cluster delete") rm -f "$S/k3d" ;;
esac
SH

# 가짜 kubectl: 호출을 남긴다. get job(jsonpath) → FAKE_JOB_STATUS("<ns>/<이름> <참인 조건...>", 기본 Complete), logs job/ → 가짜 로그, get -o name → backend 하나, get(-l board.dev/profile, jsonpath) → FAKE_ACTIVE_PROFILES, create secret --from-env-file → 내용 복사
# (FAKE_KUBECTL_FAIL_SECRET=1 이면 읽은 뒤 실패, FAKE_REAL_SECRET=1 이면 진짜 kubectl 의 --dry-run=client 로 만든다), apply -f - → stdin 저장
cat >"$FAKE/bin/kubectl" <<'SH'
#!/usr/bin/env bash
set -u
S="$FAKE_STATE"
printf 'kubectl %s\n' "$*" >>"$S/calls.log"
args=" $* "
case "$args" in
  *" get job "*" -o name "*) [ "${FAKE_NO_JOBS:-0}" = 1 ] || echo "job.batch/board-migrate" ;;
  *" get job "*"jsonpath="*) [ "${FAKE_NO_JOBS:-0}" = 1 ] || echo "${FAKE_JOB_STATUS-board/board-migrate Complete }" ;;
  *" logs job/"*) echo "FAKE-JOB-LOG: flyway 마이그레이션 실패" ;;
  *" get "*" -o name "*) echo "deployment.apps/board-backend" ;;
  *" get "*"board.dev/profile"*) for p in ${FAKE_ACTIVE_PROFILES:-}; do echo "$p"; done ;;
  *" create secret "*)
    for a in "$@"; do case "$a" in --from-env-file=*) cat "${a#--from-env-file=}" >"$S/secret.env" ;; esac; done
    [ "${FAKE_KUBECTL_FAIL_SECRET:-0}" = 1 ] && { echo "error: fake create secret failed" >&2; exit 1; }
    if [ "${FAKE_REAL_SECRET:-0}" = 1 ]; then
      KUBECONFIG=/nonexistent "$REAL_KUBECTL" -n board create secret generic board-env --from-env-file="$S/secret.env" --dry-run=client -o yaml
      exit $?
    fi
    echo "kind: Secret" ;;
  *" apply -f - "*) cat >>"$S/applied.yaml" ;;
esac
exit 0
SH

# 가짜 curl: 인자를 남기고 FAKE_HEALTH_BODY 를 출력한다
cat >"$FAKE/bin/curl" <<'SH'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >>"$FAKE_STATE/calls.log"
printf '%s' "${FAKE_HEALTH_BODY-{\"status\":\"UP\"\}}"
SH
chmod +x "$FAKE/bin/"*

fake_reset() { rm -rf "$FAKE_STATE"; mkdir -p "$FAKE_STATE"; : >"$FAKE_STATE/calls.log"; }
calls() { cat "$FAKE_STATE/calls.log"; }
# k3d <인자...> — 가짜 명령과 함께 scripts/k3d.sh 실행. 개발자 환경의 FORCE·PROFILES·env 파일 값이 섞이지 않게 비운다
k3d_run() {
  env -u FORCE -u PROFILES -u SKIP_BUILD -u K3D_BUDGETS_FILE -u K3D_PROFILES_DIR -u POSTGRES_PASSWORD -u DB_URL \
    -u POSTGRES_DB -u POSTGRES_USER -u MINIO_ROOT_USER -u MINIO_ROOT_PASSWORD -u JWT_SECRET -u OTP_HASH_KEY \
    SMOKE_TIMEOUT=0 K3D_WAIT_TIMEOUT=1s PATH="$FAKE/bin:$PATH" "$@"
}
# 호출 기록에 줄(부분 문자열)이 있는지/없는지
called() { grep -qF -- "$1" "$FAKE_STATE/calls.log" || { echo "호출 없음: $1"; calls; return 1; }; }
not_called() { ! grep -qF -- "$1" "$FAKE_STATE/calls.log" || { echo "호출되면 안 됨: $1"; calls; return 1; }; }
# 호출 순서: a 가 b 보다 먼저
called_before() {
  local a b
  a="$(grep -nF -- "$1" "$FAKE_STATE/calls.log" | head -1 | cut -d: -f1)"
  b="$(grep -nF -- "$2" "$FAKE_STATE/calls.log" | head -1 | cut -d: -f1)"
  [ -n "$a" ] && [ -n "$b" ] && [ "$a" -lt "$b" ] || { echo "순서: '$1'($a) → '$2'($b)"; calls; return 1; }
}

GIB=1073741824
MEM_38=$((38 * GIB / 10))   # Docker Desktop 4GB 설정의 실측(≈ 3.8GiB)

# 예산 초과를 만들 임시 예산 파일·프로필 디렉터리(core + big)
BUD="$TMP/budgets.txt"
cat >"$BUD" <<'TXT'
docker-min 4608 Docker Desktop 5GB
limit 3072 프로필 합계 상한
profile core 1600 Postgres·MinIO·backend
profile big 2000 테스트용 큰 프로필
TXT
PDIR="$TMP/profiles"
mkdir -p "$PDIR/core" "$PDIR/big"

# ================================================================ TASK-36#7 메모리 절차 강제(2): 사전 검사 실패면 이유·해결법을 출력하고 실패
t_up_low_mem() {
  fake_reset
  run_capture k3d_run FAKE_DOCKER_MEM="$MEM_38" ./scripts/k3d.sh up
  rc_is 1 && has_text "Docker VM 메모리" && has_text "5GB" && has_text "Resources" \
    && not_called "k3d cluster create" && not_called "docker build" && not_called "kubectl apply"
}
check "TASK-36#7 make k3d-up: Docker VM 메모리 5GB 미만(3.8GiB)이면 이유·해결법을 출력하고 실패, 아무것도 띄우지 않는다" t_up_low_mem
t_up_tc() {
  fake_reset
  run_capture k3d_run FAKE_TC="abc123 def456" ./scripts/k3d.sh up
  rc_is 1 && has_text "Testcontainers" && has_text "2" && has_text "org.testcontainers=true" \
    && not_called "k3d cluster create" && not_called "docker build"
}
check "TASK-36#7 make k3d-up: Testcontainers 컨테이너(label org.testcontainers=true)가 떠 있으면 실패(이유·해결법)" t_up_tc
t_up_budget() {
  fake_reset
  run_capture k3d_run K3D_BUDGETS_FILE="$BUD" K3D_PROFILES_DIR="$PDIR" PROFILES=core,big ./scripts/k3d.sh up
  rc_is 1 && has_text "3600" && has_text "3072" && has_text "FORCE=1" && has_text "core 1600" && has_text "big 2000" \
    && not_called "k3d cluster create" && not_called "docker build"
}
check "TASK-36#7 TASK-36#B1 make k3d-up: 프로필 예산 합계(3600MiB)가 상한(3072MiB)을 넘으면 실패(내역·FORCE=1 안내)" t_up_budget
t_up_budget_force() {
  fake_reset
  run_capture k3d_run FORCE=1 SKIP_BUILD=1 K3D_BUDGETS_FILE="$BUD" K3D_PROFILES_DIR="$PDIR" PROFILES=core,big ./scripts/k3d.sh up
  rc_is 0 && has_text "WARNING" && has_text "3600" && called "k3d cluster create"
}
check "TASK-36#7 TASK-36#B1 FORCE=1 이면 예산 초과도 경고(WARNING)를 크게 남기고 진행한다" t_up_budget_force
t_force_not_mem() {
  fake_reset
  run_capture k3d_run FORCE=1 FAKE_DOCKER_MEM="$MEM_38" ./scripts/k3d.sh up
  rc_is 1 && has_text "Docker VM 메모리" && not_called "k3d cluster create"
  fake_reset
  run_capture k3d_run FORCE=1 FAKE_TC="abc" ./scripts/k3d.sh up
  rc_is 1 && has_text "Testcontainers" && not_called "k3d cluster create"
}
check "TASK-36#7 FORCE=1 은 예산 검사만 넘긴다(메모리 부족·Testcontainers 는 그대로 실패)" t_force_not_mem
t_all_reasons() {
  fake_reset
  run_capture k3d_run FAKE_DOCKER_MEM="$MEM_38" FAKE_TC="abc" K3D_BUDGETS_FILE="$BUD" K3D_PROFILES_DIR="$PDIR" PROFILES=core,big ./scripts/k3d.sh up
  rc_is 1 && has_text "Docker VM 메모리" && has_text "Testcontainers" && has_text "3600"
}
check "TASK-36#7 여러 이유가 겹치면 모두 출력한다" t_all_reasons
t_docker_down() {
  fake_reset
  run_capture k3d_run FAKE_DOCKER_DOWN=1 ./scripts/k3d.sh up
  rc_is 1 && has_text "Docker" && not_called "k3d cluster create"
}
check "TASK-36#7 Docker 가 응답하지 않으면 실패" t_docker_down
t_unknown_profile() {
  fake_reset
  run_capture k3d_run PROFILES=core,nope ./scripts/k3d.sh up
  rc_is 1 && has_text "nope" && has_text "budgets.txt" && not_called "k3d cluster create"
}
check "TASK-36#7 예산 파일에 없는 프로필은 거부한다(예산을 모르는 것은 띄우지 않는다)" t_unknown_profile
t_profile_checks() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run FAKE_DOCKER_MEM="$MEM_38" PROFILES=core ./scripts/k3d.sh profile
  rc_is 1 && has_text "Docker VM 메모리" && not_called "kubectl apply" || return 1
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run FAKE_TC="abc" PROFILES=core ./scripts/k3d.sh profile
  rc_is 1 && has_text "Testcontainers" && not_called "kubectl apply" || return 1
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run K3D_BUDGETS_FILE="$BUD" K3D_PROFILES_DIR="$PDIR" PROFILES=core,big ./scripts/k3d.sh profile
  rc_is 1 && has_text "FORCE=1" && not_called "kubectl apply"
}
check "TASK-36#7 make k3d-profile: 메모리 부족·Testcontainers·예산 초과 각각 실패" t_profile_checks
t_ok_budget_line() {
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 ./scripts/k3d.sh up
  rc_is 0 && has_text "메모리 예산" && has_text "core 1600" && has_text "3072" && lacks_text "WARNING"
}
check "TASK-36#7 통과하면 예산 내역(core 1600 / 상한 3072 MiB)을 출력하고 진행" t_ok_budget_line
t_make_targets_use_script() {
  local t
  for t in k3d-up k3d-profile k3d-smoke k3d-stop k3d-down; do
    grep -Eq "^$t:" Makefile || { echo "make $t 없음"; return 1; }
  done
  make -n k3d-up | grep -q 'scripts/k3d.sh up' && make -n k3d-profile PROFILES=core | grep -q 'scripts/k3d.sh profile' \
    && make -n k3d-smoke | grep -q 'scripts/k3d.sh smoke' && make -n k3d-stop | grep -q 'scripts/k3d.sh stop' \
    && make -n k3d-down | grep -q 'scripts/k3d.sh down'
}
check "TASK-36#7 make k3d-up·k3d-profile·k3d-smoke·k3d-stop·k3d-down 은 scripts/k3d.sh 를 거친다" t_make_targets_use_script

# ================================================================ TASK-36#8 스모크 스크립트도 같은 사전 검사 함수 — 우회하는 진입점이 없다
t_smoke_refuses() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run FAKE_TC="abc" FAKE_ACTIVE_PROFILES=core ./scripts/k3d.sh smoke
  rc_is 1 && has_text "Testcontainers" && not_called "curl" || return 1
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run FAKE_DOCKER_MEM="$MEM_38" FAKE_ACTIVE_PROFILES=core ./scripts/k3d.sh smoke
  rc_is 1 && has_text "Docker VM 메모리" && not_called "curl"
}
check "TASK-36#8 make k3d-smoke 도 같은 사전 검사로 실패한다(curl 전에)" t_smoke_refuses
t_smoke_budget_active() {
  # 지금 켜진 프로필(클러스터의 board.dev/profile 라벨)의 합계로 예산을 검사한다
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run K3D_BUDGETS_FILE="$BUD" K3D_PROFILES_DIR="$PDIR" FAKE_ACTIVE_PROFILES="core big" ./scripts/k3d.sh smoke
  rc_is 1 && has_text "3600" && not_called "curl"
}
check "TASK-36#8 TASK-36#B1 k3d-smoke 는 켜진 프로필 합계가 예산을 넘으면 실패한다" t_smoke_budget_active
t_entrypoints_share_preflight() {
  # 클러스터를 띄우거나 쓰는 하위 명령은 모두 k3d_preflight 를 부른다(같은 함수 하나 — scripts/_k3d.sh)
  local sub
  grep -q '^k3d_preflight()' scripts/_k3d.sh || { echo "_k3d.sh 에 k3d_preflight 없음"; return 1; }
  [ "$(grep -c '^k3d_preflight()' scripts/*.sh | awk -F: '{s+=$2} END {print s}')" = 1 ] || { echo "k3d_preflight 정의가 둘 이상"; return 1; }
  for sub in up profile smoke; do
    fake_reset; echo running >"$FAKE_STATE/k3d"
    run_capture k3d_run FAKE_TC="abc" FAKE_ACTIVE_PROFILES=core PROFILES=core ./scripts/k3d.sh "$sub"
    if ! { rc_is 1 && has_text "Testcontainers"; }; then echo "하위 명령 $sub 가 사전 검사를 건너뛴다"; return 1; fi
    [ "$(grep -cvE '^(docker info|docker ps|k3d cluster list|kubectl .* get )' "$FAKE_STATE/calls.log")" = 0 ] \
      || { echo "하위 명령 $sub 가 사전 검사 전에 다른 일을 했다"; calls; return 1; }
  done
}
check "TASK-36#8 up·profile·smoke 모두 같은 k3d_preflight 를 먼저 부르고, 실패하면 조회 외에 아무것도 하지 않는다" t_entrypoints_share_preflight
t_k8s_kubectl_context() {
  # 다른 클러스터를 건드리지 않게 kubectl 은 항상 k3d-board 컨텍스트로 부른다
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 ./scripts/k3d.sh up
  rc_is 0 && [ "$(grep '^kubectl ' "$FAKE_STATE/calls.log" | grep -vc -- '--context k3d-board')" = 0 ] || { calls; return 1; }
}
check "TASK-36#8 kubectl 호출은 모두 --context k3d-board(다른 클러스터를 건드리지 않는다)" t_k8s_kubectl_context

# ================================================================ TASK-36#6 메모리 절차 강제(1): make test·test-integration 은 시작할 때 떠 있는 k3d 를 멈춘다
t_stop_if_running() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run ./scripts/k3d.sh stop-if-running "make test"
  rc_is 0 && called "k3d cluster stop board" && has_text "k3d" && has_text "멈췄다" && has_text "make test"
}
check "TASK-36#6 k3d 클러스터가 떠 있으면 멈추고 그 사실을 출력한다" t_stop_if_running
t_stop_if_running_noop() {
  fake_reset; echo stopped >"$FAKE_STATE/k3d"
  run_capture k3d_run ./scripts/k3d.sh stop-if-running "make test"
  rc_is 0 && not_called "k3d cluster stop" || return 1
  fake_reset
  run_capture k3d_run ./scripts/k3d.sh stop-if-running "make test"
  rc_is 0 && not_called "k3d cluster stop"
}
check "TASK-36#6 멈춰 있거나 없으면 아무것도 하지 않는다" t_stop_if_running_noop
t_stop_if_running_no_k3d() {
  # k3d 가 없는 환경(CI)에서는 조용히 넘어간다. PATH 에 bash·coreutils 만 둔다
  local nb="$TMP/nok3d"; mkdir -p "$nb"
  local c; for c in bash env dirname cat grep awk sed tr; do ln -sf "$(command -v "$c")" "$nb/$c"; done
  run_capture env PATH="$nb" bash ./scripts/k3d.sh stop-if-running "make test"
  rc_is 0 && lacks_text "ERROR"
}
check "TASK-36#6 k3d 가 없으면(CI) 조용히 통과한다" t_stop_if_running_no_k3d
# k3d 가 PATH 에 없고 가짜 docker 만 있는 PATH
NOK3D_DOCKER="$TMP/nok3d-docker"; mkdir -p "$NOK3D_DOCKER"
for c in bash env dirname cat grep awk sed tr; do ln -sf "$(command -v "$c")" "$NOK3D_DOCKER/$c"; done
ln -sf "$FAKE/bin/docker" "$NOK3D_DOCKER/docker"
t_stop_if_running_no_k3d_docker() {
  # k3d 가 PATH 에 없어도(다른 셸·설치 경로) 클러스터 컨테이너가 떠 있으면 docker 라벨(k3d.cluster=board)로 찾아 멈춘다
  fake_reset
  run_capture env PATH="$NOK3D_DOCKER" FAKE_K3D_CONTAINERS="srv1 lb1" bash ./scripts/k3d.sh stop-if-running "make test"
  rc_is 0 && called "docker ps -q --filter label=k3d.cluster=board" && called "docker stop srv1 lb1" \
    && has_text "멈췄다" && has_text "make test" || return 1
  fake_reset
  run_capture env PATH="$NOK3D_DOCKER" bash ./scripts/k3d.sh stop-if-running "make test"
  rc_is 0 && not_called "docker stop" && lacks_text "멈췄다"
}
check "TASK-36#6 k3d 가 PATH 에 없어도 떠 있는 클러스터 컨테이너(label k3d.cluster=board)를 docker stop 으로 멈춘다" t_stop_if_running_no_k3d_docker
t_stop_if_running_no_k3d_docker_fail() {
  fake_reset
  run_capture env PATH="$NOK3D_DOCKER" FAKE_K3D_CONTAINERS="srv1" FAKE_DOCKER_STOP_FAIL=1 bash ./scripts/k3d.sh stop-if-running "make test"
  rc_is 1 && has_text "ERROR" && has_text "docker stop" && has_text "k3d.cluster=board"
}
check "TASK-36#6 docker stop 이 실패하면 분명한 메시지로 실패한다(클러스터를 띄운 채 테스트하지 않는다)" t_stop_if_running_no_k3d_docker_fail
# test.sh·test-integration.sh 를 임시 루트에 복사하고 다른 단계는 가짜로 바꿔 실행 순서를 본다
TR="$TMP/tr"
mkdir -p "$TR/scripts/test" "$TR/backend" "$TR/frontend" "$TR/infra" "$TR/bin"
cp scripts/test.sh scripts/test-integration.sh scripts/e2e.sh scripts/_proc.sh scripts/k3d.sh scripts/_k3d.sh "$TR/scripts/"
mkdir -p "$TR/deploy/k8s"; cp deploy/k8s/budgets.txt "$TR/deploy/k8s/"
for s in repo_test deploy_test tools_test k8s_test; do
  printf '#!/usr/bin/env bash\necho "suite %s" >>"$FAKE_STATE/calls.log"\n' "$s" >"$TR/scripts/test/$s.sh"
done
# FAKE_RESTART_K3D=1: 테스트 도중 누가 클러스터를 다시 띄운 상황(시작 검사와 백엔드 테스트 사이의 TOCTOU)
printf '[ "${FAKE_RESTART_K3D:-0}" = 1 ] && echo running >"$FAKE_STATE/k3d"\nexit 0\n' >>"$TR/scripts/test/k8s_test.sh"
printf '#!/usr/bin/env bash\necho "gradlew $*" >>"$FAKE_STATE/calls.log"\n' >"$TR/backend/gradlew"
printf '#!/usr/bin/env bash\necho "frontend-deps" >>"$FAKE_STATE/calls.log"\n' >"$TR/scripts/frontend-deps.sh"
printf '#!/usr/bin/env bash\necho "npm $*" >>"$FAKE_STATE/calls.log"\n' >"$TR/bin/npm"
printf '#!/usr/bin/env bash\necho "terraform $*" >>"$FAKE_STATE/calls.log"\n' >"$TR/bin/terraform"
chmod +x "$TR/scripts/"*.sh "$TR/scripts/test/"*.sh "$TR/backend/gradlew" "$TR/bin/"*
t_test_sh_stops_first() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture env TF_PLUGIN_CACHE_DIR="$TMP/tfcache" PATH="$TR/bin:$FAKE/bin:$PATH" "$TR/scripts/test.sh"
  rc_is 0 && has_text "멈췄다" && called_before "k3d cluster stop board" "suite repo_test" && called "suite k8s_test" \
    && called_before "k3d cluster stop board" "gradlew"
}
check "TASK-36#6 make test(scripts/test.sh)는 맨 먼저 떠 있는 k3d 를 멈추고 출력한다(그다음 테스트)" t_test_sh_stops_first
t_test_integration_stops_first() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture env PATH="$TR/bin:$FAKE/bin:$PATH" "$TR/scripts/test-integration.sh"
  rc_is 0 && has_text "멈췄다" && called_before "k3d cluster stop board" "gradlew"
}
check "TASK-36#6 make test-integration 도 맨 먼저 떠 있는 k3d 를 멈추고 출력한다" t_test_integration_stops_first
t_test_sh_restops_before_backend() {
  # 시작할 때 멈춘 뒤 테스트 도중 클러스터가 다시 떠도, 백엔드(Testcontainers) 직전에 한 번 더 멈춘다
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture env FAKE_RESTART_K3D=1 TF_PLUGIN_CACHE_DIR="$TMP/tfcache" PATH="$TR/bin:$FAKE/bin:$PATH" "$TR/scripts/test.sh"
  rc_is 0 || return 1
  [ "$(grep -c '^k3d cluster stop board' "$FAKE_STATE/calls.log")" = 2 ] || { echo "k3d 정지가 두 번이어야 한다"; calls; return 1; }
  local last_stop gradle suite
  last_stop="$(grep -n '^k3d cluster stop board' "$FAKE_STATE/calls.log" | tail -1 | cut -d: -f1)"
  gradle="$(grep -n '^gradlew' "$FAKE_STATE/calls.log" | head -1 | cut -d: -f1)"
  suite="$(grep -n '^suite k8s_test' "$FAKE_STATE/calls.log" | head -1 | cut -d: -f1)"
  [ "$suite" -lt "$last_stop" ] && [ "$last_stop" -lt "$gradle" ] || { echo "순서: k8s 스위트($suite) → 정지($last_stop) → gradlew($gradle)"; calls; return 1; }
  has_text "make test (backend)"
}
check "TASK-36#6 make test 는 백엔드(Testcontainers) 직전에 다시 검사해, 도중에 뜬 k3d 도 멈춘다(TOCTOU)" t_test_sh_restops_before_backend
t_e2e_stops_first() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  # 백엔드 jar 가 없어 bootJar 뒤에 실패하지만, 그 전까지의 순서만 본다
  run_capture env PATH="$TR/bin:$FAKE/bin:$PATH" "$TR/scripts/e2e.sh"
  has_text "멈췄다" && has_text "make e2e" && called_before "k3d cluster stop board" "docker compose" \
    && called_before "k3d cluster stop board" "gradlew"
}
check "TASK-36#6 make e2e(scripts/e2e.sh)도 맨 먼저 떠 있는 k3d 를 멈춘다(compose·백엔드보다 먼저)" t_e2e_stops_first
t_make_targets_stop_before_gradle() {
  # 감사: Testcontainers 를 띄우는 gradle test·build 를 직접 부르는 make 타깃은 모두 그 앞에서 stop-if-running 을 부른다
  local t out g k found=""
  for t in $(sed -n '/^\.PHONY:/,/^$/p' Makefile | tr -d '\134' | sed 's/^\.PHONY://'); do
    out="$(make -n "$t" 2>/dev/null)" || continue
    g="$(grep -nE 'gradlew +(test|build|check)' <<<"$out" | head -1 | cut -d: -f1)"
    [ -n "$g" ] || continue
    found+=" $t"
    k="$(grep -nF 'scripts/k3d.sh stop-if-running' <<<"$out" | head -1 | cut -d: -f1)"
    [ -n "$k" ] && [ "$k" -lt "$g" ] || { echo "make $t: gradle 앞에 stop-if-running 이 없다"; echo "$out"; return 1; }
    grep -qF "stop-if-running \"make $t\"" <<<"$out" || { echo "make $t: 누가 멈췄는지(make $t) 출력하지 않는다"; return 1; }
  done
  case "$found" in *" backend-test"*) ;; *) echo "감사 대상에 backend-test 가 없다(found:$found)"; return 1 ;; esac
  case "$found" in *" backend-build"*) ;; *) echo "감사 대상에 backend-build 가 없다(found:$found)"; return 1 ;; esac
  make -n e2e | grep -q 'scripts/e2e.sh' && grep -qF 'k3d.sh" stop-if-running "make e2e"' scripts/e2e.sh
}
check "TASK-36#6 make backend-test·backend-build(gradle 테스트)·e2e 도 시작할 때 떠 있는 k3d 를 멈춘다(Makefile 감사)" t_make_targets_stop_before_gradle
check "TASK-36#6 make test-integration 타깃은 scripts/test-integration.sh 를 부른다" sh -c 'grep -Eq "^test-integration:" Makefile && make -n test-integration | grep -q "scripts/test-integration.sh"'
check "TASK-36#6 make test·test-integration 은 env 파일을 읽지 않는다" sh -c '! make -n test test-integration | grep -Eq "local-env"'

# ================================================================ TASK-36#4 프로필을 끄면 리소스를 지워 메모리를 회수한다(make k3d-profile, make k3d-stop)
t_profile_off_deletes() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run FORCE=1 K3D_BUDGETS_FILE="$BUD" K3D_PROFILES_DIR="$PDIR" PROFILES=core ./scripts/k3d.sh profile
  rc_is 0 && called "kubectl --context k3d-board delete -k $PDIR/big --ignore-not-found" \
    && called "kubectl --context k3d-board apply -k $PDIR/core" && lacks_text "WARNING" \
    && called_before "delete -k $PDIR/big" "apply -k $PDIR/core" && has_text "big"
}
check "TASK-36#4 make k3d-profile PROFILES=core: 고르지 않은 프로필(big)은 delete -k 로 지우고(먼저 — 메모리 회수) core 는 apply -k" t_profile_off_deletes
t_profile_requires_cluster() {
  fake_reset
  run_capture k3d_run PROFILES=core ./scripts/k3d.sh profile
  rc_is 1 && has_text "make k3d-up" && not_called "kubectl --context k3d-board apply"
}
check "TASK-36#4 클러스터가 떠 있지 않으면 k3d-profile 은 make k3d-up 을 안내하고 실패" t_profile_requires_cluster
t_profile_requires_list() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run ./scripts/k3d.sh profile
  rc_is 1 && has_text "PROFILES="
}
check "TASK-36#4 k3d-profile 은 PROFILES 가 비면 실패(전부 끄기는 make k3d-stop)" t_profile_requires_list
t_stop_down() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run ./scripts/k3d.sh stop
  rc_is 0 && called "k3d cluster stop board" && called "docker stop k3d-registry.localhost" || return 1
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run ./scripts/k3d.sh down
  rc_is 0 && called "k3d cluster delete board"
}
check "TASK-36#4 make k3d-stop 은 클러스터와 레지스트리 컨테이너를 멈추고, make k3d-down 은 클러스터를 지운다" t_stop_down

# ================================================================ TASK-36#1 make k3d-up → core, make k3d-smoke → /api/actuator/health UP (LB + Traefik 인그레스)
t_up_flow() {
  fake_reset
  run_capture k3d_run ./scripts/k3d.sh up
  rc_is 0 \
    && called_before "docker info" "docker build" \
    && called "docker build -t localhost:5001/board-backend:dev" \
    && called_before "docker build" "k3d cluster create --config $ROOT/deploy/k8s/k3d/cluster.yaml" \
    && called_before "k3d cluster create" "docker push localhost:5001/board-backend:dev" \
    && called_before "docker push" "apply -k $ROOT/deploy/k8s/base" \
    && called_before "apply -k $ROOT/deploy/k8s/base" "create secret generic board-env" \
    && called_before "create secret generic board-env" "apply -k $ROOT/deploy/k8s/profiles/core" \
    && called_before "apply -k $ROOT/deploy/k8s/profiles/core" "get job -A -l board.dev/profile=core -o jsonpath=" \
    && called "rollout status"
}
check "TASK-36#1 make k3d-up: 사전 검사 → 이미지 빌드 → 클러스터 생성 → 로컬 레지스트리 push → base·Secret·core 적용 → Job 완료·롤아웃 대기" t_up_flow
t_up_start_existing() {
  fake_reset; echo stopped >"$FAKE_STATE/k3d"
  run_capture k3d_run SKIP_BUILD=1 ./scripts/k3d.sh up
  rc_is 0 && called "k3d cluster start board" && not_called "k3d cluster create" && not_called "docker build" \
    && called "docker start k3d-registry.localhost"
}
check "TASK-36#1 멈춘 클러스터가 있으면 다시 시작한다(SKIP_BUILD=1 이면 빌드 생략)" t_up_start_existing
t_up_rerun_jobs() {
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 ./scripts/k3d.sh up
  rc_is 0 && called_before "delete job -A -l board.dev/profile=core,board.dev/rerun=true" "apply -k $ROOT/deploy/k8s/profiles/core"
}
check "TASK-36#1 TASK-36#3 적용 전에 다시 돌릴 Job(마이그레이션 등)을 지운다(Job 은 바꿀 수 없다 — 매번 새 이미지로 다시 돈다)" t_up_rerun_jobs
t_secret_from_env() {
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 POSTGRES_PASSWORD=s3cret POSTGRES_DB=boarddb DB_URL=jdbc:postgresql://localhost:5432/board \
    MINIO_ROOT_USER=mu MINIO_ROOT_PASSWORD=mp12345678 ./scripts/k3d.sh up
  rc_is 0 || return 1
  local f="$FAKE_STATE/secret.env"
  if ! { grep -qx 'POSTGRES_PASSWORD=s3cret' "$f" && grep -qx 'DB_PASSWORD=s3cret' "$f" \
    && grep -qx 'DB_URL=jdbc:postgresql://board-postgres:5432/boarddb' "$f" \
    && grep -qx 'MINIO_ROOT_USER=mu' "$f" && grep -qx 'MINIO_ROOT_PASSWORD=mp12345678' "$f" \
    && grep -qx 'UPLOADS_ACCESS_KEY=mu' "$f" && ! grep -q 'localhost' "$f"; }; then
    echo "--- secret.env:"; cat "$f"; return 1
  fi
  if grep -rqs 's3cret' "$ROOT/deploy"; then echo "비밀값이 리포에 남았다"; return 1; fi
}
check "TASK-36#1 Secret 은 env 파일(환경변수) 값으로 스크립트가 만든다(클러스터 안 주소로, 리포에 남기지 않는다)" t_secret_from_env
t_secret_no_tempfile() {
  # 비밀값은 임시 파일에 쓰지 않는다 — stdin(파이프)으로 kubectl 에 넘긴다. kubectl 이 실패해도($TMPDIR 에) 평문이 남지 않는다
  local td="$TMP/tmpdir-secret"; rm -rf "$td"; mkdir -p "$td"
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 TMPDIR="$td" POSTGRES_PASSWORD=leak-me-123 FAKE_KUBECTL_FAIL_SECRET=1 ./scripts/k3d.sh up
  rc_is 1 && called "create secret generic board-env --from-env-file=/dev/stdin" && lacks_text "leak-me-123" || return 1
  [ -z "$(ls -A "$td")" ] || { echo "TMPDIR 에 파일이 남았다:"; ls -la "$td"; return 1; }
  grep -rqs 'leak-me-123' "$td" && { echo "평문 비밀값이 남았다"; return 1; }
  grep -q 'mktemp' scripts/k3d.sh && { echo "k3d.sh 가 아직 mktemp 를 쓴다"; return 1; }
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 TMPDIR="$td" POSTGRES_PASSWORD=leak-me-123 ./scripts/k3d.sh up
  rc_is 0 && [ -z "$(ls -A "$td")" ] && lacks_text "leak-me-123"
}
check "TASK-36#1 Secret 은 임시 파일 없이 stdin 으로 넘긴다 — kubectl 이 실패해도 평문이 남지 않고, 값을 출력하지 않는다" t_secret_no_tempfile
SPECIAL_PW='p a"s'"'"'s $x `y` \n ;&|<> @:/#% end '
t_secret_special_values() {
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 POSTGRES_PASSWORD="$SPECIAL_PW" MINIO_ROOT_PASSWORD='pw@:/#%x' ./scripts/k3d.sh up
  rc_is 0 && lacks_text "$SPECIAL_PW" || return 1
  local f="$FAKE_STATE/secret.env"
  if ! { grep -qxF "POSTGRES_PASSWORD=$SPECIAL_PW" "$f" && grep -qxF "DB_PASSWORD=$SPECIAL_PW" "$f" \
    && grep -qxF 'MINIO_ROOT_PASSWORD=pw@:/#%x' "$f" && grep -qxF 'UPLOADS_SECRET_KEY=pw@:/#%x' "$f"; }; then
    echo "--- secret.env:"; cat "$f"; return 1
  fi
}
check "TASK-36#1 Secret 값의 공백·따옴표·특수문자(\$ \` \\ ; @:/#%)를 그대로 넣는다(값은 출력하지 않는다)" t_secret_special_values
if [ -n "$REAL_KUBECTL" ] && need_tool jq && need_tool ruby; then
  t_secret_real_kubectl() {
    # 진짜 kubectl(--dry-run=client, 클러스터 없이)이 env 파일 형식을 값 그대로 읽는지 — 앞뒤 공백·따옴표·특수문자
    fake_reset
    run_capture k3d_run SKIP_BUILD=1 FAKE_REAL_SECRET=1 POSTGRES_PASSWORD="$SPECIAL_PW" MINIO_ROOT_PASSWORD='pw@:/#%x' ./scripts/k3d.sh up
    rc_is 0 || return 1
    local got
    got="$(ruby -ryaml -rjson -e 'puts JSON.generate(YAML.load_stream(STDIN.read).compact)' <"$FAKE_STATE/applied.yaml" \
      | jq -r '.[] | select(.kind == "Secret") | .data.POSTGRES_PASSWORD' | base64 -d)"
    [ "$got" = "$SPECIAL_PW" ] || { echo "kubectl 이 값을 바꿨다: [$got]"; return 1; }
    got="$(ruby -ryaml -rjson -e 'puts JSON.generate(YAML.load_stream(STDIN.read).compact)' <"$FAKE_STATE/applied.yaml" \
      | jq -r '.[] | select(.kind == "Secret") | .data.MINIO_ROOT_PASSWORD' | base64 -d)"
    [ "$got" = 'pw@:/#%x' ] || { echo "kubectl 이 MinIO 값을 바꿨다: [$got]"; return 1; }
  }
  check "TASK-36#1 진짜 kubectl(--dry-run=client)로 만든 Secret 의 값이 env 파일 값과 바이트 단위로 같다" t_secret_real_kubectl
fi
t_secret_newline_rejected() {
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 POSTGRES_PASSWORD=$'line1\nINJECTED=1' ./scripts/k3d.sh up
  rc_is 1 && has_text "POSTGRES_PASSWORD" && has_text "줄바꿈" && lacks_text "INJECTED" && not_called "create secret" || return 1
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 JWT_SECRET=$'abc\rdef' ./scripts/k3d.sh up
  rc_is 1 && has_text "JWT_SECRET" && has_text "줄바꿈" && not_called "create secret"
}
check "TASK-36#1 Secret 값에 줄바꿈(\\n·\\r)이 있으면 키 이름과 함께 분명한 오류로 거부한다(값은 출력하지 않는다)" t_secret_newline_rejected
check "TASK-36#1 make k3d-up·k3d-profile 은 env 파일을 읽는다(LOCAL_ENV)" sh -c 'make -n k3d-up | grep -q "local-env.sh" && make -n k3d-profile PROFILES=core | grep -q "local-env.sh"'
t_smoke_ok() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run FAKE_ACTIVE_PROFILES=core ./scripts/k3d.sh smoke
  rc_is 0 && called "Host: board.localhost" && called "http://127.0.0.1:80/api/actuator/health" && has_text "UP"
}
check "TASK-36#1 make k3d-smoke: 127.0.0.1(k3d LB) + Host: board.localhost(Traefik 인그레스)로 /api/actuator/health 가 UP" t_smoke_ok
t_smoke_down() {
  fake_reset; echo running >"$FAKE_STATE/k3d"
  run_capture k3d_run FAKE_ACTIVE_PROFILES=core FAKE_HEALTH_BODY='{"status":"DOWN"}' ./scripts/k3d.sh smoke
  rc_is 1 && has_text "DOWN" || return 1
  fake_reset
  run_capture k3d_run FAKE_ACTIVE_PROFILES=core ./scripts/k3d.sh smoke
  rc_is 1 && has_text "make k3d-up" && not_called "curl"
}
check "TASK-36#1 health 가 UP 이 아니거나 클러스터가 없으면 k3d-smoke 는 실패" t_smoke_down
check "TASK-36#1 k3d 설정: 서버 1·에이전트 0, 로컬 레지스트리 :5001, metrics-server 끔, 80 → loadbalancer, k3s 이미지 고정" sh -c '
  f=deploy/k8s/k3d/cluster.yaml
  grep -q "^kind: Simple" $f && grep -Eq "^servers: 1$" $f && grep -Eq "^agents: 0$" $f \
    && grep -q "name: board$" $f && grep -q "hostPort: \"5001\"" $f && grep -q "name: registry.localhost" $f \
    && grep -q -- "--disable=metrics-server" $f && grep -q "port: 127.0.0.1:80:80" $f && grep -q "loadbalancer" $f \
    && grep -Eq "^image: rancher/k3s:v[0-9.]+-k3s[0-9]+$" $f'

if command -v k3d >/dev/null 2>&1; then
  # k3d 는 설정 파일을 읽을 때 스키마로 검증한다(모르는 필드면 실패) — 클러스터를 만들지 않고 migrate 로 읽어 본다
  check "TASK-36#1 k3d 설정이 k3d 스키마(v1alpha5)를 통과한다(k3d config migrate)" k3d config migrate deploy/k8s/k3d/cluster.yaml "$TMP/cluster-migrated.yaml"
else
  echo "  WARNING: k3d 가 없어 k3d 설정 스키마 검사를 건너뛴다" >&2
fi
t_up_no_jobs() {
  # Job 이 없는 프로필(이후 태스크)도 대기 단계에서 실패하지 않는다 — kubectl wait 는 대상이 없으면 실패하므로 있을 때만 부른다
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 FAKE_NO_JOBS=1 ./scripts/k3d.sh up
  rc_is 0 && not_called "wait --for=condition=complete" && called "rollout status"
}
check "TASK-36#1 Job 이 없는 프로필이면 Job 대기를 건너뛴다" t_up_no_jobs
t_job_failed_fast() {
  # Job 이 Failed 면 시간 초과(K3D_WAIT_TIMEOUT)를 기다리지 않고 바로 실패하고, kubectl logs job/<이름> 을 보여 준다
  fake_reset
  local start end
  start=$(date +%s)
  run_capture k3d_run SKIP_BUILD=1 K3D_WAIT_TIMEOUT=300s FAKE_JOB_STATUS="board/board-migrate Failed" ./scripts/k3d.sh up
  end=$(date +%s)
  rc_is 1 && has_text "board-migrate" && has_text "FAKE-JOB-LOG" && called "-n board logs job/board-migrate" \
    && not_called "rollout status" || return 1
  [ $((end - start)) -lt 10 ] || { echo "$((end - start))초 — Failed 면 바로 끝나야 한다"; return 1; }
}
check "TASK-36#1 TASK-36#3 Job 이 Failed 면 시간 초과를 기다리지 않고 바로 실패하고 Job 로그를 출력한다" t_job_failed_fast
t_job_timeout_logs() {
  fake_reset
  run_capture k3d_run SKIP_BUILD=1 K3D_WAIT_TIMEOUT=1s FAKE_JOB_STATUS="board/board-migrate " ./scripts/k3d.sh up
  rc_is 1 && has_text "1s" && has_text "board-migrate" && has_text "FAKE-JOB-LOG" && not_called "rollout status"
}
check "TASK-36#1 TASK-36#3 Job 이 시간 안에 끝나지 않아도 실패하고 Job 로그를 출력한다" t_job_timeout_logs


# ================================================================ 매니페스트 렌더(진짜 kubectl kustomize)
# YAML → JSON 은 ruby 표준 라이브러리(Psych)로 바꿔 jq 로 본다(macOS·GitHub ubuntu 러너에 있다. python3 에는 yaml 모듈이 없다)
y2j() { ruby -ryaml -rjson -e 'puts JSON.generate(YAML.load_stream(STDIN.read).compact)'; }
RENDER_OK=0
if need_tool kubectl && need_tool jq && need_tool ruby; then
  RENDER_OK=1
  kubectl kustomize deploy/k8s/base | y2j >"$TMP/base.json"
  for d in deploy/k8s/profiles/*/; do
    p="$(basename "$d")"
    kubectl kustomize "$d" | y2j >"$TMP/profile-$p.json"
  done
fi
# 컨테이너(초기화 컨테이너 포함) 목록: 종류·이름·컨테이너·resources
containers_jq='.[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job" or .kind == "DaemonSet" or .kind == "Pod")
  | (if .kind == "Pod" then .spec else .spec.template.spec end) as $s
  | .kind as $k | .metadata.name as $n
  | (($s.containers // []) + ($s.initContainers // []))[] | {kind: $k, name: $n, container: .name, resources: .resources}'

# ================================================================ TASK-36#2 TASK-36#B1 모든 pod 에 requests/limits, 예산 파일 ≥ 매니페스트 메모리 한도 합계
# MiB 로: 256Mi → 256, 1Gi → 1024
mib() { awk -v v="$1" 'BEGIN { if (v ~ /Gi$/) { sub(/Gi$/, "", v); print v * 1024 } else if (v ~ /Mi$/) { sub(/Mi$/, "", v); print v + 0 } else { print "bad:" v } }'; }
if [ "$RENDER_OK" = 1 ]; then
  t_all_resources() {
    local f bad
    for f in "$TMP"/profile-*.json; do
      bad="$(jq -c "[$containers_jq | select(.resources.requests.memory == null or .resources.requests.cpu == null or .resources.limits.memory == null or .resources.limits.cpu == null)]" "$f")"
      [ "$bad" = "[]" ] || { echo "$(basename "$f"): requests/limits(cpu·memory) 없음: $bad"; return 1; }
      [ "$(jq "[$containers_jq] | length" "$f")" -gt 0 ] || { echo "$(basename "$f"): 컨테이너가 없다"; return 1; }
    done
  }
  check "TASK-36#2 모든 프로필의 모든 컨테이너(초기화 컨테이너 포함)에 requests·limits(cpu·memory)" t_all_resources
  t_budget_covers_manifests() {
    # 계산: 오래 도는 워크로드(Deployment·StatefulSet)의 컨테이너 한도 × replicas + Job 컨테이너 한도 합(동시에 돌 수 있다).
    # 초기화 컨테이너는 같은 pod 의 앱 컨테이너보다 크지 않게 둔다(pod 의 실효 한도 = max(초기화, 앱 합)).
    local f p sum budget
    for f in "$TMP"/profile-*.json; do
      p="$(basename "$f" .json)"; p="${p#profile-}"
      sum=0
      while IFS=' ' read -r _kind replicas mem; do
        sum=$((sum + replicas * $(mib "$mem")))
      done < <(jq -r '.[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
        | (.spec.replicas // 1) as $r | .kind as $k | .spec.template.spec.containers[] | "\($k) \($r) \(.resources.limits.memory)"' "$f")
      budget="$(awk -v p="$p" '$1 == "profile" && $2 == p { print $3 }' deploy/k8s/budgets.txt)"
      [ -n "$budget" ] || { echo "budgets.txt 에 profile $p 가 없다"; return 1; }
      [ "$sum" -le "$budget" ] || { echo "프로필 $p: 매니페스트 메모리 한도 합 ${sum}MiB > 예산 ${budget}MiB"; return 1; }
      echo "$p: ${sum}MiB ≤ ${budget}MiB"
    done
  }
  check "TASK-36#2 TASK-36#B1 프로필마다 매니페스트의 메모리 한도 합계 ≤ budgets.txt 예산(문서의 숫자가 실제와 같다)" t_budget_covers_manifests
  t_init_le_main() {
    local f
    for f in "$TMP"/profile-*.json; do
      jq -e '[.[] | select(.spec.template.spec.initContainers != null) | .spec.template.spec as $s
        | ([$s.initContainers[].resources.limits.memory | sub("Mi$"; "") | tonumber] | max)
          <= ([$s.containers[].resources.limits.memory | sub("Mi$"; "") | tonumber] | add)] | all' "$f" >/dev/null \
        || { echo "$(basename "$f"): 초기화 컨테이너 메모리 한도가 앱보다 크다"; return 1; }
    done
  }
  check "TASK-36#B1 초기화 컨테이너의 메모리 한도는 앱 컨테이너 합 이하(예산 계산이 맞게)" t_init_le_main
  t_recreate() {
    jq -e '[.[] | select(.kind == "Deployment") | .spec.strategy.type == "Recreate"] | all' "$TMP"/profile-core.json >/dev/null \
      || { echo "core Deployment 는 Recreate 여야 한다(롤링 업데이트는 잠깐 두 벌 → 예산 초과)"; return 1; }
  }
  check "TASK-36#B1 core Deployment 는 Recreate(롤아웃 중 두 벌이 떠 예산을 넘지 않게)" t_recreate
  check "TASK-36#2 TASK-36#B1 예산 파일: docker-min·limit·core 가 있고 core 는 약 1.6GB, 합계 상한 ≤ Docker 최소 메모리" sh -c '
    f=deploy/k8s/budgets.txt
    dm=$(awk "\$1==\"docker-min\"{print \$2}" $f); lim=$(awk "\$1==\"limit\"{print \$2}" $f); core=$(awk "\$1==\"profile\" && \$2==\"core\"{print \$3}" $f)
    [ -n "$dm" ] && [ -n "$lim" ] && [ -n "$core" ] && [ "$core" -ge 1400 ] && [ "$core" -le 1800 ] && [ "$lim" -lt "$dm" ]'
  check "TASK-36#2 TASK-36#B1 README 의 예산 표가 budgets.txt 와 같다(문서·스크립트가 같은 숫자)" sh -c '
    awk "\$1==\"profile\"{print \$2, \$3}" deploy/k8s/budgets.txt | while read -r p m; do grep -Eq "\| *$p *\| *$m *MiB" README.md || { echo "README 에 $p $m MiB 없음"; exit 1; }; done'
fi

# ================================================================ TASK-36#1 MinIO 버킷 준비 Job: 자격 증명은 URL(MC_HOST_*)에 넣지 않는다(특수문자 @:/#% 가 깨지지 않게)
if [ "$RENDER_OK" = 1 ]; then
  t_minio_init_creds() {
    local f="$TMP/profile-core.json"
    jq -e '.[] | select(.kind == "Job" and .metadata.name == "board-minio-init") | .spec.template.spec.containers[0]
      | ([.env[] | select(.valueFrom.secretKeyRef != null) | .name] | sort == ["MINIO_ROOT_PASSWORD", "MINIO_ROOT_USER"])
        and ([.env[].name] | index("MC_HOST_local") == null)
        and (.args | join(" ") | contains("mc alias set local http://board-minio:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\""))' "$f" >/dev/null \
      || { echo "board-minio-init: env 로 받은 MINIO_ROOT_USER·PASSWORD 를 mc alias set 에 따옴표로 넘겨야 한다(MC_HOST_local 금지)"; return 1; }
    ! grep -rq 'MC_HOST' deploy/k8s scripts/k3d.sh || { echo "MC_HOST 가 남았다"; return 1; }
  }
  check "TASK-36#1 MinIO 초기화 Job 은 자격 증명을 env 로 받아 mc alias set 에 넘긴다(URL 에 넣지 않아 @:/#% 도 된다)" t_minio_init_creds
fi

# ================================================================ TASK-36#3 Flyway 마이그레이션은 앱 기동과 분리한 Job
if [ "$RENDER_OK" = 1 ]; then
  t_migrate_job() {
    local f="$TMP/profile-core.json"
    jq -e '[.[] | select(.kind == "Job" and .metadata.name == "board-migrate")] | length == 1' "$f" >/dev/null || { echo "Job board-migrate 없음"; return 1; }
    jq -e '.[] | select(.kind == "Job" and .metadata.name == "board-migrate") | .metadata.labels["board.dev/rerun"] == "true"
      and (.spec.template.spec.containers[0].command | join(" ") | test("com.example.board.common.MigrationJob migrate$"))' "$f" >/dev/null \
      || { echo "Job 명령이 MigrationJob migrate 가 아니다"; return 1; }
  }
  check "TASK-36#3 core 에 마이그레이션 Job(board-migrate: 백엔드 이미지의 MigrationJob migrate, 매 적용마다 다시 돈다)" t_migrate_job
  t_backend_no_startup_migration() {
    local f="$TMP/profile-core.json"
    jq -e '.[] | select(.kind == "Deployment" and .metadata.name == "board-backend") | .spec.template.spec
      | ([.containers[0].env[] | select(.name == "SPRING_PROFILES_ACTIVE") | .value] == ["k8s"])
        and ([.initContainers[] | select(.command | join(" ") | test("MigrationJob await$"))] | length == 1)' "$f" >/dev/null \
      || { echo "board-backend: SPRING_PROFILES_ACTIVE=k8s 와 initContainer(MigrationJob await)가 필요하다"; return 1; }
  }
  check "TASK-36#3 backend 는 k8s 프로필(기동 시 마이그레이션 끔)로 뜨고, 초기화 컨테이너가 Job 의 마이그레이션 완료를 기다린다" t_backend_no_startup_migration
fi
check "TASK-36#3 application-k8s.properties 가 spring.flyway.enabled=false" grep -qx 'spring.flyway.enabled=false' backend/src/main/resources/application-k8s.properties
check "TASK-36#3 docker-compose·운영(AWS) 경로는 그대로(기본 프로필 = 기동 시 마이그레이션)" sh -c '
  ! grep -q "spring.flyway.enabled" backend/src/main/resources/application.properties \
  && ! grep -rq "SPRING_PROFILES_ACTIVE" docker-compose.yml deploy/docker-compose.prod.yml deploy/render-env.sh'

# ================================================================ TASK-36#5 kustomize build + kubeconform 오프라인 검증(증가 10초 미만)
if need_tool kubectl && need_tool kubeconform; then
  t_validate_all() {
    local start end
    start=$(date +%s)
    run_capture ./scripts/k8s-validate.sh
    end=$(date +%s)
    rc_is 0 && has_text "deploy/k8s/base" && has_text "deploy/k8s/profiles/core" && has_text "Invalid: 0" || return 1
    [ $((end - start)) -lt 10 ] || { echo "$((end - start))초 — 10초 미만이어야 한다"; return 1; }
  }
  check "TASK-36#5 scripts/k8s-validate.sh: base·모든 프로필을 kustomize 렌더 + kubeconform(-strict) 검증, 10초 미만" t_validate_all
  t_validate_offline() {
    # 스키마는 리포(deploy/k8s/schemas)에서만 읽는다 — 네트워크 주소를 쓰지 않는다
    grep -q 'schema-location' scripts/k8s-validate.sh && ! grep -E 'kubeconform .*https?://' scripts/k8s-validate.sh \
      && ls deploy/k8s/schemas/*.json >/dev/null 2>&1 \
      && run_capture env HTTPS_PROXY=http://127.0.0.1:9 HTTP_PROXY=http://127.0.0.1:9 ./scripts/k8s-validate.sh && rc_is 0
  }
  check "TASK-36#5 kubeconform 은 오프라인(리포에 고정한 스키마, 프록시를 막아도 통과)" t_validate_offline
  t_validate_rejects() {
    local bad="$TMP/badk"; mkdir -p "$bad"
    printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: [d.yaml]\n' >"$bad/kustomization.yaml"
    printf 'apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: x\nspec:\n  replicas: "two"\n  selector: {}\n  template:\n    spec:\n      containers:\n      - name: a\n        imagee: x\n' >"$bad/d.yaml"
    run_capture ./scripts/k8s-validate.sh "$bad"
    rc_is 1
  }
  check "TASK-36#5 잘못된 매니페스트(타입·모르는 필드)는 실패한다" t_validate_rejects
  t_validate_missing_schema() {
    local bad="$TMP/nosch"; mkdir -p "$bad"
    printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: [d.yaml]\n' >"$bad/kustomization.yaml"
    printf 'apiVersion: v1\nkind: ReplicationController\nmetadata:\n  name: x\n' >"$bad/d.yaml"
    run_capture ./scripts/k8s-validate.sh "$bad"
    rc_is 1 && has_text "--update-schemas"
  }
  check "TASK-36#5 리포에 스키마가 없는 종류는 실패하고 --update-schemas 를 안내한다(조용히 건너뛰지 않는다)" t_validate_missing_schema
fi
check "TASK-36#5 scripts/test.sh 는 k8s 스위트를 돌린다" grep -q 'scripts/test/k8s_test.sh' scripts/test.sh
check "TASK-36#5 CI 는 kubeconform 을 설치한다(REQUIRE_LINTERS=1 로 필수)" sh -c 'grep -q "kubeconform" .github/workflows/ci.yml'

echo "k8s: $passes passed, $failures failed"
[ "$failures" -eq 0 ]
