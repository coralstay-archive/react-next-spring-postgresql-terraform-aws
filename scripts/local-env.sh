#!/usr/bin/env bash
# 로컬 실행 타깃이 읽는 루트 env 파일(example.env 를 복사한 것)을 export 문으로 바꿔 출력한다 (TASK-35#4, OPS-41).
#   Makefile 의 LOCAL_ENV 가 `eval "$(./scripts/local-env.sh <파일>)"` 로 쓴다(dev-db, dev-storage, dev-db-down, backend-run, frontend-dev).
#   - 이미 정의된 변수(환경변수, make 명령줄 변수 — make 가 레시피 환경으로 export 한다)는 덮지 않는다. 빈 값으로 정의된 것도 그대로 둔다.
#     예: make dev-db POSTGRES_PORT=5433 은 파일의 POSTGRES_PORT 보다 우선한다.
#   - 값은 셸 문법으로 읽는다(`. 파일` — 주석·빈 줄·export KEY=…·따옴표). 키는 `KEY=`·`export KEY=` 로 시작하는 줄에서 찾는다.
#   - 파일이 없으면 아무것도 출력하지 않는다. 파일 문법 오류면 1 로 끝난다(레시피도 실패 — 조용히 기본값으로 돌지 않게).
# 사용: scripts/local-env.sh [파일]   (기본: 현재 디렉터리의 env 파일)
set -euo pipefail

file="${1:-.env}"
[ -f "$file" ] || exit 0
case "$file" in /*) ;; *) file="./$file" ;; esac

# 이미 정의된(환경에 있는) 변수 — 이 스크립트가 만든 변수와 섞이지 않게 맨 먼저 읽는다
preset=$'\n'"$(compgen -e)"$'\n'
keys="$(sed -nE 's/^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=.*/\2/p' "$file" | awk '!seen[$0]++')"

out="$(
  set +u
  # shellcheck disable=SC1090 # 사용자 로컬 파일
  . "$file" >/dev/null || exit 1
  for k in $keys; do
    case "$preset" in *$'\n'"$k"$'\n'*) continue ;; esac
    v="${!k-}"
    printf "export %s='%s'\n" "$k" "${v//\'/\'\\\'\'}"
  done
)" || { echo "local-env: $file 을 읽지 못했다(셸 문법 — 값에 공백·특수문자가 있으면 따옴표로 감싼다)" >&2; exit 1; }
printf '%s\n' "$out"
