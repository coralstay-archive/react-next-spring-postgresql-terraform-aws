#!/usr/bin/env bash
# 리포·Terraform·백엔드·프론트 테스트를 한 번에 실행한다. 로컬 `make test`와 CI가 같이 쓴다.
# Terraform 은 fmt 검사 + 루트 스택(infra/bootstrap, infra/main)과 tests/*.tftest.hcl 이 있는 모듈마다 init(-backend=false)·validate·test(mock_provider, 오프라인). terraform 이 없으면 경고 후 건너뛴다.
# 백엔드 테스트는 Testcontainers를 쓰므로 Docker가 떠 있어야 한다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# TASK-36#6 로컬 k3d 클러스터가 떠 있으면 먼저 멈춘다(Testcontainers 와 동시에 띄우지 않는다 — 8GB). 커밋 훅의 make test 에도 적용된다.
"$ROOT/scripts/k3d.sh" stop-if-running "make test"

echo "==> repo"
"$ROOT/scripts/test/repo_test.sh"

echo "==> deploy"
"$ROOT/scripts/test/deploy_test.sh"

echo "==> tools"
"$ROOT/scripts/test/tools_test.sh"

echo "==> k8s"
"$ROOT/scripts/test/k8s_test.sh"

echo "==> terraform"
if command -v terraform >/dev/null 2>&1; then
  # provider 는 모듈마다 받지 않고 캐시를 같이 쓴다. 테스트는 mock_provider 라 AWS 자격 증명이 필요 없다.
  export TF_PLUGIN_CACHE_DIR="${TF_PLUGIN_CACHE_DIR:-$HOME/.terraform.d/plugin-cache}"
  mkdir -p "$TF_PLUGIN_CACHE_DIR"
  terraform fmt -check -recursive "$ROOT/infra"
  # 루트 스택(infra/bootstrap, infra/main)은 테스트 유무와 상관없이 init(-backend=false)·validate 한다. 모듈은 tests/*.tftest.hcl 이 있는 곳만.
  while IFS= read -r dir; do
    echo "--> $dir"
    terraform -chdir="$dir" init -backend=false -input=false -no-color >/dev/null
    terraform -chdir="$dir" validate -no-color
    if ls "$dir"/tests/*.tftest.hcl >/dev/null 2>&1; then
      terraform -chdir="$dir" test -no-color
    fi
  done < <({
    printf '%s\n' "$ROOT/infra/bootstrap" "$ROOT/infra/main"
    find "$ROOT/infra" -path '*/.terraform' -prune -o -type d -name tests -print \
      | while IFS= read -r t; do ls "$t"/*.tftest.hcl >/dev/null 2>&1 && dirname "$t"; done
  } | sort -u)
else
  echo "WARNING: terraform 이 없어 infra 테스트(fmt·validate·test)를 건너뛴다" >&2
fi

echo "==> backend"
(cd "$ROOT/backend" && ./gradlew test --console=plain)

echo "==> frontend"
(
  cd "$ROOT/frontend"
  # node_modules 가 없거나 package.json·package-lock.json 이 마지막 설치와 다르면 npm ci (TASK-35#3)
  "$ROOT/scripts/frontend-deps.sh"
  npm test
)

echo "==> all tests passed"
