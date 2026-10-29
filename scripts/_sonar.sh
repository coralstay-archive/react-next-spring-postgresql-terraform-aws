#!/usr/bin/env bash
# SonarQube 로컬 공통 함수 (TASK-67). source 해서 쓴다(scripts/sonar.sh). scripts/_vm.sh 를 먼저 source 한다.
#   sonar_compose <인자...>   podman compose -p board-sonar -f deploy/sonar/compose.yaml <인자...> (podman 이 없으면 docker compose)
#   sonar_running             board-sonar 컨테이너가 하나라도 떠 있으면 0, 없으면 1, 확인하지 못하면(podman ps 실패) 2
#   sonar_wait_up             /api/system/status 가 UP 이 될 때까지(SONAR_WAIT_TIMEOUT 초) 기다린다
#   sonar_preflight [--no-mem]  단독 모드(B1, TASK-67#2): k3s·k3d 실행 중, make test 잠금(VM_LOCK_FILE)·Testcontainers, VM 사용 가능 메모리
#                             (MemAvailable) < budgets.txt sonar 면 이유·해결을 모두 출력하고 실패. SONAR_LOCK_HELD=1(맥 프록시가 그 잠금
#                             안에서 부른다)이면 잠금 검사는 건너뛴다
#   sonar_ensure_credentials  (B2, TASK-67#4) 관리자 비밀번호를 API 로 무작위 값으로 바꾸고 분석 토큰(GLOBAL_ANALYSIS_TOKEN)을 만들어
#                             SONAR_CONFIG_DIR(기본 VM 의 ~/.config/board-sonar, 0700/0600)에만 둔다. 다시 돌리면 저장값을 확인만 한다.
#                             비밀값은 출력하지 않고 curl 인자에도 싣지 않는다(-K 설정을 프로세스 치환으로). 새 코드 기준은 PREVIOUS_VERSION
#   sonar_refuse_if_running <누가>  역방향: SonarQube 가 떠 있으면(또는 확인하지 못하면) make sonar-down 을 안내하고 실패(make test·
#                             k3d 진입점이 부른다). 맥에서 부르면 ssh 로 VM 안 Podman 을 본다
#   sonar_ci_gates            (CI, TASK-67#6) 프로젝트별 래칫 품질 게이트(전체 코드 조건)를 만들어 지정 — 아래 SONAR_CI_GATE_CONDITIONS
# 테스트용 환경변수: SONAR_CONTAINER_CLI(podman|docker), SONAR_WAIT_TIMEOUT·SONAR_WAIT_INTERVAL(초),
#   SONAR_MEMINFO(/proc/meminfo 대신)

SONAR_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SONAR_PROJECT="board-sonar"
SONAR_COMPOSE_FILE="$SONAR_ROOT/deploy/sonar/compose.yaml"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"
SONAR_WAIT_TIMEOUT="${SONAR_WAIT_TIMEOUT:-600}"
SONAR_WAIT_INTERVAL="${SONAR_WAIT_INTERVAL:-5}"
SONAR_CONFIG_DIR="${SONAR_CONFIG_DIR:-$HOME/.config/board-sonar}"
# 스캐너 컨테이너(deploy/sonar/scanner/Containerfile 의 ARG SCANNER_VERSION 과 같게 — sonar_test.sh 가 비교한다)
SONAR_SCANNER_VERSION="${SONAR_SCANNER_VERSION:-8.1.0.6389}"
# 태그에 Containerfile 해시를 붙인다 — 파일을 고치면 다음 sonar-scan 이 새로 빌드한다
# shellcheck disable=SC2034 # scripts/sonar.sh 가 쓴다
SONAR_SCANNER_IMAGE="board-sonar-scanner:$SONAR_SCANNER_VERSION-$(sha256sum "$SONAR_ROOT/deploy/sonar/scanner/Containerfile" 2>/dev/null | cut -c1-8)"

sonar_log() { echo "==> [sonar] $*" >&2; }
sonar_warn() { echo "WARNING: [sonar] $*" >&2; }
sonar_die() { echo "ERROR: [sonar] $*" >&2; exit 1; }

# 컨테이너 CLI: VM 은 Podman, CI(ubuntu)는 docker. 둘 다 없으면 빈 값
sonar_cli() {
  if [ -n "${SONAR_CONTAINER_CLI:-}" ]; then echo "$SONAR_CONTAINER_CLI"; return; fi
  if command -v podman >/dev/null 2>&1; then echo podman; elif command -v docker >/dev/null 2>&1; then echo docker; fi
}

sonar_compose() {
  local cli; cli="$(sonar_cli)"
  [ -n "$cli" ] || sonar_die "컨테이너 런타임(podman·docker)이 없다 — Rocky 개발 VM 안에서 돈다(맥이면 make sonar-* 가 VM 으로 보낸다)"
  "$cli" compose -p "$SONAR_PROJECT" -f "$SONAR_COMPOSE_FILE" "$@"
}

# 라벨(여러 개면 모두 맞는 것)로 찾은 실행 중 컨테이너 id. 런타임이 없으면 빈 값(CI·맥), ps 가 실패하면 1(모른다 — "없음"이 아니다)
sonar_ids_by_label() {
  local cli l f=(); cli="$(sonar_cli)"
  [ -n "$cli" ] || return 0
  for l in "$@"; do f+=(--filter "label=$l"); done
  "$cli" ps -q "${f[@]}"
}

sonar_running() {
  local ids
  ids="$(sonar_ids_by_label "com.docker.compose.project=$SONAR_PROJECT")" || return 2
  [ -n "$ids" ]
}

# 맥 브라우저·IDE 접속(9000 은 VM 루프백에만 열려 있다)
# shellcheck disable=SC2034 # scripts/sonar.sh 가 쓴다
SONAR_TUNNEL_HINT="맥에서 ssh -N -L 9000:localhost:9000 rocky-dev 를 열고 http://localhost:9000"

sonar_status_value() { curl -fsS --max-time 5 "$SONAR_HOST_URL/api/system/status" 2>/dev/null | jq -r '.status // empty' 2>/dev/null || true; }

sonar_wait_up() {
  local deadline st=""
  deadline=$(( $(date +%s) + SONAR_WAIT_TIMEOUT ))
  sonar_log "기동 대기: $SONAR_HOST_URL/api/system/status 가 UP 이 될 때까지(최대 ${SONAR_WAIT_TIMEOUT}초)"
  while :; do
    st="$(sonar_status_value)"
    [ "$st" = UP ] && return 0
    # 컨테이너가 이미 끝났으면(Elasticsearch 부트스트랩 검사 실패 등) 기다리지 않는다
    if [ -z "$(sonar_ids_by_label "com.docker.compose.service=sonarqube" "com.docker.compose.project=$SONAR_PROJECT")" ]; then
      sonar_die "SonarQube 컨테이너가 기동 중에 멈췄다.
  로그: $(sonar_cli) logs ${SONAR_PROJECT}-sonarqube-1 | tail -50
  흔한 원인: vm.max_map_count 가 낮다(Elasticsearch 부트스트랩 검사) — sudo /usr/local/sbin/rocky-install.sh --profile dev … 로 install.sh dev 를 다시 돌린다"
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      sonar_die "SonarQube 가 ${SONAR_WAIT_TIMEOUT}초 안에 UP 이 되지 않았다(마지막 상태: ${st:-응답 없음}).
  로그: $(sonar_cli) logs ${SONAR_PROJECT}-sonarqube-1 | tail -50 (Elasticsearch 오류면 vm.max_map_count — install.sh dev 를 다시 돌린다)"
    fi
    sleep "$SONAR_WAIT_INTERVAL"
  done
}

# k3s(VM 안 systemd 서비스 또는 프로세스)
sonar_k3s_running() {
  systemctl is-active --quiet k3s 2>/dev/null || systemctl is-active --quiet k3s-agent 2>/dev/null \
    || pgrep -f '^k3s (server|agent)' >/dev/null 2>&1
}

sonar_preflight() {
  local reasons=() rc=0 avail_kb avail min k3d tc
  if sonar_k3s_running; then
    reasons+=("k3s 가 실행 중이다 — 5GB VM 에서 SonarQube(약 $(vm_budget sonar-rss) MiB)와 k3s 를 같이 띄우지 않는다.
     해결: sudo systemctl stop k3s 로 멈춘 뒤 다시(쓰고 나서 make sonar-down → k3s 다시 시작)")
  fi
  if ! k3d="$(sonar_ids_by_label k3d.cluster)"; then
    reasons+=("컨테이너 목록($(sonar_cli) ps)을 확인하지 못했다 — k3d·Testcontainers·SonarQube 가 떠 있는지 모른 채 띄우지 않는다.
     해결: $(sonar_cli) ps 오류를 고친다(Podman 사용자 소켓: systemctl --user status podman.socket)")
  elif [ -n "$k3d" ]; then
    reasons+=("k3d 클러스터 컨테이너가 떠 있다(label k3d.cluster).
     해결: make k3d-stop 뒤 다시")
  fi
  if [ "${SONAR_LOCK_HELD:-0}" != 1 ]; then
    flock -n -E 75 "$VM_LOCK_FILE" true 2>/dev/null || rc=$?
    if [ "$rc" = 75 ]; then
      reasons+=("make test(또는 다른 VM 실행)가 돌고 있다(잠금 $VM_LOCK_FILE) — 테스트(Testcontainers)와 SonarQube 를 같이 띄우지 않는다.
     해결: 끝날 때까지 기다린 뒤 다시. 남은 프로세스 보기: ps -eo pid,etime,args --forest")
    elif [ "$rc" != 0 ]; then
      reasons+=("make test 잠금($VM_LOCK_FILE)을 확인하지 못했다(flock 종료 코드 $rc) — 테스트가 도는지 모른 채 띄우지 않는다.
     해결: 잠금 파일·디렉터리 권한을 확인한다(ls -l $VM_LOCK_FILE)")
    fi
  fi
  tc="$({ sonar_ids_by_label org.testcontainers=true || true; } | awk 'NF' | wc -l | tr -d ' ')"
  if [ "$tc" -gt 0 ]; then
    reasons+=("Testcontainers 컨테이너 ${tc}개가 실행 중이다(label org.testcontainers=true — make test·test-integration).
     해결: 테스트가 끝나길 기다린다. 남은 컨테이너라면: $(sonar_cli) rm -f \$($(sonar_cli) ps -q --filter label=org.testcontainers=true)")
  fi
  if [ "${1:-}" != --no-mem ]; then
    min="$(vm_budget sonar)"
    avail_kb="$(awk '/^MemAvailable:/ { print $2 }' "${SONAR_MEMINFO:-/proc/meminfo}" 2>/dev/null || true)"
    if [ -n "$min" ] && [[ "$avail_kb" =~ ^[0-9]+$ ]]; then
      avail=$((avail_kb / 1024))
      sonar_log "VM 사용 가능 메모리 ${avail} MiB (SonarQube 기동 최소 ${min} MiB, budgets.txt sonar)"
      if [ "$avail" -lt "$min" ]; then
        reasons+=("VM 사용 가능 메모리 ${avail} MiB < ${min} MiB(budgets.txt sonar) — 띄우면 OOM 으로 죽거나 VM 이 스왑 없이 멈춘다.
     해결: 다른 컨테이너·프로세스를 내린다(podman ps, make k3d-stop). 그래도 모자라면 VM 을 다시 켠다(make vm-stop → make vm-start)")
      fi
    fi
  fi
  if [ "${#reasons[@]}" -gt 0 ]; then
    echo "ERROR: [sonar] 사전 검사 실패 — SonarQube 는 단독으로만 띄운다(TASK-67 B1):" >&2
    local r; for r in "${reasons[@]}"; do echo "  - $r" >&2; done
    return 1
  fi
}

sonar_refuse_if_running() {
  local who="${1:-이 명령}" rc=0 ids
  if vm_on_mac; then
    # 맥(k3d 진입점 등): SonarQube 는 VM 안에 있다 — VM 이 꺼져 있으면 떠 있을 수 없다
    [ "$(vm_state)" = started ] || return 0
    ids="$(vm_ssh podman ps -q --filter "label=com.docker.compose.project=$SONAR_PROJECT" 2>/dev/null)" || rc=2
    [ "$rc" = 0 ] && { [ -n "$ids" ] || rc=1; }
  else
    sonar_running || rc=$?
  fi
  [ "$rc" = 1 ] && return 0
  [ "$rc" = 2 ] && sonar_die "SonarQube($SONAR_PROJECT)가 떠 있는지 확인하지 못했다(podman ps 실패) — $who 를 하지 않는다(TASK-67 B1).
  해결: podman ps 오류를 고친 뒤 다시(VM 안: systemctl --user status podman.socket)"
  sonar_die "SonarQube($SONAR_PROJECT)가 떠 있다(약 $(vm_budget sonar-rss) MiB) — $who 와 같이 띄우지 않는다(5GB VM, TASK-67 B1).
  해결: make sonar-down 뒤 다시 (분석 이력은 남는다)"
}

# ---------------------------------------------------------------- 관리자 비밀번호·분석 토큰(B2)
# sonar_api <메서드> <경로> <user:password> [폼 k=v...] — 인증·폼은 curl 설정(-K, 프로세스 치환)으로만 넘긴다(ps 에 보이지 않게)
# curl 설정 값 이스케이프(\ → \\, " → \")
sonar_cfg_esc() { local v="${1//\\/\\\\}"; printf '%s' "${v//\"/\\\"}"; }
sonar_api() {
  local m="$1" p="$2" auth="$3" f; shift 3
  curl -fsS --max-time 30 -X "$m" -K <(printf 'user = "%s"\n' "$(sonar_cfg_esc "$auth")"
    for f in "$@"; do printf 'data-urlencode = "%s"\n' "$(sonar_cfg_esc "$f")"; done) "$SONAR_HOST_URL$p"
}
sonar_valid() { sonar_api GET /api/authentication/validate "$1" 2>/dev/null | jq -e '.valid == true' >/dev/null 2>&1; }
# SonarQube 규칙: 12자 이상, 대·소문자·숫자·특수문자
sonar_random_password() { local r; r="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"; printf '%s-Aa1' "${r:0:32}"; }
sonar_mask() { [ "${GITHUB_ACTIONS:-}" = true ] && echo "::add-mask::$1"; return 0; }
sonar_write_secret() { (umask 077; printf '%s' "$2" >"$1.tmp") && mv "$1.tmp" "$1" && chmod 600 "$1"; }
sonar_change_admin() {
  sonar_api POST /api/users/change_password "admin:$1" "login=admin" "previousPassword=$1" "password=$2" >/dev/null
}

sonar_ensure_credentials() {
  local d="$SONAR_CONFIG_DIR" pwf tokf pw tok name
  pwf="$d/admin-password"; tokf="$d/token"
  (umask 077; mkdir -p "$d"); chmod 700 "$d"
  if [ -s "$pwf" ]; then
    pw="$(cat "$pwf")"
    if ! sonar_valid "admin:$pw"; then
      sonar_valid "admin:admin" || sonar_die "저장된 관리자 비밀번호($pwf)가 맞지 않는다(UI 에서 바꿨다?).
  해결: 지금 비밀번호를 그 파일에 적거나(printf '%s' '<비밀번호>' > $pwf), 처음부터 다시: make sonar-down PURGE=1 → make sonar-up"
      sonar_change_admin admin "$pw" || sonar_die "관리자 비밀번호를 바꾸지 못했다(/api/users/change_password)"
      sonar_log "서버 데이터가 새로 만들어져(admin/admin) 관리자 비밀번호를 저장된 값으로 다시 바꿨다"
    fi
  else
    sonar_valid "admin:admin" || sonar_die "관리자 비밀번호가 기본값(admin)이 아닌데 저장된 값($pwf)이 없다.
  해결: 지금 비밀번호를 그 파일에 적거나(printf '%s' '<비밀번호>' > $pwf; chmod 600 $pwf), 처음부터 다시: make sonar-down PURGE=1 → make sonar-up"
    pw="$(sonar_random_password)"
    # 먼저 저장한다 — 바꾸기가 중간에 실패해도 다음 실행이 admin/admin 을 보고 이 값으로 다시 바꾼다
    sonar_write_secret "$pwf" "$pw"
    sonar_change_admin admin "$pw" || sonar_die "관리자 비밀번호를 바꾸지 못했다(/api/users/change_password) — 다시 make sonar-up"
    sonar_log "관리자 비밀번호를 무작위 값으로 바꿨다($pwf, 0600 — 출력하지 않는다)"
  fi
  sonar_mask "$pw"
  tok="$(cat "$tokf" 2>/dev/null || true)"
  if [ -z "$tok" ] || ! sonar_valid "$tok:"; then
    name="board-analysis-$(date +%Y%m%d%H%M%S)"
    tok="$(sonar_api POST /api/user_tokens/generate "admin:$pw" "name=$name" "type=GLOBAL_ANALYSIS_TOKEN" | jq -r '.token // empty')" || true
    [ -n "$tok" ] || sonar_die "분석 토큰을 만들지 못했다(/api/user_tokens/generate)"
    sonar_write_secret "$tokf" "$tok"
    sonar_log "분석 토큰($name, GLOBAL_ANALYSIS_TOKEN)을 만들었다($tokf, 0600 — 출력하지 않는다)"
  fi
  sonar_mask "$tok"
  # 새 코드 기준: 이전 버전(버전이 그대로면 첫 분석 이후 바뀐 코드) — 첫 분석은 기존 부채로 게이트가 막히지 않는다
  sonar_api POST /api/new_code_periods/set "admin:$pw" "type=PREVIOUS_VERSION" >/dev/null || sonar_warn "새 코드 기준(PREVIOUS_VERSION)을 설정하지 못했다"
}

# 분석 토큰: SONAR_TOKEN 환경변수(CI) > SONAR_CONFIG_DIR/token(VM)
sonar_token() {
  if [ -n "${SONAR_TOKEN:-}" ]; then printf '%s' "$SONAR_TOKEN"; return 0; fi
  cat "$SONAR_CONFIG_DIR/token" 2>/dev/null || true
}

# ---------------------------------------------------------------- CI 래칫 품질 게이트(TASK-67#6)
# CI 서버는 실행마다 새로 떠서 매번 "첫 분석"이라 Sonar way(새 코드) 게이트는 걸리지 않는다. 그래서 CI 에서는 전체 코드 조건으로
# 프로젝트별 게이트를 만든다 — 첫 분석 기준선(2026-10-29, TASK-67 노트)은 통과하고 나빠지면 실패한다.
# 백엔드 버그 6·취약점 7 은 지금 값 그대로다 — TASK-38 이 고치면서 이 값을 낮춘다. 형식: <게이트> <지표> <연산 LT|GT> <오류 기준>
SONAR_CI_GATE_CONDITIONS="
board-ci-backend coverage LT 88
board-ci-backend duplicated_lines_density GT 3
board-ci-backend bugs GT 6
board-ci-backend vulnerabilities GT 7
board-ci-frontend coverage LT 92
board-ci-frontend duplicated_lines_density GT 3
board-ci-frontend bugs GT 0
board-ci-frontend vulnerabilities GT 0
"
sonar_ci_gates() {
  local pw g m op e p
  pw="$(cat "$SONAR_CONFIG_DIR/admin-password")"
  for p in board-backend board-frontend; do
    # 프로젝트가 있어야 게이트를 지정할 수 있다(첫 분석 전) — 이미 있으면 실패해도 된다
    sonar_api POST /api/projects/create "admin:$pw" "project=$p" "name=board ${p#board-}" >/dev/null 2>&1 || true
    g="board-ci-${p#board-}"
    if ! sonar_api GET "/api/qualitygates/show?name=$g" "admin:$pw" >/dev/null 2>&1; then
      sonar_api POST /api/qualitygates/create "admin:$pw" "name=$g" >/dev/null || sonar_die "품질 게이트 $g 를 만들지 못했다"
      while read -r gg m op e; do
        [ "$gg" = "$g" ] || continue
        sonar_api POST /api/qualitygates/create_condition "admin:$pw" "gateName=$g" "metric=$m" "op=$op" "error=$e" >/dev/null \
          || sonar_die "품질 게이트 $g 조건($m $op $e)을 만들지 못했다"
      done <<<"$SONAR_CI_GATE_CONDITIONS"
    fi
    sonar_api POST /api/qualitygates/select "admin:$pw" "gateName=$g" "projectKey=$p" >/dev/null || sonar_die "$p 에 게이트 $g 를 지정하지 못했다"
    sonar_log "CI 품질 게이트: $p → $g ($(awk -v g="$g" '$1 == g { printf "%s%s %s %s", s, $2, $3, $4; s = ", " }' <<<"$SONAR_CI_GATE_CONDITIONS"))"
  done
}
