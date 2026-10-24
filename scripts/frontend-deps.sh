#!/usr/bin/env bash
# 프론트 의존성을 lock 파일에 맞춘다 (TASK-35#3, OPS-40). scripts/test.sh, make frontend-*, scripts/e2e.sh 가 부른다.
#   package.json + package-lock.json 의 해시를 node_modules/.deps-stamp 와 비교해서, node_modules 가 없거나 해시가 다르면
#   npm ci 를 돌리고 스탬프를 새로 쓴다. 같으면 아무것도 하지 않는다(브랜치를 바꿔 lock 이 달라져도 옛 node_modules 로 돌지 않게).
#   npm ci 는 node_modules 를 지우고 새로 깔기 때문에, 실패하면 스탬프가 남지 않아 다음 실행이 다시 ci 한다.
# 사용: scripts/frontend-deps.sh [--force]   (--force: 해시와 상관없이 npm ci — make frontend-install)
# 환경: FRONTEND_DIR(기본 <repo>/frontend)
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
want="$(cat "$dir/package.json" "$dir/package-lock.json" | sha256 | cut -d' ' -f1)"
have="$(cat "$stamp" 2>/dev/null || true)"

if [ "$force" = 0 ] && [ -d "$dir/node_modules" ] && [ "$have" = "$want" ]; then
  exit 0
fi
if [ "$force" = 1 ]; then
  echo "==> npm ci (--force)" >&2
elif [ ! -d "$dir/node_modules" ]; then
  echo "==> npm ci (node_modules 없음)" >&2
else
  echo "==> npm ci (package.json·package-lock.json 이 마지막 설치와 다르다)" >&2
fi
(cd "$dir" && npm ci)
printf '%s\n' "$want" >"$stamp"
