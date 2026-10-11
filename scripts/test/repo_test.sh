#!/usr/bin/env bash
# 리포 골격 검증 테스트 (TASK-1). scripts/test.sh 가 호출한다.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

failures=0
passes=0

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    passes=$((passes + 1)); echo "  ok   - $name"
  else
    failures=$((failures + 1)); echo "  FAIL - $name"
  fi
}

has_make_target() { grep -Eq "^$1:" Makefile; }

echo "repo tests"

# TASK-1#3 Makefile + scripts/test.sh 로 백엔드·프론트 테스트를 일괄 실행한다
check "TASK-1#3 scripts/test.sh 실행 가능" test -x scripts/test.sh
check "TASK-1#3 Makefile 존재" test -f Makefile
for t in test dev-db dev-db-down backend-run backend-test frontend-dev frontend-test frontend-build; do
  check "TASK-1#3 make $t 타깃" has_make_target "$t"
done
check "TASK-1#3 make test 는 scripts/test.sh 를 호출" sh -c 'make -n test | grep -q "scripts/test.sh"'
check "TASK-1#3 scripts/test.sh 는 백엔드 테스트를 실행" grep -q "gradlew" scripts/test.sh
check "TASK-1#3 scripts/test.sh 는 프론트 테스트를 실행" grep -q "npm test" scripts/test.sh

echo "repo tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
