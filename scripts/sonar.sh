#!/usr/bin/env bash
# SonarQube 로컬 (TASK-67). make sonar-up·sonar-down·sonar-status 가 부른다. 공통 함수는 scripts/_sonar.sh, 서비스는 deploy/sonar/compose.yaml.
#   up               compose up -d → /api/system/status UP 대기 → 접속 주소(http://<VM IP>:9000)
#   down [--purge]   compose down(볼륨·분석 이력은 남긴다). --purge 는 볼륨과 VM 설정(~/.config/board-sonar)까지 지운다
#   status           실행 여부·접속 주소·컨테이너 메모리
#   scan             make sonar-scan(TASK-67#4): 백엔드 gradle(org.sonarqube + JaCoCo XML) → 프론트 vitest lcov + 스캐너 컨테이너.
#                    둘 다 sonar.qualitygate.wait=true — 게이트가 실패하면(하나라도) non-zero. 토큰은 VM 설정 파일(B2) 또는 SONAR_TOKEN(CI)
#   init             기동 대기 + 관리자 비밀번호 교체·토큰(CI sonar.yml 이 서비스 컨테이너에 쓴다, SONAR_CONFIG_DIR)
#   refuse-if-running <누가>  SonarQube 가 떠 있으면 실패(테스트 진입점이 VM 안에서 부른다 — 프록시하지 않는다)
# up 은 먼저 단독 모드 사전 검사(B1, scripts/_sonar.sh sonar_preflight)를 지난다.
# 맥이면 Rocky 개발 VM 안에서 돈다(scripts/_vm.sh vm_proxy_if_mac — VM 사전 검사·같은 잠금). 맥에는 컨테이너 런타임이 없다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=scripts/_vm.sh
source "$ROOT/scripts/_vm.sh"
# shellcheck source=scripts/_sonar.sh
source "$ROOT/scripts/_sonar.sh"

usage() { echo "사용: $0 up | down [--purge] | status | scan | init | refuse-if-running <누가>" >&2; exit 2; }
cmd="${1:-}"
case "$cmd" in
  up | down | status | scan | init) ;;
  refuse-if-running) sonar_refuse_if_running "${2:-}"; exit 0 ;;
  *) usage ;;
esac

# VM 안 실행은 프록시가 잡은 잠금(/tmp/board-repo-test.lock) 안에서 돈다 — 사전 검사가 그 잠금을 "다른 실행"으로 보지 않게 알린다
vm_proxy_if_mac "SONAR_LOCK_HELD=1 ./scripts/sonar.sh $(printf '%q ' "$@")"

cmd_up() {
  local rc=0; sonar_running || rc=$?
  [ "$rc" = 2 ] && sonar_die "컨테이너 목록($(sonar_cli) ps)을 확인하지 못했다 — 띄우지 않는다. 해결: VM 안 systemctl --user status podman.socket"
  if [ "$rc" = 0 ]; then
    sonar_log "이미 떠 있다 — 사전 검사(메모리)는 건너뛰고 상태만 맞춘다"
    sonar_preflight --no-mem
  else
    sonar_preflight
  fi
  sonar_log "SonarQube 기동(podman compose -p $SONAR_PROJECT, $SONAR_COMPOSE_FILE)"
  sonar_compose up -d
  sonar_wait_up
  # 자격 증명을 맞추지 못하면 기본 admin/admin 인 채로 두지 않는다 — 멈추고 크게 실패한다
  if ! (sonar_ensure_credentials); then
    sonar_compose stop || true
    sonar_die "관리자 비밀번호·분석 토큰을 맞추지 못해 SonarQube 를 멈췄다(위 오류). 고친 뒤 다시 make sonar-up"
  fi
  sonar_log "준비됐다: $SONAR_TUNNEL_HINT · 분석: make sonar-scan · 상태: make sonar-status · 내리기: make sonar-down"
  sonar_log "UI 로그인: admin / 비밀번호는 출력하지 않는다 — 보기: ssh $VM_SSH_HOST cat ~/.config/board-sonar/admin-password"
}

# CI(sonar.yml): 기동 대기 → 자격 증명 → 래칫 품질 게이트
cmd_init() {
  sonar_wait_up
  sonar_ensure_credentials
  sonar_ci_gates
}

cmd_scan() {
  local token rc_b=0 rc_f=0 cli
  sonar_preflight --no-mem
  token="$(sonar_token)"
  [ -n "$token" ] || sonar_die "분석 토큰이 없다($SONAR_CONFIG_DIR/token). 해결: make sonar-up (처음 띄울 때 만든다)"
  [ "$(sonar_status_value)" = UP ] || sonar_die "SonarQube($SONAR_HOST_URL)가 떠 있지 않다. 해결: make sonar-up"
  cli="$(sonar_cli)"
  # 남은 Gradle 데몬(make test 가 남긴다, 약 800MB)을 먼저 내리고 분석은 데몬 없이 — 5GB VM 에서 SonarQube 와 같이 돈다
  (cd "$ROOT/backend" && ./gradlew --stop -q >/dev/null 2>&1) || true
  sonar_log "백엔드(board-backend): gradle test + JaCoCo XML + sonar (게이트 대기)"
  (cd "$ROOT/backend" && SONAR_TOKEN="$token" SONAR_HOST_URL="$SONAR_HOST_URL" \
    ./gradlew --no-daemon -Pcoverage test jacocoTestReport sonar -Dsonar.qualitygate.wait=true --console=plain) || rc_b=$?
  sonar_log "프론트(board-frontend): vitest 커버리지(lcov) + sonar-scanner 컨테이너 (게이트 대기)"
  if ! "$ROOT/scripts/frontend-deps.sh" || ! (cd "$ROOT/frontend" && npm run test:coverage); then
    rc_f=1
  else
    if ! "$cli" image inspect "$SONAR_SCANNER_IMAGE" >/dev/null 2>&1; then
      "$cli" build -t "$SONAR_SCANNER_IMAGE" -f "$ROOT/deploy/sonar/scanner/Containerfile" "$ROOT/deploy/sonar/scanner" || rc_f=1
    fi
    # 저장소 전체를 붙인다(스캐너가 .git 으로 새 코드 줄을 가른다). 토큰은 이름만 넘겨 환경변수로(인자에 값이 없다)
    [ "$rc_f" != 0 ] || SONAR_TOKEN="$token" SONAR_HOST_URL="$SONAR_HOST_URL" "$cli" run --rm --network host --security-opt label=disable \
      -e SONAR_TOKEN -e SONAR_HOST_URL -v "$ROOT:/usr/src" -w /usr/src/frontend "$SONAR_SCANNER_IMAGE" \
      -Dsonar.projectBaseDir=/usr/src/frontend -Dsonar.qualitygate.wait=true || rc_f=$?
  fi
  echo "==> [sonar] 결과: board-backend $([ "$rc_b" = 0 ] && echo 통과 || echo "실패(rc=$rc_b)") · board-frontend $([ "$rc_f" = 0 ] && echo 통과 || echo "실패(rc=$rc_f)") — $SONAR_HOST_URL/projects" >&2
  [ "$rc_b" = 0 ] && [ "$rc_f" = 0 ]
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
  local rc=0; sonar_running || rc=$?
  [ "$rc" = 2 ] && sonar_die "컨테이너 목록($(sonar_cli) ps)을 확인하지 못했다"
  if [ "$rc" != 0 ]; then
    echo "SonarQube($SONAR_PROJECT): 꺼져 있다 — 띄우기: make sonar-up"
    return 0
  fi
  echo "SonarQube($SONAR_PROJECT): 실행 중 (서버 상태: $(sonar_status_value | grep . || echo '응답 없음'))"
  echo "  접속: $SONAR_TUNNEL_HINT (9000 은 VM 루프백에만) — 관리자 admin, 비밀번호는 출력하지 않는다: ssh $VM_SSH_HOST cat ~/.config/board-sonar/admin-password"
  echo "  컨테이너 메모리:"
  local ids; mapfile -t ids < <(sonar_ids_by_label "com.docker.compose.project=$SONAR_PROJECT")
  "$(sonar_cli)" stats --no-stream --format '{{.Name}} {{.MemUsage}}' "${ids[@]}" 2>/dev/null | sed 's/^/    /' || true
}

case "$cmd" in
  up) cmd_up ;;
  down) cmd_down "${2:-}" ;;
  status) cmd_status ;;
  scan) cmd_scan ;;
  init) cmd_init ;;
esac
