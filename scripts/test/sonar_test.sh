#!/usr/bin/env bash
# shellcheck disable=SC2016 # sh -c '...' 안의 $ 는 하위 셸에서 풀린다
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
      *" up "*) [ "${FAKE_COMPOSE_FAIL:-0}" = 1 ] && { echo "Error: fake compose up failed" >&2; exit 1; }; echo running >"$FAKE_STATE/sonar" ;;
      *" down "*) rm -f "$FAKE_STATE/sonar" ;;
      *" ps "*) [ -f "$FAKE_STATE/sonar" ] && printf 'board-sonar-sonarqube-1 running\nboard-sonar-db-1 running\n' ;;
    esac ;;
  ps)
    case " $* " in
      *"com.docker.compose.service=sonarqube"*) [ -f "$FAKE_STATE/sonar" ] && [ "${FAKE_SONARQUBE_EXITED:-0}" != 1 ] && echo aaa111 ;;
      *"com.docker.compose.project=board-sonar"*) [ -f "$FAKE_STATE/sonar" ] && printf 'aaa111\nbbb222\n' ;;
      *"org.testcontainers=true"*) for id in ${FAKE_TC:-}; do echo "$id"; done ;;
      *"k3d.cluster"*) for id in ${FAKE_K3D:-}; do echo "$id"; done ;;
    esac ;;
  stats) printf '%s\n' "${FAKE_STATS:-board-sonar-sonarqube-1 1.9GB / 4.6GB}" ;;
  image) [ "${2:-}" = exists ] && exit "${FAKE_IMAGE_EXISTS_RC:-1}" ;;
  run) exit "${FAKE_PODMAN_RUN_RC:-0}" ;;
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
val() { sed -n "s/^$1 = \"\\(.*\\)\"\$/\\1/p" <<<"$conf" | head -n1; }
form() { sed -n "s/^data-urlencode = \"$1=\\(.*\\)\"\$/\\1/p" <<<"$conf" | head -n1; }
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
check "TASK-67#1 $C·$S·$L 존재, sonar.sh 는 실행 가능" sh -c "test -f $C && test -x $S && test -f $L && bash -n $S && bash -n $L"
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
    [ "$(yq -r '.services.sonarqube.ports | join(",")' $C)" = "9000:9000" ] || { echo "sonarqube 포트는 9000:9000 하나"; return 1; }
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
  check "TASK-67#1 compose: 프로젝트 board-sonar, 9000 만 공개(db 비공개), 이름 있는 볼륨, db healthy 뒤 기동" t_compose_ports
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
    && has_text "http://192.168.64.77:9000"
}
check "TASK-67#1 sonar.sh up(VM 안): podman compose -p board-sonar up -d → /api/system/status UP 대기 → 접속 주소 http://<VM IP>:9000" t_up_linux
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
  rc_is 0 && has_text "실행 중" && has_text "http://192.168.64.77:9000" && has_text "1.9GB"
}
check "TASK-67#1 sonar.sh status: 실행 여부·접속 주소(VM IP:9000)·컨테이너 메모리" t_status
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

echo "sonar tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
