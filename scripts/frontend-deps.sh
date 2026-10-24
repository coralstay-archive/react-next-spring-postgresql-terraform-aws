#!/usr/bin/env bash
# 프론트 의존성을 lock 파일에 맞춘다 (TASK-35#3, OPS-40). scripts/test.sh, make frontend-*, scripts/e2e.sh 가 부른다.
#   package.json + package-lock.json 의 해시를 node_modules/.deps-stamp 와 비교해서, node_modules 가 없거나 해시가 다르면
#   npm ci 를 돌리고 스탬프를 새로 쓴다. 같으면 아무것도 하지 않는다(브랜치를 바꿔 lock 이 달라져도 옛 node_modules 로 돌지 않게).
#   npm ci 는 node_modules 를 지우고 새로 깔기 때문에, 실패하면 스탬프가 남지 않아 다음 실행이 다시 ci 한다.
#   동시 실행 잠금: 두 make(예: make test 와 make frontend-dev)가 함께 npm ci 를 돌려 node_modules 를 서로 지우지 않게,
#   npm ci 는 잠금 디렉터리 <frontend>/.deps-lock(mkdir 은 원자적, 안에 pid)을 잡고 돈다. 잠금을 잡은 뒤 해시를 다시 보므로, 기다린 쪽은
#   앞 실행이 설치를 끝냈으면 건너뛴다. 잠금의 PID 가 살아 있지 않으면(중단된 실행이 남긴 잠금) 치우고 진행한다.
#   기다림이 FRONTEND_DEPS_LOCK_TIMEOUT(초, 기본 600)을 넘으면 그 PID 를 알리고 실패한다.
# 사용: scripts/frontend-deps.sh [--force]   (--force: 해시와 상관없이 npm ci — make frontend-install)
# 환경: FRONTEND_DIR(기본 <repo>/frontend), FRONTEND_DEPS_LOCK_TIMEOUT(기본 600)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="${FRONTEND_DIR:-$ROOT/frontend}"
stamp="$dir/node_modules/.deps-stamp"

force=0
case "${1:-}" in
  --force) force=1 ;;
  "") ;;
  *) echo "usage: $0 [--force]" >&2; exit 2 ;;
esac

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi
}
hash_now() { cat "$dir/package.json" "$dir/package-lock.json" | sha256 | cut -d' ' -f1; }
up_to_date() { [ -d "$dir/node_modules" ] && [ "$(cat "$stamp" 2>/dev/null || true)" = "$(hash_now)" ]; }

if [ "$force" = 0 ] && up_to_date; then
  exit 0
fi

# --- 잠금 -------------------------------------------------------------------------------------------------
lock="$dir/.deps-lock"
timeout="${FRONTEND_DEPS_LOCK_TIMEOUT:-600}"
locked=0
release() { if [ "$locked" = 1 ]; then rm -rf "$lock"; fi; }
trap release EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
waited=0
while ! mkdir "$lock" 2>/dev/null; do
  holder="$(cat "$lock/pid" 2>/dev/null || true)"
  if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then
    # 죽은 PID 의 잠금: 이름을 바꿔(원자적) 치운다. 그 사이 다른 실행이 먼저 치우고 새로 잡았으면 되돌린다
    stale="$lock.stale.$$"
    if mv "$lock" "$stale" 2>/dev/null; then
      if [ "$(cat "$stale/pid" 2>/dev/null || true)" = "$holder" ]; then
        echo "==> 남은 잠금 정리: $lock (PID $holder 는 끝났다)" >&2
        rm -rf "$stale"
      elif ! mv "$stale" "$lock" 2>/dev/null; then
        rm -rf "$stale"
      fi
    fi
    continue
  fi
  if [ "$waited" = 0 ]; then
    echo "==> 다른 실행(PID ${holder:-?})이 npm ci 중이라 끝나기를 기다린다 (잠금 $lock, 최대 ${timeout}초)" >&2
  fi
  if [ "$waited" -ge "$timeout" ]; then
    echo "frontend-deps: ${timeout}초 기다려도 잠금이 풀리지 않았다 — PID ${holder:-?} 가 잡고 있다($lock). 그 프로세스가 없으면 잠금 디렉터리를 지운다" >&2
    exit 1
  fi
  sleep 1
  waited=$((waited + 1))
done
locked=1
echo "$$" >"$lock/pid"

# 기다리는 동안 앞 실행이 같은 lock 으로 설치를 끝냈으면 건너뛴다
if [ "$force" = 0 ] && up_to_date; then
  echo "==> 앞 실행이 의존성을 맞췄다 — npm ci 를 건너뛴다" >&2
  exit 0
fi

want="$(hash_now)"
if [ "$force" = 1 ]; then
  echo "==> npm ci (--force)" >&2
elif [ ! -d "$dir/node_modules" ]; then
  echo "==> npm ci (node_modules 없음)" >&2
else
  echo "==> npm ci (package.json·package-lock.json 이 마지막 설치와 다르다)" >&2
fi
echo "    npm ci 는 $dir/node_modules 를 지우고 다시 만든다 — 실행 중인 next dev 같은 dev 서버·vitest 가 있으면 깨질 수 있으니 끝난 뒤 다시 띄운다" >&2
(cd "$dir" && npm ci)
printf '%s\n' "$want" >"$stamp"
