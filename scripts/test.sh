#!/usr/bin/env bash
# 리포·Terraform·백엔드·프론트 테스트를 한 번에 실행한다. 로컬 `make test`와 CI가 같이 쓴다.
# Terraform 은 fmt 검사 + 루트 스택(infra/bootstrap, infra/main, infra/platform)과 tests/*.tftest.hcl 이 있는 모듈마다 init(-backend=false)·validate·test(mock_provider, 오프라인). terraform 이 없으면 경고 후 건너뛴다.
# 백엔드 테스트는 Testcontainers를 쓰므로 Docker(또는 Podman 소켓 — DOCKER_HOST)가 떠 있어야 한다.
# TASK-64#5 맥에는 컨테이너 런타임이 없다 — 맥에서 실행하면 사전 검사(VM 이 꺼져 있으면 켜지 않고 실패) 뒤 Rocky 개발 VM 안에서
# 같은 make test 를 돌린다(ssh rocky-dev). 저장소는 기본으로 rsync 로 작업 트리를 VM 로컬 /repo 에 맞추고, UTM 창에서 VirtioFS 공유를
# 직접 붙였으면(선택) 동기화 없이 그 /repo 를 쓴다(scripts/_vm.sh). VM 안·CI(ubuntu)에서는 그대로 실행한다. VM_PROXY=0 이면 맥에서도 그대로.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=scripts/_vm.sh
source "$ROOT/scripts/_vm.sh"
vm_proxy_if_mac "make test"

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

echo "==> vm"
"$ROOT/scripts/test/vm_test.sh"

echo "==> platform"
"$ROOT/scripts/test/platform_test.sh"

echo "==> cost"
"$ROOT/scripts/test/cost_test.sh"

echo "==> sonar"
"$ROOT/scripts/test/sonar_test.sh"

echo "==> terraform"
if command -v terraform >/dev/null 2>&1; then
  # provider 는 모듈마다 받지 않고 캐시를 같이 쓴다. 테스트는 mock_provider 라 AWS 자격 증명이 필요 없다.
  export TF_PLUGIN_CACHE_DIR="${TF_PLUGIN_CACHE_DIR:-$HOME/.terraform.d/plugin-cache}"
  mkdir -p "$TF_PLUGIN_CACHE_DIR"
  terraform fmt -check -recursive "$ROOT/infra"
  # 루트 스택(infra/bootstrap, infra/main, infra/platform)은 테스트 유무와 상관없이 init(-backend=false)·validate 한다. 모듈은 tests/*.tftest.hcl 이 있는 곳만.
  while IFS= read -r dir; do
    echo "--> $dir"
    terraform -chdir="$dir" init -backend=false -input=false -no-color >/dev/null
    terraform -chdir="$dir" validate -no-color
    if ls "$dir"/tests/*.tftest.hcl >/dev/null 2>&1; then
      terraform -chdir="$dir" test -no-color
    fi
  done < <({
    printf '%s\n' "$ROOT/infra/bootstrap" "$ROOT/infra/main" "$ROOT/infra/platform"
    find "$ROOT/infra" -path '*/.terraform' -prune -o -type d -name tests -print \
      | while IFS= read -r t; do ls "$t"/*.tftest.hcl >/dev/null 2>&1 && dirname "$t"; done
  } | sort -u)
else
  echo "WARNING: terraform 이 없어 infra 테스트(fmt·validate·test)를 건너뛴다" >&2
fi

echo "==> backend"
# 시작 검사와 여기 사이(수 분)에 누가 클러스터를 다시 띄웠을 수 있다 — Testcontainers 직전에 한 번 더(TOCTOU)
"$ROOT/scripts/k3d.sh" stop-if-running "make test (backend)"
(cd "$ROOT/backend" && ./gradlew test --console=plain)

echo "==> frontend"
(
  cd "$ROOT/frontend"
  # node_modules 가 없거나 package.json·package-lock.json 이 마지막 설치와 다르면 npm ci (TASK-35#3)
  "$ROOT/scripts/frontend-deps.sh"
  npm test
)

echo "==> all tests passed"
