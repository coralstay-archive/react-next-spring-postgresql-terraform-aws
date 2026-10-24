#!/usr/bin/env bash
# 백그라운드 프로세스를 프로세스 그룹 단위로 띄우고 끄는 함수 (TASK-35#2, OPS-39). source 해서 쓴다(scripts/e2e.sh).
#   proc_start <명령|함수> [인자...]   자기 프로세스 그룹(set -m)으로 백그라운드 실행. PGID(= PID)를 PROC_LAST 와 PROC_PIDS 에 둔다.
#                                     리다이렉션은 호출에 붙인다: proc_start java -jar x.jar >>log 2>&1
#   proc_stop <PGID> [초]             그룹에 TERM → 초(기본 PROC_STOP_TIMEOUT, 10) 안에 안 끝나면 KILL. 그룹의 하위 프로세스까지 끝난다.
#   proc_stop_all [초]                PROC_PIDS 의 그룹을 모두 끈다
#   proc_record <파일>                PROC_PIDS 를 "PGID|시작 시각"으로 기록한다(E2E_KEEP: 다음 실행이 끈다)
#   proc_stop_recorded <파일> [초]    기록의 그룹 중 시작 시각이 같은 것(PID 재사용이 아닌 것)만 끄고 파일을 지운다
# 포트·이름으로 찾아 죽이지 않는다 — 다른 프로세스를 죽일 수 있다. 이 셸이 띄운 그룹만 끈다.

PROC_PIDS=()
PROC_LAST=""
PROC_STOP_TIMEOUT="${PROC_STOP_TIMEOUT:-10}"

_proc_log() { echo "==> $*" >&2; }

proc_start() {
  # set -m(작업 제어) 상태에서 띄운 백그라운드 작업은 자기 프로세스 그룹의 리더가 된다(PGID = PID).
  # 터미널의 Ctrl-C(SIGINT)도 이 그룹에는 가지 않는다 — 정리는 proc_stop 이 한다.
  set -m
  "$@" &
  PROC_LAST=$!
  set +m
  PROC_PIDS+=("$PROC_LAST")
}

_proc_alive() { kill -0 -- "-$1" 2>/dev/null; }

proc_stop() {
  local pgid="$1" timeout="${2:-$PROC_STOP_TIMEOUT}" waited=0
  [ -n "$pgid" ] || return 0
  if _proc_alive "$pgid"; then
    kill -TERM -- "-$pgid" 2>/dev/null || true
    # 0.2초 간격으로 timeout 초까지 기다린다
    while _proc_alive "$pgid" && [ "$waited" -lt $((timeout * 5)) ]; do
      sleep 0.2
      waited=$((waited + 1))
    done
    if _proc_alive "$pgid"; then
      _proc_log "프로세스 그룹 $pgid 이 TERM 뒤 ${timeout}초 안에 끝나지 않아 KILL"
      kill -KILL -- "-$pgid" 2>/dev/null || true
      while _proc_alive "$pgid"; do sleep 0.1; done
    fi
  fi
  # 이 셸의 자식이면 거둔다(좀비·"Terminated" 알림 정리). 끈 그룹은 목록에서 뺀다(나중에 같은 번호가 재사용돼도 건드리지 않게)
  wait "$pgid" 2>/dev/null || true
  local keep=() p
  for p in "${PROC_PIDS[@]:-}"; do [ -z "$p" ] || [ "$p" = "$pgid" ] || keep+=("$p"); done
  if [ "${#keep[@]}" -gt 0 ]; then PROC_PIDS=("${keep[@]}"); else PROC_PIDS=(); fi
}

proc_stop_all() {
  local pgid
  for pgid in "${PROC_PIDS[@]:-}"; do
    [ -z "$pgid" ] || proc_stop "$pgid" "${1:-$PROC_STOP_TIMEOUT}"
  done
}

_proc_started_at() { ps -o lstart= -p "$1" 2>/dev/null | sed -E 's/^ +//; s/ +$//'; }

proc_record() {
  local file="$1" pgid
  : >"$file"
  for pgid in "${PROC_PIDS[@]:-}"; do
    if [ -z "$pgid" ] || ! _proc_alive "$pgid"; then continue; fi
    printf '%s|%s\n' "$pgid" "$(_proc_started_at "$pgid")" >>"$file"
  done
}

proc_stop_recorded() {
  local file="$1" timeout="${2:-$PROC_STOP_TIMEOUT}" pgid started now
  [ -f "$file" ] || return 0
  while IFS='|' read -r pgid started; do
    [[ "$pgid" =~ ^[0-9]+$ ]] || continue
    now="$(_proc_started_at "$pgid")"
    if [ -z "$now" ]; then
      continue
    elif [ "$now" != "$started" ]; then
      _proc_log "PID $pgid 는 기록과 시작 시각이 달라(다른 프로세스가 재사용) 건너뛴다"
    else
      _proc_log "이전 실행이 남긴 프로세스 그룹 $pgid 정리"
      proc_stop "$pgid" "$timeout"
    fi
  done <"$file"
  rm -f "$file"
}
