#!/usr/bin/env bash
# SonarQube 로컬 (TASK-67). make sonar-up·sonar-down·sonar-status 가 부른다. 공통 함수는 scripts/_sonar.sh, 서비스는 deploy/sonar/compose.yaml.
#   up               compose up -d → /api/system/status UP 대기 → 접속 주소(http://<VM IP>:9000)
#   down [--purge]   compose down(볼륨·분석 이력은 남긴다). --purge 는 볼륨과 VM 설정(~/.config/board-sonar)까지 지운다
#   status           실행 여부·접속 주소·컨테이너 메모리
# 맥이면 Rocky 개발 VM 안에서 돈다(scripts/_vm.sh vm_proxy_if_mac — VM 사전 검사·같은 잠금). 맥에는 컨테이너 런타임이 없다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=scripts/_vm.sh
source "$ROOT/scripts/_vm.sh"
# shellcheck source=scripts/_sonar.sh
source "$ROOT/scripts/_sonar.sh"

usage() { echo "사용: $0 up | down [--purge] | status" >&2; exit 2; }
cmd="${1:-}"
case "$cmd" in up | down | status) ;; *) usage ;; esac

vm_proxy_if_mac "./scripts/sonar.sh $(printf '%q ' "$@")"

cmd_up() {
  sonar_log "SonarQube 기동(podman compose -p $SONAR_PROJECT, $SONAR_COMPOSE_FILE)"
  sonar_compose up -d
  sonar_wait_up
  sonar_log "준비됐다: $(sonar_public_url) (맥 브라우저) · 상태: make sonar-status · 내리기: make sonar-down"
}

cmd_down() {
  if [ "${1:-}" = --purge ]; then
    sonar_compose down -v
    rm -rf "${SONAR_CONFIG_DIR:-$HOME/.config/board-sonar}"
    sonar_log "내렸다 — 볼륨(분석 이력)과 VM 설정(관리자 비밀번호·토큰)까지 지웠다. 다음 make sonar-up 은 처음부터 다시 만든다"
  else
    sonar_compose down
    sonar_log "내렸다(메모리 회수). 볼륨·분석 이력은 남는다. 다시: make sonar-up"
  fi
}

cmd_status() {
  if ! sonar_running; then
    echo "SonarQube($SONAR_PROJECT): 꺼져 있다 — 띄우기: make sonar-up"
    return 0
  fi
  echo "SonarQube($SONAR_PROJECT): 실행 중 (서버 상태: $(sonar_status_value | grep . || echo '응답 없음'))"
  echo "  접속: $(sonar_public_url) (맥 브라우저)"
  echo "  컨테이너 메모리:"
  local ids; mapfile -t ids < <(sonar_ids_by_label "com.docker.compose.project=$SONAR_PROJECT")
  "$(sonar_cli)" stats --no-stream --format '{{.Name}} {{.MemUsage}}' "${ids[@]}" 2>/dev/null | sed 's/^/    /' || true
}

case "$cmd" in
  up) cmd_up ;;
  down) cmd_down "${2:-}" ;;
  status) cmd_status ;;
esac
