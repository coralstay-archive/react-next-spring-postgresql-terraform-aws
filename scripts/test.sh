#!/usr/bin/env bash
# 백엔드·프론트·리포 테스트를 한 번에 실행한다. 로컬 `make test`와 CI가 같이 쓴다.
# 백엔드 테스트는 Testcontainers를 쓰므로 Docker가 떠 있어야 한다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> repo"
"$ROOT/scripts/test/repo_test.sh"

echo "==> backend"
(cd "$ROOT/backend" && ./gradlew test --console=plain)

echo "==> frontend"
(
  cd "$ROOT/frontend"
  [ -d node_modules ] || npm ci
  npm test
)

echo "==> all tests passed"
