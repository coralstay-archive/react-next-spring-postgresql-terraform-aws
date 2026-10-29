#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2015 # sh -c '...' 안의 $ 는 하위 셸에서 풀린다, A && B || C 는 실패 출력용
# SonarQube 로컬·CI 테스트 (TASK-67). scripts/test.sh 가 호출한다.
# 진짜 SonarQube·Podman·UTM·ssh 는 부르지 않는다(PATH 앞에 둔 가짜 podman·curl·utmctl·ssh·systemctl·pgrep, 임시 HOME·설정 디렉터리).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

failures=0
passes=0
OUT=""
RC=0

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
need_linter() {
  if command -v "$1" >/dev/null 2>&1; then return 0; fi
  if [ "${REQUIRE_LINTERS:-0}" = 1 ]; then echo "  FAIL - $1 이 없다(REQUIRE_LINTERS=1)"; failures=$((failures + 1)); else echo "  WARNING: $1 이 없어 건너뛴다" >&2; fi
  return 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAKE="$TMP/fake"
mkdir -p "$FAKE/bin" "$FAKE/state"
export FAKE_STATE="$FAKE/state"
calls() { cat "$FAKE_STATE/calls.log" 2>/dev/null || true; }
called() { grep -qF -- "$1" "$FAKE_STATE/calls.log" 2>/dev/null || { echo "호출 없음: $1"; echo "--- calls:"; calls; echo "--- output:"; echo "$OUT"; return 1; }; }
not_called() { ! grep -qF -- "$1" "$FAKE_STATE/calls.log" 2>/dev/null || { echo "호출되면 안 됨: $1"; echo "--- calls:"; calls; return 1; }; }
FAKE_HOME="$TMP/home"
fake_reset() { rm -rf "$FAKE_STATE" "$FAKE_HOME"; mkdir -p "$FAKE_STATE" "$FAKE_HOME"; : >"$FAKE_STATE/calls.log"; }

echo "sonar tests"

# ---------------------------------------------------------------- 가짜 명령
# 가짜 podman: compose up → state/sonar(실행 중), compose down → 지움. ps --filter label=… → 라벨별 id(FAKE_TC·FAKE_K3D, board-sonar 는 state)
# stats → FAKE_STATS 줄. 그 밖(build·run·image exists)은 기록만, FAKE_PODMAN_RUN_RC 로 run 의 종료 코드
cat >"$FAKE/bin/podman" <<'SH'
#!/usr/bin/env bash
printf 'podman %s\n' "$*" >>"$FAKE_STATE/calls.log"
case "$1" in
  compose)
    case " $* " in
      *" stop "*) rm -f "$FAKE_STATE/sonar" ;;
      *" up "*) [ "${FAKE_COMPOSE_FAIL:-0}" = 1 ] && { echo "Error: fake compose up failed" >&2; exit 1; }; echo running >"$FAKE_STATE/sonar" ;;
      *" down "*) rm -f "$FAKE_STATE/sonar" ;;
      *" ps "*) [ -f "$FAKE_STATE/sonar" ] && printf 'board-sonar-sonarqube-1 running\nboard-sonar-db-1 running\n' ;;
    esac ;;
  ps)
    [ "${FAKE_PS_FAIL:-0}" = 1 ] && { echo "Error: fake podman ps failed" >&2; exit 125; }
    case " $* " in
      *"com.docker.compose.service=sonarqube"*) [ -f "$FAKE_STATE/sonar" ] && [ "${FAKE_SONARQUBE_EXITED:-0}" != 1 ] && echo aaa111 ;;
      *"com.docker.compose.project=board-sonar"*) [ -f "$FAKE_STATE/sonar" ] && printf 'aaa111\nbbb222\n' ;;
      *"org.testcontainers=true"*) for id in ${FAKE_TC:-}; do echo "$id"; done ;;
      *"k3d.cluster"*) for id in ${FAKE_K3D:-}; do echo "$id"; done ;;
    esac ;;
  stats) printf '%s\n' "${FAKE_STATS:-board-sonar-sonarqube-1 1.9GB / 4.6GB}" ;;
  image) [ "${2:-}" = inspect ] && exit "${FAKE_IMAGE_EXISTS_RC:-1}" ;;
  run) printf 'podman-run-env SONAR_TOKEN=%s\n' "${SONAR_TOKEN:+set}" >>"$FAKE_STATE/calls.log"; exit "${FAKE_PODMAN_RUN_RC:-0}" ;;
esac
exit 0
SH
# 가짜 curl(SonarQube Web API): 경로만 기록한다(비밀값은 -K 설정 파일로 받으므로 인자에 없다).
#  /api/system/status → FAKE_SONAR_STATUS(기본 UP)
#  /api/authentication/validate → -K 의 user 가 관리자(state/adminpw, 기본 admin) 또는 토큰(state/token)과 맞으면 valid
#  /api/users/change_password → previousPassword 가 맞으면 state/adminpw 를 password 로
#  /api/user_tokens/generate → 새 토큰(state/token)을 JSON 으로
cat >"$FAKE/bin/curl" <<'SH'
#!/usr/bin/env bash
S="$FAKE_STATE"
url=""; cfg=""; argv="$*"
while [ $# -gt 0 ]; do case "$1" in -K|--config) cfg="$2"; shift 2 ;; -o|-w|-H|-m|--max-time|--data-urlencode|-d|--data|-u|--user|-X) shift 2 ;; -*) shift ;; *) url="$1"; shift ;; esac; done
path="/${url#*://*/}"; path="${path%%\?*}"
printf 'curl %s\n' "$path" >>"$S/calls.log"
printf 'curl-argv %s\n' "$argv" >>"$S/argv.log"
conf=""; [ -n "$cfg" ] && conf="$(cat "$cfg")"
printf '%s\n' "$conf" >>"$S/curl-config.log"
# curl 설정 값의 이스케이프(\\ \\")를 푼다
unesc() { sed -e 's/\\"/"/g' -e 's/\\\\/\\/g'; }
val() { sed -n "s/^$1 = \"\\(.*\\)\"\$/\\1/p" <<<"$conf" | head -n1 | unesc; }
form() { sed -n "s/^data-urlencode = \"$1=\\(.*\\)\"\$/\\1/p" <<<"$conf" | head -n1 | unesc; }
adminpw="$(cat "$S/adminpw" 2>/dev/null || echo admin)"; token="$(cat "$S/token" 2>/dev/null || true)"
user="$(val user)"
authed() { [ "$user" = "admin:$adminpw" ] || { [ -n "$token" ] && [ "$user" = "$token:" ]; }; }
case "$path" in
  /api/system/status) [ "${FAKE_SONAR_DOWN:-0}" = 1 ] && exit 7; printf '{"status":"%s"}' "${FAKE_SONAR_STATUS:-UP}" ;;
  /api/authentication/validate) if authed; then echo '{"valid":true}'; else echo '{"valid":false}'; fi ;;
  /api/users/change_password)
    authed || { echo '{"errors":[{"msg":"Unauthorized"}]}'; exit 22; }
    [ "$(form previousPassword)" = "$adminpw" ] || { echo '{"errors":[{"msg":"Incorrect old password"}]}'; exit 22; }
    form password >"$S/adminpw" ;;
  /api/user_tokens/generate)
    authed || exit 22
    t="squ_FAKE$RANDOM$RANDOM"; printf '%s' "$t" >"$S/token"; printf '{"login":"admin","name":"%s","token":"%s"}' "$(form name)" "$t" ;;
  /api/user_tokens/revoke | /api/new_code_periods/set) authed || exit 22 ;;
  # 품질 게이트(CI 래칫): 만든 게이트 이름은 state/gates 에
  /api/qualitygates/show) authed || exit 22; grep -qx "$(sed -n 's/.*[?&]name=\([^&]*\).*/\1/p' <<<"$url")" "$S/gates" 2>/dev/null || exit 22; echo '{}' ;;
  /api/qualitygates/create) authed || exit 22; form name >>"$S/gates"; echo '{}' ;;
  /api/qualitygates/create_condition | /api/qualitygates/select | /api/projects/create) authed || exit 22; echo '{}' ;;
  *) : ;;
esac
SH
# 맥 흉내용 가짜 utmctl·ssh·vm_stat(scripts/_vm.sh vm_proxy_if_mac)
cat >"$FAKE/bin/utmctl" <<'SH'
#!/usr/bin/env bash
case " $* " in *" status "*) echo started ;; esac
SH
cat >"$FAKE/bin/ssh" <<'SH'
#!/usr/bin/env bash
printf 'ssh %s\n' "$*" >>"$FAKE_STATE/calls.log"
case "$*" in
  *meminfo*) echo "${FAKE_VM_MEM_KB:-4760000}" ;;
  *"-E 75"*) exit "$([ "${FAKE_LOCK_BUSY:-0}" = 1 ] && echo 75 || echo 0)" ;;
  *"com.docker.compose.project=board-sonar"*) [ -n "${FAKE_VM_SONAR:-}" ] && echo "$FAKE_VM_SONAR"; exit "${FAKE_VM_PS_RC:-0}" ;;
  *findmnt*) exit 0 ;;
  *"frontend/node_modules"*) exit 0 ;;
  *"scripts/sonar.sh"*) echo "FAKE-REMOTE-SONAR"; exit "${FAKE_REMOTE_RC:-0}" ;;
  *) exit 0 ;;
esac
SH
cat >"$FAKE/bin/vm_stat" <<'SH'
#!/usr/bin/env bash
printf 'Mach Virtual Memory Statistics: (page size of 16384 bytes)\nPages free: 40000.\nPages inactive: 30000.\nPages speculative: 2000.\n'
SH
# 가짜 systemctl·pgrep(k3s 실행 여부): FAKE_K3S=1 이면 k3s 가 떠 있다
cat >"$FAKE/bin/systemctl" <<'SH'
#!/usr/bin/env bash
case " $* " in *" is-active "*" k3s "*) [ "${FAKE_K3S:-0}" = 1 ] && exit 0; exit 3 ;; esac
exit 3
SH
cat >"$FAKE/bin/pgrep" <<'SH'
#!/usr/bin/env bash
exit 1
SH
cat >"$FAKE/bin/hostname" <<'SH'
#!/usr/bin/env bash
echo "192.168.64.77 10.88.0.1"
SH
chmod +x "$FAKE/bin/"*

# 가짜 meminfo(MemAvailable). VM 5GB 의 실측과 비슷한 기본값
MEMINFO="$TMP/meminfo"
fake_meminfo() { printf 'MemTotal:        4760000 kB\nMemFree:          800000 kB\nMemAvailable:    %s kB\n' "$1" >"$MEMINFO"; }
fake_meminfo 3500000
LOCK="$TMP/board-repo-test.lock"
# sonar_run <env...> <명령...>: 리눅스(VM 안) 흉내 — 가짜 PATH·임시 HOME·설정 디렉터리, 기다림은 짧게
sonar_run() {
  env -u SONAR_TOKEN -u SONAR_HOST_URL -u SONAR_LOCK_HELD -u SONAR_CONFIG_DIR \
    PATH="$FAKE/bin:$PATH" HOME="$FAKE_HOME" VM_HOST_OS=Linux SONAR_CONTAINER_CLI=podman SONAR_MEMINFO="$MEMINFO" \
    VM_LOCK_FILE="$LOCK" SONAR_WAIT_TIMEOUT=3 SONAR_WAIT_INTERVAL=0 "$@"
}
# 맥 흉내(VM_HOST_OS=Darwin) — vm_proxy_if_mac 가 ssh rocky-dev 로 보낸다
mac_run() {
  env PATH="$FAKE/bin:$PATH" HOME="$FAKE_HOME" VM_HOST_OS=Darwin UTMCTL="$FAKE/bin/utmctl" VM_SHARE_DIR="$ROOT" \
    VM_CACHE_DIR="$FAKE_HOME/cache" "$@"
}

C=deploy/sonar/compose.yaml
S=scripts/sonar.sh
L=scripts/_sonar.sh
I=deploy/rocky/install.sh

# ================================================================ TASK-67#1 compose(다이제스트 고정) + make sonar-up·sonar-down·sonar-status
check "TASK-67#1 ${C}·${S}·${L} 존재, sonar.sh 는 실행 가능" sh -c "test -f $C && test -x $S && test -f $L && bash -n $S && bash -n $L"
if need_linter yq; then
  t_compose_images() {
    local sq db
    sq="$(yq -r '.services.sonarqube.image' $C)"; db="$(yq -r '.services.db.image' $C)"
    [[ "$sq" =~ ^docker\.io/library/sonarqube:[0-9.]+-community@sha256:[0-9a-f]{64}$ ]] || { echo "sonarqube 이미지가 Community Build 다이제스트 고정이 아니다: $sq"; return 1; }
    [[ "$db" =~ ^docker\.io/library/postgres:18-alpine@sha256:[0-9a-f]{64}$ ]] || { echo "db 이미지가 postgres:18-alpine 다이제스트 고정이 아니다: $db"; return 1; }
  }
  check "TASK-67#1 compose: SonarQube Community Build·postgres:18-alpine 를 태그@sha256 다이제스트로 고정" t_compose_images
  t_compose_ports() {
    [ "$(yq -r '.name' $C)" = board-sonar ] || { echo "프로젝트 이름(name)이 board-sonar 가 아니다"; return 1; }
    # VM 루프백에만 연다 — 자격 증명을 바꾸기 전(admin/admin)에도 밖에서 붙지 못한다. 맥은 ssh 터널로(TASK-67 리뷰)
    [ "$(yq -r '.services.sonarqube.ports | join(",")' $C)" = "127.0.0.1:9000:9000" ] || { echo "sonarqube 포트는 127.0.0.1:9000:9000 하나"; return 1; }
    [ "$(yq -r '.services.db | has("ports")' $C)" = false ] || { echo "db 포트를 열면 안 된다"; return 1; }
    # 데이터는 VM 로컬 이름 있는 볼륨(SELinux 레이블 문제 없음, sonar-down 은 지우지 않는다)
    local v; for v in sonarqube_data sonarqube_extensions sonar_db; do
      [ "$(yq -r ".volumes | has(\"$v\")" $C)" = true ] || { echo "볼륨 $v 없음"; return 1; }
    done
    yq -r '.services.sonarqube.volumes[]' $C | grep -q '^sonarqube_data:/opt/sonarqube/data$' || { echo "sonarqube 데이터 볼륨"; return 1; }
    yq -r '.services.db.volumes[]' $C | grep -q '^sonar_db:/var/lib/postgresql$' || { echo "db 볼륨(postgres 18 은 /var/lib/postgresql)"; return 1; }
    [ "$(yq -r '.services.sonarqube.depends_on.db.condition' $C)" = service_healthy ] || { echo "db 준비 뒤 기동"; return 1; }
    [ "$(yq -r '.services.sonarqube.environment.SONAR_JDBC_URL' $C)" = "jdbc:postgresql://db:5432/sonar" ] || { echo "JDBC URL"; return 1; }
  }
  check "TASK-67#1 compose: 프로젝트 board-sonar, 9000 은 VM 루프백(127.0.0.1)에만(db 비공개), 이름 있는 볼륨, db healthy 뒤 기동" t_compose_ports
fi
check "TASK-67#1 install.sh dev: compose 공급자(docker-compose 플러그인)를 버전·sha256 고정으로 podman 이 찾는 곳에 설치" sh -c "
  grep -Eq '^COMPOSE_VERSION=\"?[0-9]+\.[0-9]+\.[0-9]+\"?' $I && grep -Eq '^COMPOSE_SHA256=\"?[0-9a-f]{64}\"?' $I \
    && grep -q 'docker-compose-linux-aarch64\" \"\$COMPOSE_SHA256\"' $I && grep -q '/usr/local/lib/docker/cli-plugins/docker-compose' $I \
    && grep -q 'installed \"compose-\$COMPOSE_VERSION\"' $I"
for t in sonar-up sonar-down sonar-status; do
  check "TASK-67#1 make $t → scripts/sonar.sh ${t#sonar-}" sh -c "grep -Eq '^$t:.*## ' Makefile && make -n $t | grep -q 'scripts/sonar.sh ${t#sonar-}'"
done
t_up_linux() {
  fake_reset
  run_capture sonar_run "$S" up
  rc_is 0 && called "podman compose -p board-sonar -f $ROOT/$C up -d" && called "curl /api/system/status" \
    && has_text "http://localhost:9000" && has_text "ssh -N -L 9000:localhost:9000 rocky-dev"
}
check "TASK-67#1 sonar.sh up(VM 안): podman compose -p board-sonar up -d → /api/system/status UP 대기 → 접속(맥 ssh 터널 → http://localhost:9000)" t_up_linux
t_up_wait_timeout() {
  fake_reset
  run_capture sonar_run FAKE_SONAR_STATUS=STARTING SONAR_WAIT_TIMEOUT=1 "$S" up
  [ "$RC" != 0 ] && has_text "STARTING" && has_text "podman logs"
}
check "TASK-67#1 sonar.sh up: 제한 시간 안에 UP 이 안 되면 마지막 상태와 로그 보는 법을 알리고 실패" t_up_wait_timeout
t_up_exited() {
  # Elasticsearch 부트스트랩 검사 실패 등으로 컨테이너가 끝나면 제한 시간(여기 60초)을 다 기다리지 않고 바로 실패한다
  fake_reset
  run_capture sonar_run FAKE_SONAR_DOWN=1 FAKE_SONARQUBE_EXITED=1 SONAR_WAIT_TIMEOUT=60 timeout 20 "$S" up
  [ "$RC" != 0 ] && [ "$RC" != 124 ] && has_text "멈췄다" && has_text "vm.max_map_count"
}
check "TASK-67#1 sonar.sh up: sonarqube 컨테이너가 끝나 버리면 기다리지 않고 로그·원인(vm.max_map_count)을 안내하고 실패" t_up_exited
t_down_keeps_data() {
  fake_reset; echo running >"$FAKE_STATE/sonar"
  run_capture sonar_run "$S" down
  rc_is 0 && called "podman compose -p board-sonar -f $ROOT/$C down" && not_called "down -v" && has_text "메모리"
}
check "TASK-67#1 sonar.sh down: compose down(볼륨은 남긴다 — 분석 이력 유지)" t_down_keeps_data
t_down_purge() {
  fake_reset; echo running >"$FAKE_STATE/sonar"; mkdir -p "$FAKE_HOME/.config/board-sonar"; echo x >"$FAKE_HOME/.config/board-sonar/token"
  run_capture sonar_run "$S" down --purge
  rc_is 0 && called "down -v" && [ ! -e "$FAKE_HOME/.config/board-sonar" ]
}
check "TASK-67#1 sonar.sh down --purge: 볼륨과 VM 설정(~/.config/board-sonar)까지 지운다" t_down_purge
t_status() {
  fake_reset; echo running >"$FAKE_STATE/sonar"
  run_capture sonar_run "$S" status
  rc_is 0 && has_text "실행 중" && has_text "ssh -N -L 9000:localhost:9000 rocky-dev" && has_text "1.9GB"
}
check "TASK-67#1 sonar.sh status: 실행 여부·접속 방법(ssh 터널)·컨테이너 메모리" t_status
t_status_stopped() {
  fake_reset
  run_capture sonar_run "$S" status
  rc_is 0 && has_text "꺼져 있다" && has_text "make sonar-up"
}
check "TASK-67#1 sonar.sh status: 꺼져 있으면 make sonar-up 안내" t_status_stopped
t_mac_proxy() {
  fake_reset
  run_capture mac_run "$S" status
  rc_is 0 && called "ssh" && grep -q "scripts/sonar.sh.*status" "$FAKE_STATE/calls.log" && not_called "podman"
}
check "TASK-67#1 맥에서는 VM 사전 검사 뒤 VM 안에서 돈다(vm_proxy_if_mac, 맥에서 podman 을 부르지 않는다)" t_mac_proxy
if need_linter shellcheck; then
  check "TASK-67#1 shellcheck $S $L" shellcheck -x "$S" "$L"
fi

# ================================================================ TASK-67#2 단독 모드(B1): k3s·make test·메모리 부족이면 이유·해결을 출력하고 실패
B=deploy/k8s/budgets.txt
budget() { awk -v k="$1" '$1 == k { print $2; exit }' "$B"; }
check "TASK-67#2 budgets.txt 에 sonar(기동 전 VM 사용 가능 메모리 최소)·sonar-rss(실측 RSS) 예산" sh -c "
  grep -Eq '^sonar [0-9]+ ' $B && grep -Eq '^sonar-rss [0-9]+ ' $B"
check "TASK-67#2 sonar 예산은 실측 RSS 보다 크고 vm-min 보다 작다" sh -c "
  s=\$(awk '\$1==\"sonar\"{print \$2}' $B); r=\$(awk '\$1==\"sonar-rss\"{print \$2}' $B); v=\$(awk '\$1==\"vm-min\"{print \$2}' $B)
  [ \"\$s\" -gt \"\$r\" ] && [ \"\$s\" -lt \"\$v\" ]"
if command -v yq >/dev/null 2>&1; then
  t_heap() {
    local k v
    for k in SONAR_WEB_JAVAOPTS SONAR_CE_JAVAOPTS SONAR_SEARCH_JAVAOPTS; do
      v="$(yq -r ".services.sonarqube.environment.$k" $C)"
      [[ "$v" =~ -Xmx([0-9]+)m ]] || { echo "$k 에 -Xmx 없음: $v"; return 1; }
      [ "${BASH_REMATCH[1]}" -lt 512 ] || { echo "$k 가 기본(512m)보다 작지 않다: $v"; return 1; }
    done
  }
  check "TASK-67#2 힙 축소: SONAR_WEB·CE·SEARCH_JAVAOPTS 의 -Xmx 가 기본 512m 보다 작다" t_heap
fi
t_b1_k3s() {
  fake_reset
  run_capture sonar_run FAKE_K3S=1 "$S" up
  rc_is 1 && has_text "k3s" && has_text "해결" && not_called "compose -p board-sonar -f $ROOT/$C up"
}
check "TASK-67#2 TASK-67#B1 k3s 가 실행 중이면 띄우지 않고 이유·해결을 출력하고 실패" t_b1_k3s
t_b1_k3d() {
  fake_reset
  run_capture sonar_run FAKE_K3D="k3dserver" "$S" up
  rc_is 1 && has_text "k3d" && has_text "make k3d-stop" && not_called "compose -p board-sonar -f $ROOT/$C up"
}
check "TASK-67#2 TASK-67#B1 k3d 클러스터 컨테이너가 떠 있으면 실패(make k3d-stop 안내)" t_b1_k3d
t_b1_lock() {
  fake_reset
  flock "$LOCK" sleep 30 & local holder=$!
  sleep 0.3
  run_capture sonar_run "$S" up
  kill "$holder" 2>/dev/null; wait "$holder" 2>/dev/null
  rc_is 1 && has_text "make test" && has_text "$LOCK" && has_text "해결" && not_called "compose -p board-sonar -f $ROOT/$C up"
}
check "TASK-67#2 TASK-67#B1 make test 잠금(/tmp/board-repo-test.lock)이 잡혀 있으면 실패" t_b1_lock
t_lock_held_by_proxy() {
  # 맥 → VM 프록시는 sonar.sh 를 그 잠금 안에서 돌린다(SONAR_LOCK_HELD=1) — 자기 잠금으로 거부하지 않는다
  fake_reset
  flock "$LOCK" sleep 30 & local holder=$!
  sleep 0.3
  run_capture sonar_run SONAR_LOCK_HELD=1 "$S" up
  kill "$holder" 2>/dev/null; wait "$holder" 2>/dev/null
  rc_is 0
}
check "TASK-67#2 맥에서 보낸 실행(VM 잠금을 프록시가 잡고 있다, SONAR_LOCK_HELD=1)은 잠금 검사를 건너뛴다" t_lock_held_by_proxy
t_mac_lock_busy() {
  fake_reset
  run_capture mac_run FAKE_LOCK_BUSY=1 "$S" up
  [ "$RC" != 0 ] && has_text "/tmp/board-repo-test.lock" && ! grep -q "scripts/sonar.sh" "$FAKE_STATE/calls.log"
}
check "TASK-67#2 TASK-67#B1 맥: VM 안 make test 잠금이 잡혀 있으면 VM 으로 보내지 않고 실패" t_mac_lock_busy
t_mac_sends_lock_held() {
  fake_reset
  run_capture mac_run "$S" up
  rc_is 0 && grep -q "SONAR_LOCK_HELD=1 ./scripts/sonar.sh up" "$FAKE_STATE/calls.log"
}
check "TASK-67#2 맥: VM 으로 보낼 때 SONAR_LOCK_HELD=1 을 붙인다" t_mac_sends_lock_held
t_b1_testcontainers() {
  fake_reset
  run_capture sonar_run FAKE_TC="tc1 tc2" "$S" up
  rc_is 1 && has_text "Testcontainers" && not_called "compose -p board-sonar -f $ROOT/$C up"
}
check "TASK-67#2 TASK-67#B1 Testcontainers 컨테이너(테스트 실행 중)가 있으면 실패" t_b1_testcontainers
t_b1_mem() {
  fake_reset; fake_meminfo 1500000
  run_capture sonar_run "$S" up
  fake_meminfo 3500000
  rc_is 1 && has_text "1464 MiB" && has_text "$(budget sonar) MiB" && has_text "해결" && not_called "compose -p board-sonar -f $ROOT/$C up"
}
check "TASK-67#2 TASK-67#B1 VM 사용 가능 메모리(MemAvailable) < budgets.txt sonar 면 실패" t_b1_mem
t_b1_all_reasons() {
  fake_reset; fake_meminfo 1500000
  run_capture sonar_run FAKE_K3S=1 FAKE_TC=tc1 "$S" up
  fake_meminfo 3500000
  rc_is 1 && has_text "k3s" && has_text "Testcontainers" && has_text "MiB"
}
check "TASK-67#2 이유가 여럿이면 모두 출력한다" t_b1_all_reasons
t_up_already_running() {
  # 이미 떠 있으면 자기 메모리 때문에 거부하지 않는다(멱등)
  fake_reset; echo running >"$FAKE_STATE/sonar"; fake_meminfo 900000
  run_capture sonar_run "$S" up
  fake_meminfo 3500000
  rc_is 0 && has_text "이미"
}
check "TASK-67#2 이미 떠 있으면 메모리 검사 없이 상태만 맞춘다(다시 돌려도 된다)" t_up_already_running
t_refuse_running() {
  fake_reset; echo running >"$FAKE_STATE/sonar"
  run_capture sonar_run "$S" refuse-if-running "make test"
  rc_is 1 && has_text "make test" && has_text "make sonar-down"
}
check "TASK-67#2 TASK-67#B1 역방향: SonarQube 가 떠 있으면 refuse-if-running 은 make sonar-down 을 안내하고 실패" t_refuse_running
t_refuse_not_running() {
  fake_reset
  run_capture sonar_run "$S" refuse-if-running "make test"
  rc_is 0 && lacks_text "ERROR"
}
check "TASK-67#2 역방향: 떠 있지 않으면 통과(런타임이 없어도 — CI)" t_refuse_not_running
t_refuse_no_runtime() {
  fake_reset
  run_capture env PATH="/usr/bin:/bin" HOME="$FAKE_HOME" VM_HOST_OS=Linux SONAR_CONTAINER_CLI= "$ROOT/$S" refuse-if-running "make test"
  rc_is 0
}
check "TASK-67#2 역방향: 컨테이너 런타임이 없어도 통과한다" t_refuse_no_runtime
t_entrypoints_refuse() {
  local f n g
  for f in scripts/test.sh scripts/test-integration.sh; do
    n="$(grep -n 'scripts/sonar.sh" refuse-if-running' "$f" | head -1 | cut -d: -f1)"
    g="$(grep -n 'gradlew' "$f" | head -1 | cut -d: -f1)"
    [ -n "$n" ] && [ "$n" -lt "$g" ] || { echo "$f: gradle 앞에 refuse-if-running 이 없다"; return 1; }
    [ "$n" -gt "$(grep -n 'vm_proxy_if_mac' "$f" | tail -1 | cut -d: -f1)" ] || { echo "$f: VM 안에서(프록시 뒤) 검사해야 한다"; return 1; }
  done
  [ "$(grep -n 'refuse-if-running' scripts/test.sh | head -1 | cut -d: -f1)" -lt "$(grep -n 'repo_test.sh' scripts/test.sh | head -1 | cut -d: -f1)" ] \
    || { echo "test.sh: 첫 스위트보다 먼저"; return 1; }
  for t in backend-test backend-build; do
    make -n "$t" VM_PROXY=0 2>/dev/null | grep -q "scripts/sonar.sh refuse-if-running \"make $t\"" || { echo "make $t: refuse-if-running 없음"; return 1; }
  done
  grep -q 'scripts/sonar.sh" refuse-if-running "make e2e"' scripts/e2e.sh || { echo "e2e.sh"; return 1; }
}
check "TASK-67#2 TASK-67#B1 테스트 진입점(test.sh·test-integration.sh·backend-test·backend-build·e2e)은 SonarQube 가 떠 있으면 거부한다" t_entrypoints_refuse

# ================================================================ TASK-67#3 install.sh dev: vm.max_map_count·fs.file-max·nofile (다시 돌려도 같은 결과)
# ROCKY_INSTALL_LIB=1 로 함수만 읽고 임시 디렉터리·가짜 sysctl 로 부른다. KFILE_MAX: 커널이 지금 보고하는 fs.file-max
SB="$TMP/sysctl"
mkdir -p "$SB/bin"
cat >"$SB/bin/sysctl" <<'SH'
#!/usr/bin/env bash
printf 'sysctl %s\n' "$*" >>"$FAKE_SB/calls.log"
case "$*" in
  "-n fs.file-max") cat "$FAKE_SB/file-max" ;;
  "-n vm.max_map_count") cat "$FAKE_SB/map-count" ;;
  "-p "*) f="${*: -1}"
    v="$(sed -n 's/^vm.max_map_count = //p' "$f")"; [ -n "$v" ] && echo "$v" >"$FAKE_SB/map-count"
    v="$(sed -n 's/^fs.file-max = //p' "$f")"; [ -n "$v" ] && echo "$v" >"$FAKE_SB/file-max"; exit 0 ;;
esac
SH
chmod +x "$SB/bin/sysctl"
sb_reset() { rm -rf "${SB:?}/etc" "$SB/calls.log"; mkdir -p "$SB/etc/sysctl.d" "$SB/etc/security/limits.d"; echo 65530 >"$SB/map-count"; echo "$1" >"$SB/file-max"; }
inst_kernel() {
  env PATH="$SB/bin:$PATH" FAKE_SB="$SB" ROCKY_INSTALL_LIB=1 bash -c '
    . deploy/rocky/install.sh
    SYSCTL_DIR="$FAKE_SB/etc/sysctl.d"; LIMITS_DIR="$FAKE_SB/etc/security/limits.d"; TARGET_USER=rocky
    sonar_kernel_setup'
}
SYSCTL_F="$SB/etc/sysctl.d/99-sonarqube.conf"
LIMITS_F="$SB/etc/security/limits.d/99-sonarqube.conf"
t_kernel_low_filemax() {
  sb_reset 65536
  run_capture inst_kernel
  rc_is 0 || return 1
  grep -qx 'vm.max_map_count = 524288' "$SYSCTL_F" && grep -qx 'fs.file-max = 131072' "$SYSCTL_F" \
    || { echo "--- sysctl:"; cat "$SYSCTL_F"; return 1; }
  grep -qx 'rocky soft nofile 131072' "$LIMITS_F" && grep -qx 'rocky hard nofile 131072' "$LIMITS_F" || { cat "$LIMITS_F"; return 1; }
  [ "$(cat "$SB/map-count")" = 524288 ] && [ "$(cat "$SB/file-max")" = 131072 ] || { echo "적용(sysctl -p) 안 됨"; cat "$SB/calls.log"; return 1; }
  # 다시 돌려도 같은 결과
  cp "$SYSCTL_F" "$TMP/s1"; cp "$LIMITS_F" "$TMP/l1"
  run_capture inst_kernel
  rc_is 0 && diff "$TMP/s1" "$SYSCTL_F" && diff "$TMP/l1" "$LIMITS_F" && [ "$(cat "$SB/file-max")" = 131072 ]
}
check "TASK-67#3 install.sh dev: vm.max_map_count=524288·fs.file-max=131072·nofile 131072 를 쓰고 바로 적용, 다시 돌려도 같은 결과" t_kernel_low_filemax
t_kernel_high_filemax() {
  # 커널 기본 fs.file-max 가 이미 더 크면(Rocky 9: 9223372036854775807) 낮추지 않는다 — SonarQube 요구는 "131072 이상"
  sb_reset 9223372036854775807
  run_capture inst_kernel
  rc_is 0 && grep -qx 'vm.max_map_count = 524288' "$SYSCTL_F" && ! grep -q 'fs.file-max' "$SYSCTL_F" \
    && [ "$(cat "$SB/file-max")" = 9223372036854775807 ] || { cat "$SYSCTL_F"; return 1; }
  cp "$SYSCTL_F" "$TMP/s1"; run_capture inst_kernel
  rc_is 0 && diff "$TMP/s1" "$SYSCTL_F"
}
check "TASK-67#3 install.sh dev: 커널 fs.file-max 가 이미 131072 보다 크면 낮추지 않는다(다시 돌려도 같은 결과)" t_kernel_high_filemax
check "TASK-67#3 install.sh: sonar_kernel_setup 은 dev 프로필(server 종료 뒤)에서만 부른다" sh -c "
  s=\$(grep -n 'PROFILE\" = server' $I | head -n1 | cut -d: -f1); k=\$(grep -n '^sonar_kernel_setup\$' $I | head -n1 | cut -d: -f1)
  [ -n \"\$s\" ] && [ -n \"\$k\" ] && [ \"\$k\" -gt \"\$s\" ]"

# ================================================================ TASK-67#4 관리자 비밀번호·분석 토큰(B2) + make sonar-scan(게이트)
CFG_DIR="$FAKE_HOME/.config/board-sonar"
mode_of() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }
t_b2_first_up() {
  fake_reset
  run_capture sonar_run "$S" up
  rc_is 0 || return 1
  local pw tok
  pw="$(cat "$CFG_DIR/admin-password" 2>/dev/null)"; tok="$(cat "$CFG_DIR/token" 2>/dev/null)"
  [ -n "$pw" ] && [ "$pw" != admin ] && [ "$(cat "$FAKE_STATE/adminpw")" = "$pw" ] || { echo "관리자 비밀번호가 무작위 값으로 바뀌지 않았다"; return 1; }
  [ "${#pw}" -ge 12 ] && [[ "$pw" =~ [A-Z] ]] && [[ "$pw" =~ [a-z] ]] && [[ "$pw" =~ [0-9] ]] && [[ "$pw" =~ [^A-Za-z0-9] ]] \
    || { echo "SonarQube 비밀번호 규칙(12자 이상·대소문자·숫자·특수문자) 위반"; return 1; }
  [ -n "$tok" ] && [ "$tok" = "$(cat "$FAKE_STATE/token")" ] || { echo "토큰이 저장되지 않았다"; return 1; }
  [ "$(mode_of "$CFG_DIR")" = 700 ] && [ "$(mode_of "$CFG_DIR/admin-password")" = 600 ] && [ "$(mode_of "$CFG_DIR/token")" = 600 ] \
    || { echo "권한: dir $(mode_of "$CFG_DIR") pw $(mode_of "$CFG_DIR/admin-password") tok $(mode_of "$CFG_DIR/token")"; return 1; }
  # 출력·curl 인자에 비밀값이 없다(설정은 -K 로 넘긴다)
  lacks_text "$pw" && lacks_text "$tok" && ! grep -qF "$pw" "$FAKE_STATE/argv.log" && ! grep -qF "$tok" "$FAKE_STATE/argv.log" \
    || { echo "비밀값이 출력·인자에 보인다"; return 1; }
  called "curl /api/users/change_password" && called "curl /api/user_tokens/generate" && called "curl /api/new_code_periods/set" \
    && grep -q 'data-urlencode = "type=GLOBAL_ANALYSIS_TOKEN"' "$FAKE_STATE/curl-config.log" \
    && grep -q 'data-urlencode = "type=PREVIOUS_VERSION"' "$FAKE_STATE/curl-config.log" \
    && has_text "cat ~/.config/board-sonar/admin-password"
}
check "TASK-67#4 TASK-67#B2 첫 sonar-up: 관리자 비밀번호를 API 로 무작위 값으로 바꾸고 분석 토큰(GLOBAL_ANALYSIS_TOKEN)을 만들어 VM ~/.config/board-sonar(0700/0600)에만 둔다 — 출력·인자에 없다" t_b2_first_up
t_b2_second_up() {
  fake_reset
  sonar_run "$S" up >/dev/null 2>&1
  : >"$FAKE_STATE/calls.log"
  run_capture sonar_run "$S" up
  rc_is 0 && not_called "curl /api/users/change_password" && not_called "curl /api/user_tokens/generate"
}
check "TASK-67#4 다시 sonar-up: 저장된 비밀번호·토큰이 맞으면 그대로 쓴다(멱등)" t_b2_second_up
t_b2_token_regen() {
  fake_reset
  sonar_run "$S" up >/dev/null 2>&1
  echo "squ_REVOKED" >"$CFG_DIR/token"; : >"$FAKE_STATE/calls.log"
  run_capture sonar_run "$S" up
  rc_is 0 && called "curl /api/user_tokens/generate" && [ "$(cat "$CFG_DIR/token")" = "$(cat "$FAKE_STATE/token")" ]
}
check "TASK-67#4 저장된 토큰이 무효면 새로 만든다" t_b2_token_regen
t_b2_server_reset() {
  # 볼륨이 새로 만들어져 서버가 admin/admin 으로 돌아갔으면 저장된 비밀번호로 다시 바꾼다
  fake_reset
  sonar_run "$S" up >/dev/null 2>&1
  local pw; pw="$(cat "$CFG_DIR/admin-password")"
  rm -f "$FAKE_STATE/adminpw" "$FAKE_STATE/token"
  run_capture sonar_run "$S" up
  rc_is 0 && [ "$(cat "$FAKE_STATE/adminpw")" = "$pw" ] && [ "$(cat "$CFG_DIR/token")" = "$(cat "$FAKE_STATE/token")" ]
}
check "TASK-67#4 서버 데이터가 초기화됐으면(admin/admin) 저장된 비밀번호로 다시 바꾸고 토큰을 새로 만든다" t_b2_server_reset
t_b2_unknown_pw() {
  fake_reset; echo "someone-else-Changed1!" >"$FAKE_STATE/adminpw"
  run_capture sonar_run "$S" up
  rc_is 1 && has_text "PURGE=1" && [ ! -s "$CFG_DIR/token" ]
}
check "TASK-67#4 관리자 비밀번호를 모르면(기본값도 저장값도 아님) 해결법(make sonar-down PURGE=1)을 안내하고 실패" t_b2_unknown_pw
t_b2_not_in_repo() {
  ! git grep -nE 'squ_[A-Za-z0-9]{20,}|sqa_[A-Za-z0-9]{20,}' -- . ':!scripts/test/sonar_test.sh' || { echo "저장소에 토큰 형태 문자열"; return 1; }
  ! grep -n 'SONAR_TOKEN' example.env || { echo "example.env 에 SONAR_TOKEN"; return 1; }
  grep -q 'SONAR_CONFIG_DIR:-$HOME/.config/board-sonar' "$L" || { echo "설정 디렉터리 기본값이 VM HOME 아래가 아니다"; return 1; }
  grep -qxF '.scannerwork/' .gitignore || { echo ".gitignore 에 .scannerwork"; return 1; }
  # 맥 → VM 동기화는 맥 셸의 SONAR_TOKEN 을 넘기지 않는다(프록시 명령에 환경변수가 없다)
  ! grep -n 'SONAR_TOKEN' "$S" | grep -q 'vm_proxy_if_mac'
}
check "TASK-67#B2 토큰은 저장소·.env 에 두지 않는다(설정 디렉터리는 VM HOME, .scannerwork 무시)" t_b2_not_in_repo
t_b2_ci_mask() {
  fake_reset
  run_capture sonar_run GITHUB_ACTIONS=true SONAR_CONFIG_DIR="$TMP/ci-sonar" "$S" init
  rc_is 0 && has_text "::add-mask::" && [ -s "$TMP/ci-sonar/token" ] && not_called "podman compose"
}
check "TASK-67#4 sonar.sh init(CI): 기동 대기 + 비밀번호 교체·토큰(SONAR_CONFIG_DIR), GitHub Actions 에서는 값을 마스킹" t_b2_ci_mask

# --- make sonar-scan: 임시 루트에 스크립트를 복사하고 gradlew·npm·frontend-deps 는 가짜로
TR="$TMP/tr"
mkdir -p "$TR/scripts" "$TR/backend" "$TR/frontend" "$TR/deploy/k8s" "$TR/bin"
cp "$S" "$L" scripts/_vm.sh "$TR/scripts/"; cp -R deploy/sonar "$TR/deploy/"; cp deploy/k8s/budgets.txt "$TR/deploy/k8s/"
cat >"$TR/backend/gradlew" <<'SH'
#!/usr/bin/env bash
printf 'gradlew %s token=%s host=%s\n' "$*" "${SONAR_TOKEN:+set}" "${SONAR_HOST_URL:-}" >>"$FAKE_STATE/calls.log"
case " $* " in *" --stop "*) exit 0 ;; esac
exit "${FAKE_GRADLE_RC:-0}"
SH
printf '#!/usr/bin/env bash\necho "frontend-deps" >>"$FAKE_STATE/calls.log"\n' >"$TR/scripts/frontend-deps.sh"
printf '#!/usr/bin/env bash\necho "npm $* token=${SONAR_TOKEN:+set}" >>"$FAKE_STATE/calls.log"\nexit "${FAKE_NPM_RC:-0}"\n' >"$TR/bin/npm"
chmod +x "$TR/scripts/"*.sh "$TR/backend/gradlew" "$TR/bin/"*
scan_run() { sonar_run PATH="$TR/bin:$FAKE/bin:$PATH" "$@" "$TR/scripts/sonar.sh" scan; }
scan_ready() { fake_reset; sonar_run PATH="$TR/bin:$FAKE/bin:$PATH" "$TR/scripts/sonar.sh" up >/dev/null 2>&1; : >"$FAKE_STATE/calls.log"; }
t_scan_ok() {
  scan_ready
  run_capture scan_run
  rc_is 0 || return 1
  called "gradlew --no-daemon -Pcoverage test jacocoTestReport sonar -Dsonar.qualitygate.wait=true --console=plain token=set host=http://localhost:9000" \
    && called "npm run test:coverage token=" && called "podman build -t board-sonar-scanner:" && called "podman-run-env SONAR_TOKEN=set" \
    && grep -q "^podman run --rm --network host --security-opt label=disable -e SONAR_TOKEN -e SONAR_HOST_URL -v $TR:/usr/src" "$FAKE_STATE/calls.log" \
    && grep -q -- "-Dsonar.projectBaseDir=/usr/src/frontend -Dsonar.qualitygate.wait=true" "$FAKE_STATE/calls.log" \
    && lacks_text "$(cat "$CFG_DIR/token")" && ! grep -qF "$(cat "$CFG_DIR/token")" "$FAKE_STATE/calls.log"
}
check "TASK-67#4 make sonar-scan: 백엔드 gradle(-Pcoverage test jacocoTestReport sonar, 게이트 대기) → 프론트 vitest 커버리지 → 스캐너 컨테이너(게이트 대기), 토큰은 환경변수로만" t_scan_ok
t_scan_gate_fail() {
  scan_ready
  run_capture scan_run FAKE_GRADLE_RC=1
  [ "$RC" != 0 ] && called "podman-run-env" && has_text "board-backend"
}
check "TASK-67#4 게이트 실패(백엔드)면 프론트까지 분석한 뒤 non-zero 로 끝난다" t_scan_gate_fail
t_scan_gate_fail_frontend() {
  scan_ready
  run_capture scan_run FAKE_PODMAN_RUN_RC=3
  [ "$RC" != 0 ] && has_text "board-frontend"
}
check "TASK-67#4 게이트 실패(프론트 스캐너)면 non-zero" t_scan_gate_fail_frontend
t_scan_no_token() {
  fake_reset; echo running >"$FAKE_STATE/sonar"
  run_capture scan_run
  rc_is 1 && has_text "make sonar-up" && not_called "gradlew --no-daemon"
}
check "TASK-67#4 토큰 파일이 없으면 make sonar-up 을 안내하고 실패" t_scan_no_token
t_scan_server_down() {
  scan_ready
  run_capture scan_run FAKE_SONAR_DOWN=1
  rc_is 1 && has_text "make sonar-up" && not_called "gradlew --no-daemon"
}
check "TASK-67#4 SonarQube 가 떠 있지 않으면 실패" t_scan_server_down
t_scan_env_token() {
  # CI: SONAR_TOKEN 환경변수가 있으면 설정 파일 없이 쓴다
  fake_reset; echo running >"$FAKE_STATE/sonar"
  run_capture scan_run SONAR_TOKEN=squ_FROMENV FAKE_IMAGE_EXISTS_RC=0
  rc_is 0 && called "token=set" && not_called "podman build"
}
check "TASK-67#4 SONAR_TOKEN 환경변수(CI)가 있으면 그것을 쓰고, 스캐너 이미지가 있으면 다시 빌드하지 않는다" t_scan_env_token
t_scan_k3s() {
  scan_ready
  run_capture scan_run FAKE_K3S=1
  rc_is 1 && has_text "k3s" && not_called "gradlew --no-daemon"
}
check "TASK-67#4 TASK-67#B1 sonar-scan 도 k3s 가 떠 있으면 거부한다" t_scan_k3s
check "TASK-67#4 make sonar-scan → scripts/sonar.sh scan" sh -c "grep -Eq '^sonar-scan:.*## ' Makefile && make -n sonar-scan | grep -q 'scripts/sonar.sh scan'"
G=backend/build.gradle.kts
check "TASK-67#4 백엔드: org.sonarqube·jacoco 버전 고정, JaCoCo XML, 에이전트는 -Pcoverage 일 때만(-Pcoverage=false 는 끔)(평소 make test 에 부담 없음), projectKey board-backend" sh -c "
  grep -Eq 'id\(\"org.sonarqube\"\) version \"[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+\"' $G && grep -Eq 'toolVersion = \"[0-9]+\.[0-9]+\.[0-9]+\"' $G \
    && grep -q 'xml.required = true' $G && grep -q 'gradleProperty(\"coverage\").map { it != \"false\" }.getOrElse(false)' $G && grep -q 'isEnabled' $G \
    && grep -q '\"sonar.projectKey\", \"board-backend\"' $G"
FP=frontend/sonar-project.properties
check "TASK-67#4 프론트: sonar-project.properties(board-frontend, lcov), vitest 커버리지(v8, lcov)·test:coverage 스크립트" sh -c "
  grep -qx 'sonar.projectKey=board-frontend' $FP && grep -qx 'sonar.javascript.lcov.reportPaths=coverage/lcov.info' $FP \
    && grep -q 'test.inclusions' $FP && grep -q 'provider: \"v8\"' frontend/vitest.config.mts && grep -q '\"lcov\"' frontend/vitest.config.mts \
    && grep -q '\"test:coverage\": \"vitest run --coverage\"' frontend/package.json"
t_cov_pkg_pinned() {
  local v c
  v="$(jq -r '.devDependencies.vitest' frontend/package.json)"; c="$(jq -r '.devDependencies["@vitest/coverage-v8"]' frontend/package.json)"
  [ "$c" = "$v" ] || { echo "@vitest/coverage-v8 범위($c)가 vitest($v)와 다르다"; return 1; }
  [ "$(jq -r '.packages["node_modules/vitest"].version' frontend/package-lock.json)" = "$(jq -r '.packages["node_modules/@vitest/coverage-v8"].version' frontend/package-lock.json)" ] \
    || { echo "lock 의 vitest·coverage-v8 버전이 다르다"; return 1; }
  [ "$(jq -r '.packages[""].devDependencies["@vitest/coverage-v8"]' frontend/package-lock.json)" = "$c" ] || { echo "lock 이 package.json 과 다르다"; return 1; }
}
check "TASK-67#4 @vitest/coverage-v8 은 vitest 와 같은 범위, lock 의 설치 버전도 같다" t_cov_pkg_pinned
SC=deploy/sonar/scanner/Containerfile
check "TASK-67#4 스캐너 컨테이너: 베이스 이미지 다이제스트 고정 + sonar-scanner-cli jar sha256 고정(ADD --checksum) — 공식 이미지는 amd64 뿐이라 VM(arm64)용으로 빌드, JS 분석용 Node(install.sh 와 같은 버전) 복사" sh -c "
  grep -Eq '^FROM docker\.io/library/node:[0-9.]+-bookworm-slim@sha256:[0-9a-f]{64} AS node$' $SC \
    && grep -q '^COPY --from=node /usr/local/bin/node /usr/local/bin/node$' $SC \
    && [ \"\$(sed -nE 's/^FROM docker.io\/library\/node:([0-9.]+)-.*/\1/p' $SC)\" = \"\$(sed -nE 's/^NODE_VERSION=\"?([0-9.]+)\"?.*/\1/p' $I)\" ] \
    && grep -Eq '^FROM docker\.io/library/eclipse-temurin:[0-9]+-jre[a-z-]*@sha256:[0-9a-f]{64}$' $SC \
    && grep -Eq '^ADD --checksum=sha256:[0-9a-f]{64} https://repo1\.maven\.org/maven2/org/sonarsource/scanner/cli/sonar-scanner-cli/' $SC \
    && v=\$(sed -nE 's/^ARG SCANNER_VERSION=([0-9.]+)$/\1/p' $SC) && [ -n \"\$v\" ] && grep -qF \"SONAR_SCANNER_VERSION:-\$v}\" $L"

# ================================================================ TASK-67#5 SonarQube for IDE 연결 모드(.sonarlint) + README
t_connected_mode() {
  local f k u
  for f in .sonarlint/connectedMode.json .sonarlint/frontend.json; do
    git ls-files --error-unmatch "$f" >/dev/null 2>&1 || [ -f "$f" ] || { echo "$f 없음"; return 1; }
    jq -e . "$f" >/dev/null || { echo "$f JSON 아님"; return 1; }
    u="$(jq -r '.sonarQubeUri' "$f")"
    [ "$u" = "http://localhost:9000" ] || { echo "$f sonarQubeUri: $u (ssh 터널 주소 http://localhost:9000 — VM IP 는 DHCP 로 바뀐다)"; return 1; }
  done
  k="$(jq -r '.projectKey' .sonarlint/connectedMode.json)"; [ "$k" = board-backend ] || { echo "connectedMode.json projectKey: $k"; return 1; }
  grep -q "\"sonar.projectKey\", \"$k\"" backend/build.gradle.kts || { echo "백엔드 projectKey 가 다르다"; return 1; }
  k="$(jq -r '.projectKey' .sonarlint/frontend.json)"; [ "$k" = board-frontend ] || { echo "frontend.json projectKey: $k"; return 1; }
  grep -qx "sonar.projectKey=$k" frontend/sonar-project.properties || { echo "프론트 projectKey 가 다르다"; return 1; }
  ! git check-ignore -q .sonarlint/connectedMode.json || { echo ".sonarlint 가 gitignore 된다"; return 1; }
}
check "TASK-67#5 .sonarlint/connectedMode.json(board-backend)·frontend.json(board-frontend): projectKey 는 분석 설정과 같고 주소는 http://localhost:9000(ssh 터널)" t_connected_mode
readme_sonar_section() { awk '/^## 코드 품질 \(SonarQube/{f=1; print; next} f && /^## /{exit} f' README.md; }
for w in "make sonar-up" "make sonar-scan" "make sonar-down" "make sonar-status" "PURGE=1" "ssh -N -L 9000:localhost:9000 rocky-dev" \
  "VS Code" "IntelliJ" "connectedMode.json" "cat ~/.config/board-sonar/admin-password" "PREVIOUS_VERSION" "단독" "vm.max_map_count" \
  "budgets.txt" ".github/workflows/sonar.yml"; do
  check "TASK-67#5 README '코드 품질 (SonarQube)' 절에 '$w'" sh -c "$(declare -f readme_sonar_section); readme_sonar_section | grep -qF -- '$w'"
done
t_readme_budget_numbers() {
  local sec; sec="$(readme_sonar_section)"
  grep -qF "$(budget sonar) MiB" <<<"$sec" && grep -qF "$(budget sonar-rss) MiB" <<<"$sec" || { echo "README 숫자와 budgets.txt(sonar·sonar-rss)가 다르다"; return 1; }
}
check "TASK-67#5 README 의 메모리 숫자는 budgets.txt(sonar·sonar-rss)와 같다" t_readme_budget_numbers

# ================================================================ TASK-67#6 CI sonar.yml: 서비스 컨테이너 SonarQube·Postgres → 분석·게이트
W=.github/workflows/sonar.yml
check "TASK-67#6 $W 존재" test -f "$W"
if need_linter actionlint; then
  check "TASK-67#6 actionlint $W" actionlint -shellcheck "$(command -v shellcheck || true)" "$W"
fi
if command -v yq >/dev/null 2>&1; then
  t_wf_services() {
    local j='.jobs.sonar'
    [ "$(yq -r "$j.services.sonarqube.image" $W)" = "$(yq -r '.services.sonarqube.image' $C)" ] || { echo "sonarqube 이미지가 compose 와 다르다"; return 1; }
    [ "$(yq -r "$j.services.sonar-db.image" $W)" = "$(yq -r '.services.db.image' $C)" ] || { echo "db 이미지가 compose 와 다르다"; return 1; }
    [ "$(yq -r "$j.services.sonarqube.env.SONAR_JDBC_URL" $W)" = "jdbc:postgresql://sonar-db:5432/sonar" ] || { echo "JDBC URL"; return 1; }
    [ "$(yq -r "$j.services.sonarqube.ports[0]" $W)" = "9000:9000" ] || { echo "포트 9000"; return 1; }
    # 러너의 vm.max_map_count 는 서비스가 뜨기 전에 바꿀 수 없다 — 내장 ES 부트스트랩 검사를 끈다(일회성 CI 서버)
    [ "$(yq -r "$j.services.sonarqube.env.SONAR_ES_BOOTSTRAP_CHECKS_DISABLE" $W)" = true ] || { echo "ES 부트스트랩 검사"; return 1; }
  }
  check "TASK-67#6 서비스 컨테이너: SonarQube·Postgres(로컬 compose 와 같은 다이제스트), 9000, ES 부트스트랩 검사 끔" t_wf_services
  t_wf_perms() {
    [ "$(yq -r '.permissions | to_entries | map(.key + "=" + .value) | join(",")' $W)" = "contents=read" ] \
      && [ "$(yq -r '[.jobs[] | select(has("permissions"))] | length' $W)" = 0 ]
  }
  check "TASK-67#6 권한 최소: contents: read 만" t_wf_perms
  t_wf_steps() {
    local runs; runs="$(yq -r '.jobs.sonar.steps[] | (.uses // "") + " " + (.run // "")' $W)"
    grep -q 'actions/checkout@v7' <<<"$runs" && [ "$(yq -r '.jobs.sonar.steps[] | select(.uses == "actions/checkout@v7") | .with["fetch-depth"]' $W)" = 0 ] \
      || { echo "checkout(fetch-depth 0 — 새 코드 판별에 git 이력)"; return 1; }
    grep -q 'actions/setup-java@v6' <<<"$runs" && grep -q 'actions/setup-node@v7' <<<"$runs" || { echo "ci.yml 과 같은 setup 액션"; return 1; }
    grep -q 'scripts/sonar.sh init' <<<"$runs" && grep -q 'scripts/sonar.sh scan' <<<"$runs" || { echo "init → scan"; return 1; }
    [ "$(grep -n 'sonar.sh init' <<<"$runs" | cut -d: -f1)" -lt "$(grep -n 'sonar.sh scan' <<<"$runs" | cut -d: -f1)" ] || { echo "순서"; return 1; }
    grep -q 'SONAR_CONFIG_DIR=$RUNNER_TEMP/sonar' <<<"$runs" || { echo "설정(비밀번호·토큰)은 러너 임시 디렉터리"; return 1; }
  }
  check "TASK-67#6 단계: checkout(전체 이력) → Java·Node → sonar.sh init(실행마다 무작위 관리자 비밀번호·토큰) → sonar.sh scan(게이트)" t_wf_steps
  t_wf_no_secrets() {
    ! grep -n 'secrets\.' $W || { echo "secret 을 쓰지 않는다(실행마다 API 로 만든다 — 포크 PR 도 돈다)"; return 1; }
    local r; r="$(yq -r '.jobs[].steps[].run // ""' $W)"; ! grep -nF '${{' <<<"$r"
  }
  check "TASK-67#6 secret 없이 돈다(비밀번호·토큰은 실행마다 API 로), run 에 \${{ }} 를 넣지 않는다" t_wf_no_secrets
  check "TASK-67#6 트리거: pull_request·workflow_dispatch" sh -c "[ \"\$(yq -r '.on | keys | sort | join(\",\")' $W)\" = pull_request,workflow_dispatch ]"
fi

# ================================================================ TASK-67 리뷰 반영
# --- 자격 증명을 못 맞추면 SonarQube 를 멈춘다(기본 admin/admin 인 채로 두지 않는다)
t_creds_fail_stops() {
  fake_reset; echo "someone-else-Changed1!" >"$FAKE_STATE/adminpw"
  run_capture sonar_run "$S" up
  rc_is 1 && called "compose -p board-sonar -f $ROOT/$C stop" && has_text "멈췄다"
}
check "TASK-67#4 TASK-67#B2 관리자 비밀번호·토큰을 맞추지 못하면 SonarQube 컨테이너를 멈추고 크게 실패한다" t_creds_fail_stops
t_creds_escape() {
  # 사람이 적어 둔 비밀번호에 " \ 가 있어도 curl 설정(-K)이 깨지지 않는다
  fake_reset; mkdir -p "$CFG_DIR"; local pw='pa"ss\wd-Aa1-long'
  printf '%s' "$pw" >"$CFG_DIR/admin-password"; printf '%s' "$pw" >"$FAKE_STATE/adminpw"
  run_capture sonar_run "$S" up
  rc_is 0 && not_called "curl /api/users/change_password" && called "curl /api/user_tokens/generate" \
    && grep -qF 'user = "admin:pa\"ss\\wd-Aa1-long"' "$FAKE_STATE/curl-config.log"
}
check "TASK-67#4 curl 설정 값은 \\ 와 \" 를 이스케이프한다" t_creds_escape
# --- 잠금·런타임 확인이 실패하면 "없음"으로 보지 않는다(실패 쪽으로 닫힌다)
t_lock_check_error() {
  fake_reset
  run_capture sonar_run VM_LOCK_FILE=/nonexistent-dir-board/x.lock "$S" up
  rc_is 1 && has_text "잠금" && has_text "확인하지 못했다" && not_called "compose -p board-sonar -f $ROOT/$C up"
}
check "TASK-67#2 TASK-67#B1 잠금 확인이 0·75 가 아닌 코드로 끝나면(파일을 못 연다 등) 거부한다" t_lock_check_error
t_ps_error_up() {
  fake_reset
  run_capture sonar_run FAKE_PS_FAIL=1 "$S" up
  rc_is 1 && has_text "확인하지 못했다" && not_called "compose -p board-sonar -f $ROOT/$C up"
}
check "TASK-67#2 TASK-67#B1 컨테이너 목록(podman ps)이 실패하면 sonar-up 은 거부한다" t_ps_error_up
t_ps_error_refuse() {
  fake_reset
  run_capture sonar_run FAKE_PS_FAIL=1 "$S" refuse-if-running "make test"
  rc_is 1 && has_text "확인하지 못했다" && has_text "make test"
}
check "TASK-67#2 TASK-67#B1 역방향: podman ps 가 실패하면 '안 떠 있음'으로 보지 않고 거부한다" t_ps_error_refuse
# --- 맥에서 부른 역방향 검사(k3d 진입점 등)는 VM 안 Podman 을 본다
t_refuse_mac() {
  fake_reset
  run_capture mac_run FAKE_VM_SONAR=abc "$S" refuse-if-running "make k3d-up"
  rc_is 1 && has_text "make sonar-down" && has_text "make k3d-up" && called "com.docker.compose.project=board-sonar" || return 1
  fake_reset
  run_capture mac_run "$S" refuse-if-running "make k3d-up"
  rc_is 0 || return 1
  fake_reset
  run_capture mac_run FAKE_VM_PS_RC=125 "$S" refuse-if-running "make k3d-up"
  rc_is 1 && has_text "확인하지 못했다"
}
check "TASK-67#2 TASK-67#B1 맥: refuse-if-running 은 ssh 로 VM 안 SonarQube 를 확인한다(떠 있거나 확인 실패면 거부)" t_refuse_mac
check "TASK-67#2 TASK-67#B1 k3d 진입점(up·profile·smoke)의 k3d_preflight 도 SonarQube 가 떠 있으면 거부한다" \
  grep -q 'scripts/sonar.sh" refuse-if-running' scripts/_k3d.sh
# --- CI 품질 게이트 래칫: 전체 코드 조건(지금 기준선은 통과, 나빠지면 실패)
t_ci_gate() {
  fake_reset
  run_capture sonar_run SONAR_CONFIG_DIR="$TMP/ci-gate" "$S" init
  rc_is 0 || return 1
  local c="$FAKE_STATE/curl-config.log" line
  called "curl /api/projects/create" && called "curl /api/qualitygates/create" && called "curl /api/qualitygates/select" || return 1
  for line in 'name=board-ci-backend' 'name=board-ci-frontend' 'projectKey=board-backend' 'projectKey=board-frontend' 'gateName=board-ci-backend' 'gateName=board-ci-frontend'; do
    grep -qF "data-urlencode = \"$line\"" "$c" || { echo "설정에 $line 없음"; return 1; }
  done
  # 조건: gateName·metric·op·error 가 한 요청에 — 백엔드 커버리지 < 88, 중복 > 3, 버그 > 6, 취약점 > 7 / 프론트 커버리지 < 92, 중복 > 3, 버그·취약점 > 0
  awk '/^user = /{ if (r) print r; r="" } /^data-urlencode/{ sub(/^data-urlencode = "/, ""); sub(/"$/, ""); r = r " " $0 } END { if (r) print r }' "$c" >"$TMP/conds"
  for line in "gateName=board-ci-backend metric=coverage op=LT error=88" "gateName=board-ci-backend metric=duplicated_lines_density op=GT error=3" \
    "gateName=board-ci-backend metric=bugs op=GT error=6" "gateName=board-ci-backend metric=vulnerabilities op=GT error=7" \
    "gateName=board-ci-frontend metric=coverage op=LT error=92" "gateName=board-ci-frontend metric=duplicated_lines_density op=GT error=3" \
    "gateName=board-ci-frontend metric=bugs op=GT error=0" "gateName=board-ci-frontend metric=vulnerabilities op=GT error=0"; do
    grep -qF " $line" "$TMP/conds" || { echo "조건 없음: $line"; cat "$TMP/conds"; return 1; }
  done
  # 다시 돌려도(게이트가 있으면) 만들지 않는다
  : >"$FAKE_STATE/calls.log"
  run_capture sonar_run SONAR_CONFIG_DIR="$TMP/ci-gate" "$S" init
  rc_is 0 && not_called "curl /api/qualitygates/create"
}
check "TASK-67#6 CI(sonar.sh init): 프로젝트별 품질 게이트(board-ci-backend·frontend)를 전체 코드 조건으로 만들어 지정 — 기준선 통과, 나빠지면 실패" t_ci_gate
t_local_no_ci_gate() {
  fake_reset
  run_capture sonar_run "$S" up
  rc_is 0 && not_called "curl /api/qualitygates/create"
}
check "TASK-67#6 로컬 sonar-up 은 CI 래칫 게이트를 만들지 않는다(Sonar way·새 코드)" t_local_no_ci_gate
for w in "board-ci-backend" "88%" "92%" "TASK-38"; do
  check "TASK-67#6 README '코드 품질 (SonarQube)' 절에 '$w'(CI 래칫)" sh -c "$(declare -f readme_sonar_section); readme_sonar_section | grep -qF -- '$w'"
done

echo "sonar tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
