#!/usr/bin/env bash
# shellcheck disable=SC2016 # proc_sh '...' 안의 $ 는 하위 bash 에서 풀린다
# 로컬 도구 테스트 (TASK-35): build-diagrams --check 의 렌더 환경 고정, e2e 프로세스 정리, 프론트 의존성 갱신, Makefile env 범위.
# scripts/test.sh 가 호출한다. 실제 graphviz·npm·AWS 는 부르지 않는다(PATH 앞에 둔 가짜 명령·임시 디렉터리).
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

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "tools tests"

# ================================================================ TASK-35#1 OPS-38 build-diagrams --check: 고정 환경에서만 바이트 비교 + 차이 목록
# 임시 트리(scripts/build-diagrams.py 복사본 + diagrams/a.dot + README 블록)와 가짜 dot 으로 돌린다.
# 가짜 dot: -V 는 FAKE_DOT_VERSION, -Tsvg 는 .dot 의 첫 label 을 글자로, FAKE_DOT_X 를 x 좌표로 쓴다(글꼴 측정 차이 흉내).
DG="$TMP/dg"
mkdir -p "$DG/bin" "$DG/r/scripts" "$DG/r/diagrams" "$DG/r/docs"
cp scripts/build-diagrams.py "$DG/r/scripts/"
cat >"$DG/bin/dot" <<'SH'
#!/usr/bin/env bash
if [ "${1:-}" = -V ]; then echo "dot - graphviz version ${FAKE_DOT_VERSION:-1.0.0} (20260101.0000)" >&2; exit 0; fi
src="${*: -1}"
label="$(sed -nE 's/.*label="([^"]*)".*/\1/p' "$src" | head -n1)"
# 색(.dot 의 첫 color 센티넬)과 모양(shape=ellipse 면 <ellipse>, 아니면 <polygon>)도 .dot 에서 읽는다
color="$(sed -nE 's/.*color="(#f0[0-9a-f]{4})".*/\1/p' "$src" | head -n1)"
if grep -q 'shape=ellipse' "$src"; then
  shape="<ellipse fill=\"none\" stroke=\"#f00003\" cx=\"${FAKE_DOT_X:-10}\" cy=\"20\" rx=\"5\" ry=\"5\"/>"
else
  shape="<polygon fill=\"none\" stroke=\"#f00003\" points=\"${FAKE_DOT_X:-10},10 20,10 20,30 ${FAKE_DOT_X:-10},30\"/>"
fi
cat <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<svg width="100pt" height="50pt" viewBox="0.00 0.00 100.00 50.00" xmlns="http://www.w3.org/2000/svg">
<g id="graph0" class="graph">
<title>G</title>
<polygon fill="white" stroke="none" points="0,0 100,0 100,50 0,50"/>
<g id="node1" class="node">
<title>a</title>
$shape
<text text-anchor="middle" x="${FAKE_DOT_X:-10}" y="20" font-family="Helvetica" fill="${color:-#f00001}">$label</text>
</g>
</g>
</svg>
EOF
SH
chmod +x "$DG/bin/dot"
printf '// alt: 그림 A\ndigraph G { a [label="hello"]; }\n' >"$DG/r/diagrams/a.dot"
printf '# t\n\n<!--dot:a-->\n<!--/dot:a-->\n' >"$DG/r/README.md"
dg() { PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" "$@"; }
# 고정 환경 = 가짜 dot 1.0.0 + 이 OS. --env 출력의 graphviz 줄만 바꿔 고정값 파일을 만든다
dg_pin() { FAKE_DOT_VERSION="$1" dg --env >"$DG/r/diagrams/render-env.txt"; }
dg_reset() {
  printf '// alt: 그림 A\ndigraph G { a [label="hello"]; }\n' >"$DG/r/diagrams/a.dot"
  dg_pin 1.0.0
  FAKE_DOT_VERSION=1.0.0 FAKE_DOT_X=10 dg >/dev/null
}

t_dg_env() {
  run_capture env FAKE_DOT_VERSION=9.8.7 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --env
  rc_is 0 && has_text "graphviz=9.8.7" && has_text "os=$(uname -s)"
}
check "TASK-35#1 --env 는 렌더 환경(graphviz 버전·OS)을 출력" t_dg_env
t_dg_same() {
  dg_reset
  run_capture env FAKE_DOT_VERSION=1.0.0 FAKE_DOT_X=10 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 0 && has_text "diagrams up to date"
}
check "TASK-35#1 고정 환경 + 변경 없음 → 0" t_dg_same
t_dg_pinned_diff() {
  dg_reset
  run_capture env FAKE_DOT_VERSION=1.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 1 && has_text "docs/diagrams/a.svg" && has_text '+<text text-anchor="middle" x="11"' && has_text '-<text text-anchor="middle" x="10"'
}
check "TASK-35#1 고정 환경에서 SVG 가 다르면 1 + 파일 목록과 diff 를 보인다" t_dg_pinned_diff
t_dg_other_env_coords() {
  dg_reset
  run_capture env FAKE_DOT_VERSION=2.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 0 && has_text "SKIP" && has_text "graphviz=2.0.0" && has_text "graphviz=1.0.0" && lacks_text "out of date"
}
check "TASK-35#1 다른 환경(graphviz 버전)에서 좌표만 다르면 바이트 비교를 건너뛰고 0" t_dg_other_env_coords
t_dg_other_env_text() {
  dg_reset
  printf '// alt: 그림 A\ndigraph G { a [label="changed"]; }\n' >"$DG/r/diagrams/a.dot"
  run_capture env FAKE_DOT_VERSION=2.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 1 && has_text "docs/diagrams/a.svg" && has_text "changed"
}
check "TASK-35#1 다른 환경이어도 글자(.dot 의 라벨)가 다르면 1 + 파일 목록" t_dg_other_env_text
t_dg_other_env_color() {
  # 색 센티넬만 바꾸고 다시 렌더하지 않았다 — 글자는 같아도 var(--d-*) 색 토큰 순서가 다르다
  dg_reset
  printf '// alt: 그림 A\ndigraph G { a [label="hello", fontcolor="#f0000e"]; }\n' >"$DG/r/diagrams/a.dot"
  run_capture env FAKE_DOT_VERSION=2.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 1 && has_text "docs/diagrams/a.svg" && has_text "색 토큰" && has_text "var(--d-warn)" && has_text "var(--d-ink)"
}
check "TASK-35#1 다른 환경이어도 색 센티넬(var(--d-*) 토큰 순서)이 다르면 1 + 파일 목록" t_dg_other_env_color
t_dg_other_env_shape() {
  # 모양만 바꿨다(polygon → ellipse) — 요소 태그 순서와 모양 종류 개수가 다르다
  dg_reset
  printf '// alt: 그림 A\ndigraph G { a [label="hello", shape=ellipse]; }\n' >"$DG/r/diagrams/a.dot"
  run_capture env FAKE_DOT_VERSION=2.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 1 && has_text "docs/diagrams/a.svg" && has_text "요소" && has_text "ellipse"
}
check "TASK-35#1 다른 환경이어도 모양(요소 태그 순서·종류 개수)이 다르면 1 + 파일 목록" t_dg_other_env_shape
t_dg_other_env_doc() {
  dg_reset
  printf '# t\n\n<!--dot:a-->\nold\n<!--/dot:a-->\n' >"$DG/r/README.md"
  run_capture env FAKE_DOT_VERSION=2.0.0 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 1 && has_text "README.md"
}
check "TASK-35#1 다른 환경이어도 문서 블록이 다르면 1" t_dg_other_env_doc
t_dg_no_pin() {
  dg_reset
  rm -f "$DG/r/diagrams/render-env.txt"
  run_capture env FAKE_DOT_VERSION=1.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 0 && has_text "SKIP" && has_text "render-env.txt"
}
check "TASK-35#1 고정값 파일이 없으면 바이트 비교를 건너뛴다(안내)" t_dg_no_pin
check "TASK-35#1 리포에 렌더 환경 고정값(diagrams/render-env.txt: graphviz·os)" sh -c 'grep -Eq "^graphviz=[0-9]+(\.[0-9]+)+$" diagrams/render-env.txt && grep -Eq "^os=[A-Za-z]+$" diagrams/render-env.txt'

# ================================================================ TASK-35#2 OPS-39 e2e 프로세스 정리: 프로세스 그룹(PGID) 단위 TERM → 시간 초과 시 KILL
# scripts/_proc.sh 를 source 해서 가짜 프로세스(손자 포함·TERM 무시)로 확인한다. 포트 기반 kill 은 쓰지 않는다.
# proc_sh <스크립트> — _proc.sh 를 source 한 bash 에서 실행
proc_sh() { bash -c "set -u; source '$ROOT/scripts/_proc.sh' || exit 99; $1"; }
t_proc_kill_escalate() {
  # TERM 을 무시하는 그룹(손자 sleep 도 무시를 물려받는다) → 1초 뒤 KILL, 그룹 전체가 사라진다
  run_capture proc_sh '
    stubborn() { trap "" TERM; sleep 300 & sleep 300; }
    proc_start stubborn; g=$PROC_LAST; sleep 0.5
    proc_stop "$g" 1
    if kill -0 -- "-$g" 2>/dev/null; then echo "group $g alive"; exit 1; fi
    echo stopped'
  rc_is 0 && has_text "stopped" && has_text "KILL"
}
check "TASK-35#2 TERM 을 무시하는 프로세스 그룹은 시간 초과 뒤 KILL 로 끝난다(손자 포함)" t_proc_kill_escalate
t_proc_term() {
  run_capture proc_sh '
    normal() { sleep 300 & sleep 300; }
    proc_start normal; g=$PROC_LAST; sleep 0.5
    start=$(date +%s); proc_stop "$g" 10
    if kill -0 -- "-$g" 2>/dev/null; then echo "group $g alive"; exit 1; fi
    [ $(( $(date +%s) - start )) -lt 5 ] || { echo "too slow"; exit 1; }
    echo stopped'
  rc_is 0 && has_text "stopped" && lacks_text "KILL"
}
check "TASK-35#2 TERM 에 끝나는 그룹은 KILL 없이 바로 끝난다(하위 프로세스 포함)" t_proc_term
t_proc_unrelated() {
  # 기록한 그룹만 끈다: 같은 셸이 따로 띄운 프로세스(다른 그룹)는 살아 있다
  run_capture proc_sh '
    sleep 300 & other=$!
    proc_start sleep 300; proc_start sleep 300; sleep 0.3
    [ "${#PROC_PIDS[@]}" = 2 ] || { echo "PROC_PIDS: ${PROC_PIDS[*]:-}"; kill "$other"; exit 1; }
    gs=("${PROC_PIDS[@]}")
    proc_stop_all 2
    for g in "${gs[@]}"; do kill -0 -- "-$g" 2>/dev/null && { echo "group $g alive"; kill "$other"; exit 1; }; done
    kill -0 "$other" || { echo "unrelated $other killed"; exit 1; }
    kill "$other"; echo ok-unrelated'
  rc_is 0 && has_text "ok-unrelated"
}
check "TASK-35#2 기록한 그룹만 끄고 관계없는 프로세스는 건드리지 않는다" t_proc_unrelated
t_proc_recorded() {
  # E2E_KEEP: 기록 파일(PID + 시작 시각)로 다음 실행이 끈다. 시작 시각이 다르면(PID 재사용) 건드리지 않는다
  local f="$TMP/kept-pids"
  run_capture proc_sh "
    proc_start sleep 300; g=\$PROC_LAST; sleep 0.3
    proc_record '$f'
    grep -q \"^\$g|\" '$f' || { echo 'not recorded'; cat '$f'; exit 1; }
    sleep 300 & other=\$!
    printf '%s|%s\n' \"\$other\" 'Thu Jan  1 00:00:00 1970' >>'$f'
    proc_stop_recorded '$f' 2
    kill -0 -- \"-\$g\" 2>/dev/null && { echo 'recorded group alive'; exit 1; }
    kill -0 \"\$other\" || { echo 'reused pid killed'; exit 1; }
    kill \"\$other\"
    [ ! -e '$f' ] || { echo 'record file left'; exit 1; }
    echo ok-recorded"
  rc_is 0 && has_text "ok-recorded" && has_text "건너뛴다"
}
check "TASK-35#2 기록 파일의 그룹을 끄되 시작 시각이 다른 PID(재사용)는 건너뛴다" t_proc_recorded
check "TASK-35#2 e2e.sh 는 _proc.sh 로 정리하고 포트 기반 kill 을 하지 않는다" sh -c 'grep -q "_proc.sh" scripts/e2e.sh && grep -q "proc_stop_all" scripts/e2e.sh && ! grep -Eq "lsof .*-t|xargs kill|pkill" scripts/e2e.sh'
check "TASK-35#2 e2e.sh: E2E_KEEP 이면 기록하고, 시작할 때 이전 기록을 정리한다" sh -c 'grep -q "proc_record" scripts/e2e.sh && grep -q "proc_stop_recorded" scripts/e2e.sh'

# ================================================================ TASK-35#3 OPS-40 lock 파일(package.json·package-lock.json)이 바뀌면 npm ci
# scripts/frontend-deps.sh 를 임시 frontend 디렉터리(FRONTEND_DIR)와 가짜 npm 으로 돌린다. 가짜 npm ci 는 node_modules 를 새로 만든다.
FD="$TMP/fd"
mkdir -p "$FD/bin" "$FD/frontend"
cat >"$FD/bin/npm" <<'SH'
#!/usr/bin/env bash
echo "npm $*" >>"$FAKE_NPM_LOG"
# 동시에 두 npm ci 가 돌면 OVERLAP 을 남긴다(잠금 확인용). FAKE_NPM_SLEEP 초 동안 설치하는 척한다
if [ "${1:-}" = ci ]; then
  mkdir "$FAKE_NPM_LOG.running" 2>/dev/null || echo "OVERLAP" >>"$FAKE_NPM_LOG"
  sleep "${FAKE_NPM_SLEEP:-0}"
  rmdir "$FAKE_NPM_LOG.running" 2>/dev/null || true
fi
if [ "${FAKE_NPM_FAIL:-0}" = 1 ]; then echo "npm ERR! fake" >&2; exit 1; fi
[ "${1:-}" = ci ] && { rm -rf node_modules; mkdir -p node_modules/pkg; }
exit 0
SH
chmod +x "$FD/bin/npm"
export FAKE_NPM_LOG="$FD/npm.log"
fd() { PATH="$FD/bin:$PATH" FRONTEND_DIR="$FD/frontend" "$ROOT/scripts/frontend-deps.sh" "$@"; }
ci_count() { grep -c '^npm ci' "$FAKE_NPM_LOG" 2>/dev/null || true; }
t_fd_flow() {
  rm -rf "$FD/frontend/node_modules"; : >"$FAKE_NPM_LOG"
  echo '{"name":"x"}' >"$FD/frontend/package.json"; echo '{"lockfileVersion":3,"v":1}' >"$FD/frontend/package-lock.json"
  fd >/dev/null 2>&1 || { echo "first run failed"; return 1; }
  [ "$(ci_count)" = 1 ] || { echo "처음(node_modules 없음): ci $(ci_count)회"; return 1; }
  fd >/dev/null 2>&1 || return 1
  [ "$(ci_count)" = 1 ] || { echo "lock 그대로인데 ci 를 다시 돌렸다"; return 1; }
  echo '{"lockfileVersion":3,"v":2}' >"$FD/frontend/package-lock.json"
  fd >/dev/null 2>&1 || return 1
  [ "$(ci_count)" = 2 ] || { echo "lock 이 바뀌었는데 ci 를 안 돌렸다"; return 1; }
  echo '{"name":"x","dependencies":{}}' >"$FD/frontend/package.json"
  fd >/dev/null 2>&1 || return 1
  [ "$(ci_count)" = 3 ] || { echo "package.json 이 바뀌었는데 ci 를 안 돌렸다"; return 1; }
  fd --force >/dev/null 2>&1 || return 1
  [ "$(ci_count)" = 4 ] || { echo "--force 인데 ci 를 안 돌렸다"; return 1; }
  [ -d "$FD/frontend/node_modules/pkg" ] || { echo "ci 가 frontend 디렉터리에서 돌지 않았다"; return 1; }
}
check "TASK-35#3 node_modules 가 없거나 package.json·package-lock.json 이 바뀌면(또는 --force) npm ci, 그대로면 건너뛴다" t_fd_flow
t_fd_fail() {
  : >"$FAKE_NPM_LOG"
  echo '{"lockfileVersion":3,"v":3}' >"$FD/frontend/package-lock.json"
  run_capture env FAKE_NPM_FAIL=1 PATH="$FD/bin:$PATH" FRONTEND_DIR="$FD/frontend" "$ROOT/scripts/frontend-deps.sh"
  [ "$RC" != 0 ] || { echo "npm ci 실패인데 0"; return 1; }
  fd >/dev/null 2>&1 || return 1
  [ "$(ci_count)" = 2 ] || { echo "실패 뒤 다음 실행이 ci 를 다시 돌리지 않았다($(ci_count))"; return 1; }
}
check "TASK-35#3 npm ci 가 실패하면 실패로 끝나고 스탬프를 남기지 않는다(다음 실행이 다시 ci)" t_fd_fail
t_fd_notice() {
  : >"$FAKE_NPM_LOG"
  echo '{"lockfileVersion":3,"v":4}' >"$FD/frontend/package-lock.json"
  run_capture env PATH="$FD/bin:$PATH" FRONTEND_DIR="$FD/frontend" "$ROOT/scripts/frontend-deps.sh"
  rc_is 0 && has_text "node_modules 를 지우고 다시 만든다" && has_text "dev 서버"
}
check "TASK-35#3 npm ci 전에 node_modules 를 다시 만든다고(실행 중인 dev 서버가 깨질 수 있다) 알린다" t_fd_notice
t_fd_lock_concurrent() {
  # 두 실행이 동시에 lock 변경을 보더라도 npm ci 는 한 번만, 겹치지 않게 돈다(두 번째는 잠금을 기다린 뒤 최신이라 건너뛴다)
  : >"$FAKE_NPM_LOG"
  echo '{"lockfileVersion":3,"v":5}' >"$FD/frontend/package-lock.json"
  local r1 r2
  FAKE_NPM_SLEEP=2 fd >"$TMP/fd1.out" 2>&1 & local p1=$!
  FAKE_NPM_SLEEP=2 fd >"$TMP/fd2.out" 2>&1 & local p2=$!
  wait "$p1"; r1=$?; wait "$p2"; r2=$?
  [ "$r1" = 0 ] && [ "$r2" = 0 ] || { echo "rc $r1 $r2"; cat "$TMP/fd1.out" "$TMP/fd2.out"; return 1; }
  ! grep -q OVERLAP "$FAKE_NPM_LOG" || { echo "npm ci 가 동시에 돌았다"; cat "$FAKE_NPM_LOG"; return 1; }
  [ "$(ci_count)" = 1 ] || { echo "npm ci $(ci_count)회(기대 1)"; cat "$TMP/fd1.out" "$TMP/fd2.out"; return 1; }
  grep -q "기다린다" "$TMP/fd1.out" "$TMP/fd2.out" || { echo "기다린다는 안내가 없다"; cat "$TMP/fd1.out" "$TMP/fd2.out"; return 1; }
  [ ! -e "$FD/frontend/.deps-lock" ] || { echo "잠금이 남았다"; return 1; }
}
check "TASK-35#3 동시에 두 번 불러도 npm ci 는 잠금으로 한 번만, 겹치지 않게 돈다" t_fd_lock_concurrent
t_fd_lock_live() {
  # 살아 있는 프로세스가 잡은 잠금은 빼앗지 않는다 — 시간 초과면 PID 를 알리고 실패, npm ci 는 돌지 않는다
  : >"$FAKE_NPM_LOG"
  echo '{"lockfileVersion":3,"v":6}' >"$FD/frontend/package-lock.json"
  sleep 300 & local holder=$!
  mkdir -p "$FD/frontend/.deps-lock"; echo "$holder" >"$FD/frontend/.deps-lock/pid"
  run_capture env FRONTEND_DEPS_LOCK_TIMEOUT=1 PATH="$FD/bin:$PATH" FRONTEND_DIR="$FD/frontend" "$ROOT/scripts/frontend-deps.sh"
  kill "$holder" 2>/dev/null; wait "$holder" 2>/dev/null
  local ok=0
  [ "$RC" != 0 ] && grep -qF "$holder" <<<"$OUT" && [ "$(ci_count)" = 0 ] && [ "$(cat "$FD/frontend/.deps-lock/pid" 2>/dev/null)" = "$holder" ] || ok=1
  rm -rf "$FD/frontend/.deps-lock"
  [ "$ok" = 0 ] || { echo "rc=$RC ci=$(ci_count)"; echo "$OUT"; return 1; }
}
check "TASK-35#3 살아 있는 프로세스의 잠금은 기다리고, 시간 초과면 그 PID 를 알리며 실패한다" t_fd_lock_live
t_fd_lock_stale() {
  # 죽은 PID 의 잠금(중단된 이전 실행)은 치우고 진행한다
  : >"$FAKE_NPM_LOG"
  echo '{"lockfileVersion":3,"v":7}' >"$FD/frontend/package-lock.json"
  sleep 0 & local dead=$!; wait "$dead"
  mkdir -p "$FD/frontend/.deps-lock"; echo "$dead" >"$FD/frontend/.deps-lock/pid"
  run_capture env FRONTEND_DEPS_LOCK_TIMEOUT=5 PATH="$FD/bin:$PATH" FRONTEND_DIR="$FD/frontend" "$ROOT/scripts/frontend-deps.sh"
  rc_is 0 && has_text "남은 잠금" && [ "$(ci_count)" = 1 ] && [ ! -e "$FD/frontend/.deps-lock" ] || { echo "ci=$(ci_count)"; ls -la "$FD/frontend"; return 1; }
}
check "TASK-35#3 죽은 PID 가 남긴 잠금은 치우고 진행한다" t_fd_lock_stale
t_fd_lock_released_on_fail() {
  : >"$FAKE_NPM_LOG"
  echo '{"lockfileVersion":3,"v":8}' >"$FD/frontend/package-lock.json"
  run_capture env FAKE_NPM_FAIL=1 PATH="$FD/bin:$PATH" FRONTEND_DIR="$FD/frontend" "$ROOT/scripts/frontend-deps.sh"
  [ "$RC" != 0 ] && [ ! -e "$FD/frontend/.deps-lock" ] || { echo "rc=$RC"; ls -la "$FD/frontend"; return 1; }
}
check "TASK-35#3 npm ci 가 실패해도 잠금을 푼다" t_fd_lock_released_on_fail
check "TASK-35#3 잠금 디렉터리는 git 이 무시한다" sh -c 'git check-ignore -q frontend/.deps-lock/pid'
check "TASK-35#3 scripts/test.sh 는 frontend-deps.sh 로 의존성을 맞춘다" sh -c 'grep -q "frontend-deps.sh" scripts/test.sh && ! grep -q "node_modules ] || npm ci" scripts/test.sh'
for t in frontend-dev frontend-test frontend-build; do
  check "TASK-35#3 make $t 는 frontend-deps.sh 를 먼저 부른다" sh -c "make -n $t | grep -q 'scripts/frontend-deps.sh'"
done
check "TASK-35#3 make frontend-install 은 frontend-deps.sh --force" sh -c 'make -n frontend-install | grep -q "scripts/frontend-deps.sh --force"'
check "TASK-35#3 e2e.sh 는 frontend-deps.sh 로 의존성을 맞춘다" grep -q "frontend-deps.sh" scripts/e2e.sh

# ================================================================ TASK-35#4 OPS-41 배포 타깃에 루트 env 파일 값이 섞이지 않는다
# 임시 디렉터리에 Makefile 을 복사하고(scripts·frontend·infra·deploy 는 링크) 그 옆에 env 파일을 둔다. 가짜 terraform 이
# 자기가 본 환경을 남긴다(tf-outputs.sh -- <배포 스크립트> 의 환경). backend-run(로컬 실행)은 가짜 gradlew 로 env 파일이 읽히는지 본다.
# 가짜 aws·docker 도 PATH 앞에 둔다 — env 파일의 DRY_RUN=0 이 섞여도 실제 AWS 를 부르지 않게.
MK="$TMP/mk"
mkdir -p "$MK/bin" "$MK/backend"
cp Makefile "$MK/"
for d in scripts frontend infra deploy; do ln -s "$ROOT/$d" "$MK/$d"; done
MK_ENV_FILE="$MK/.env"
cat >"$MK_ENV_FILE" <<'ENVFILE'
# 주석과 빈 줄은 건너뛴다

LEAK_MARKER=from-dotenv
DRY_RUN=0
AWS_PROFILE=leaked-profile
POSTGRES_PORT=5432
export EXPORTED_KEY=exported-value
QUOTED_VALUE="a b # not a comment"
SINGLE_QUOTED='it''s $HOME literal'
ENVFILE
cat >"$MK/bin/terraform" <<'SH'
#!/usr/bin/env bash
env >"$MK_STATE/terraform.env"
echo '{"region":{"value":"us-east-1"},"ecr_repository_url":{"value":"1.dkr.ecr.us-east-1.amazonaws.com/x"},"web_bucket":{"value":"b"},"distribution_id":{"value":"E1"},"instance_id":{"value":"i-1"}}'
SH
printf '#!/usr/bin/env bash\necho "fake $(basename "$0") $*" >>"$MK_STATE/calls.log"\n' >"$MK/bin/aws"
cp "$MK/bin/aws" "$MK/bin/docker"
printf '#!/usr/bin/env bash\nenv >"$MK_STATE/gradlew.env"\n' >"$MK/backend/gradlew"
chmod +x "$MK/bin/"* "$MK/backend/gradlew"
export MK_STATE="$TMP/mk-state"
mkdir -p "$MK_STATE"
mk() { env -u LEAK_MARKER -u AWS_PROFILE -u POSTGRES_PORT -u EXPORTED_KEY -u QUOTED_VALUE -u SINGLE_QUOTED PATH="$MK/bin:$PATH" make -s -C "$MK" "$@"; }
t_mk_deploy_clean() {
  rm -f "$MK_STATE"/*
  run_capture mk deploy-frontend DRY_RUN=1 SKIP_BUILD=1
  rc_is 0 || return 1
  [ -f "$MK_STATE/terraform.env" ] || { echo "terraform 이 불리지 않았다"; echo "$OUT"; return 1; }
  ! grep -Eq '^(LEAK_MARKER|AWS_PROFILE)=' "$MK_STATE/terraform.env" || { echo "env 파일 값이 배포 환경에 섞였다:"; grep -E '^(LEAK_MARKER|AWS_PROFILE)=' "$MK_STATE/terraform.env"; return 1; }
  has_text "[dry-run] aws cloudfront create-invalidation" && [ ! -s "$MK_STATE/calls.log" ]
}
check "TASK-35#4 make deploy-frontend 에는 env 파일 값(LEAK_MARKER·AWS_PROFILE·DRY_RUN=0)이 섞이지 않는다" t_mk_deploy_clean
t_mk_deploy_env_dryrun() {
  # 환경변수로 준 DRY_RUN=1 을 env 파일의 DRY_RUN=0 이 덮지 않는다
  rm -f "$MK_STATE"/*
  run_capture env DRY_RUN=1 SKIP_BUILD=1 PATH="$MK/bin:$PATH" make -s -C "$MK" deploy-frontend
  rc_is 0 && has_text "[dry-run] aws s3 sync" && [ ! -s "$MK_STATE/calls.log" ]
}
check "TASK-35#4 환경변수 DRY_RUN=1 을 env 파일의 DRY_RUN=0 이 덮지 않는다" t_mk_deploy_env_dryrun
t_mk_local_env() {
  rm -f "$MK_STATE"/*
  run_capture mk backend-run
  rc_is 0 || return 1
  grep -qx 'LEAK_MARKER=from-dotenv' "$MK_STATE/gradlew.env" || { echo "backend-run 이 env 파일을 읽지 않았다"; echo "$OUT"; return 1; }
}
check "TASK-35#4 로컬 실행(make backend-run)은 env 파일을 그대로 읽는다" t_mk_local_env
t_mk_local_env_syntax() {
  # 주석·빈 줄·export 형식·따옴표(공백·# 포함, 작은따옴표 안의 $)를 셸 문법 그대로 읽는다
  rm -f "$MK_STATE"/*
  run_capture mk backend-run
  rc_is 0 || return 1
  local want
  for want in 'POSTGRES_PORT=5432' 'EXPORTED_KEY=exported-value' 'QUOTED_VALUE=a b # not a comment' 'SINGLE_QUOTED=its $HOME literal'; do
    grep -qxF -- "$want" "$MK_STATE/gradlew.env" || { echo "gradlew 환경에 '$want' 가 없다:"; grep -E '^(POSTGRES_PORT|EXPORTED_KEY|QUOTED_VALUE|SINGLE_QUOTED)=' "$MK_STATE/gradlew.env"; return 1; }
  done
}
check "TASK-35#4 env 파일의 주석·빈 줄·export·따옴표 값을 셸 문법대로 읽는다" t_mk_local_env_syntax
t_mk_local_cmdline_wins() {
  # make 명령줄 변수(make backend-run POSTGRES_PORT=5433)를 env 파일 값이 덮지 않는다
  rm -f "$MK_STATE"/*
  run_capture mk backend-run POSTGRES_PORT=5433
  rc_is 0 || return 1
  grep -qx 'POSTGRES_PORT=5433' "$MK_STATE/gradlew.env" || { echo "명령줄 POSTGRES_PORT=5433 이 덮였다:"; grep '^POSTGRES_PORT=' "$MK_STATE/gradlew.env"; return 1; }
  grep -qx 'LEAK_MARKER=from-dotenv' "$MK_STATE/gradlew.env" || { echo "정의되지 않은 키는 env 파일에서 읽어야 한다"; return 1; }
}
check "TASK-35#4 make 명령줄 변수가 env 파일 값보다 우선한다(make backend-run POSTGRES_PORT=5433)" t_mk_local_cmdline_wins
t_mk_local_env_wins() {
  # 이미 있는 환경변수(빈 값 포함)를 env 파일 값이 덮지 않는다
  rm -f "$MK_STATE"/*
  run_capture env -u LEAK_MARKER POSTGRES_PORT=5434 AWS_PROFILE= PATH="$MK/bin:$PATH" make -s -C "$MK" backend-run
  rc_is 0 || return 1
  grep -qx 'POSTGRES_PORT=5434' "$MK_STATE/gradlew.env" || { echo "환경변수 POSTGRES_PORT=5434 가 덮였다:"; grep '^POSTGRES_PORT=' "$MK_STATE/gradlew.env"; return 1; }
  grep -qx 'AWS_PROFILE=' "$MK_STATE/gradlew.env" || { echo "빈 값으로 정의된 AWS_PROFILE 이 덮였다:"; grep '^AWS_PROFILE=' "$MK_STATE/gradlew.env"; return 1; }
  grep -qx 'LEAK_MARKER=from-dotenv' "$MK_STATE/gradlew.env" || { echo "정의되지 않은 키는 env 파일에서 읽어야 한다"; return 1; }
}
check "TASK-35#4 이미 정의된 환경변수(빈 값 포함)가 env 파일 값보다 우선한다" t_mk_local_env_wins
t_mk_local_env_bad() {
  # env 파일 문법 오류면 로컬 실행 타깃이 실패한다(조용히 기본값으로 돌지 않게)
  local d="$TMP/mk-bad"
  rm -rf "$d"; mkdir -p "$d/backend"; cp "$MK/Makefile" "$d/"; ln -s "$ROOT/scripts" "$d/scripts"; cp "$MK/backend/gradlew" "$d/backend/"
  printf 'BROKEN="unterminated\n' >"$d/.env"
  rm -f "$MK_STATE"/*
  run_capture env PATH="$MK/bin:$PATH" make -s -C "$d" backend-run
  [ "$RC" != 0 ] && [ ! -f "$MK_STATE/gradlew.env" ] || { echo "rc=$RC, gradlew 실행됨=$([ -f "$MK_STATE/gradlew.env" ] && echo y)"; echo "$OUT"; return 1; }
}
check "TASK-35#4 env 파일 문법 오류면 로컬 실행 타깃이 실패한다" t_mk_local_env_bad
for t in test backend-test frontend-test e2e; do
  check "TASK-35#4 make $t 는 env 파일을 읽지 않는다" sh -c "! make -n $t | grep -Eq 'local-env|set -a'"
done

# ================================================================ TASK-35#5 Next.js 보안 패치 반영(GHSA-2464-fhr4-w9xw·GHSA-p3m5-59mp-jv5r·GHSA-673q-mjm6-r5xr: 16.0.0 – 16.4.0)
t_next_patched() {
  node -e '
    const pkg = require("./frontend/package.json"), lock = require("./frontend/package-lock.json");
    const want = pkg.dependencies.next, cfg = pkg.devDependencies["eslint-config-next"];
    const inst = lock.packages["node_modules/next"].version;
    const [ma, mi, pa] = inst.split(".").map(Number);
    const errs = [];
    if (!/^\d+\.\d+\.\d+$/.test(want)) errs.push("next 는 정확한 버전으로 고정한다: " + want);
    if (want !== inst) errs.push("package.json next " + want + " ≠ lock " + inst);
    if (cfg !== want) errs.push("eslint-config-next " + cfg + " ≠ next " + want);
    if (ma === 16 && (mi < 4 || (mi === 4 && pa <= 0))) errs.push("next " + inst + " 은 16.0.0 – 16.4.0 취약 범위다");
    if (errs.length) { console.log(errs.join("\n")); process.exit(1); }
  '
}
check "TASK-35#5 next 는 취약 범위(16.0.0 – 16.4.0) 밖의 고정 버전이고 lock·eslint-config-next 와 같다" t_next_patched

echo "tools: $passes passed, $failures failed"
[ "$failures" -eq 0 ]
