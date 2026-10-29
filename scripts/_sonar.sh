#!/usr/bin/env bash
# SonarQube 로컬 공통 함수 (TASK-67). source 해서 쓴다(scripts/sonar.sh). scripts/_vm.sh 를 먼저 source 한다.
#   sonar_compose <인자...>   podman compose -p board-sonar -f deploy/sonar/compose.yaml <인자...> (podman 이 없으면 docker compose)
#   sonar_running             board-sonar 컨테이너가 하나라도 떠 있으면 0
#   sonar_wait_up             /api/system/status 가 UP 이 될 때까지(SONAR_WAIT_TIMEOUT 초) 기다린다
# 테스트용 환경변수: SONAR_CONTAINER_CLI(podman|docker), SONAR_WAIT_TIMEOUT·SONAR_WAIT_INTERVAL(초), SONAR_PUBLIC_HOST(접속 주소의 호스트)

SONAR_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SONAR_PROJECT="board-sonar"
SONAR_COMPOSE_FILE="$SONAR_ROOT/deploy/sonar/compose.yaml"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"
SONAR_WAIT_TIMEOUT="${SONAR_WAIT_TIMEOUT:-600}"
SONAR_WAIT_INTERVAL="${SONAR_WAIT_INTERVAL:-5}"

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

# 라벨(여러 개면 모두 맞는 것)로 찾은 실행 중 컨테이너 id(런타임이 없으면 빈 값)
sonar_ids_by_label() {
  local cli l f=(); cli="$(sonar_cli)"
  [ -n "$cli" ] || return 0
  for l in "$@"; do f+=(--filter "label=$l"); done
  "$cli" ps -q "${f[@]}" 2>/dev/null || true
}

sonar_running() { [ -n "$(sonar_ids_by_label "com.docker.compose.project=$SONAR_PROJECT")" ]; }

# 맥 브라우저가 붙을 주소: VM 의 첫 IPv4(hostname -I)
sonar_public_url() {
  local host="${SONAR_PUBLIC_HOST:-}"
  [ -n "$host" ] || host="$(hostname -I 2>/dev/null | awk '{ print $1 }')"
  printf 'http://%s:9000' "${host:-<VM IP>}"
}

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
