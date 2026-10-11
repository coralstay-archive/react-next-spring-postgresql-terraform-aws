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

# TASK-1#4 .claude-code-agile-hooks.json 에 testCommand 와 testFirst(sourceGlobs·testGlobs) 설정
check "TASK-1#4 hooks 설정이 testCommand·testFirst 를 갖는다" python3 - <<'PY'
import json, sys
c = json.load(open(".claude-code-agile-hooks.json"))
tf = c["testFirst"]
ok = (
    isinstance(c.get("testCommand"), str) and c["testCommand"].strip()
    and all(isinstance(tf.get(k), list) and tf[k] and all(isinstance(g, str) for g in tf[k])
            for k in ("sourceGlobs", "testGlobs"))
    and "backend/src/main/**" in tf["sourceGlobs"] and "frontend/src/**" in tf["sourceGlobs"]
    and "backend/src/test/**" in tf["testGlobs"] and "scripts/test/**" in tf["testGlobs"]
    and not any(g.startswith(("**", "frontend/**")) for g in tf["testGlobs"])
)
sys.exit(0 if ok else 1)
PY
check "TASK-1#4 testCommand 는 make test" sh -c 'python3 -c "import json;print(json.load(open(\".claude-code-agile-hooks.json\"))[\"testCommand\"])" | grep -qx "make test"'

# TASK-1#5 gitignore, env 예시 파일, README 초안
check "TASK-1#5 .gitignore 존재" test -f .gitignore
for p in .env .env.local frontend/node_modules/x frontend/.next/x frontend/out/x backend/build/x backend/.gradle/x infra/.terraform/x x.tfstate; do
  check "TASK-1#5 gitignore가 $p 를 무시" git check-ignore -q --no-index "$p"
done
check "TASK-1#5 gitignore가 gradle wrapper jar는 추적" sh -c '! git check-ignore -q --no-index backend/gradle/wrapper/gradle-wrapper.jar'
check "TASK-1#5 example.env 존재" test -f example.env
for k in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_PORT DB_URL DB_USERNAME DB_PASSWORD; do
  check "TASK-1#5 example.env 에 $k" grep -Eq "^$k=" example.env
done
check "TASK-1#5 README.md 존재" test -f README.md
for w in "make test" "make dev-db" "make backend-run" "make frontend-dev" "example.env" "/api/actuator/health"; do
  check "TASK-1#5 README 에 '$w' 안내" grep -qF "$w" README.md
done

echo "repo tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
