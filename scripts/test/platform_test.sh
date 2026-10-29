#!/usr/bin/env bash
# shellcheck disable=SC2016 # sh -c '...' 안의 $ 는 하위 셸에서 풀린다
# AWS 실배포 플랫폼 스택(infra/platform, TASK-65) 테스트. scripts/test.sh 가 호출한다.
# Terraform 동작은 tests/platform.tftest.hcl(mock_provider)이 보고, 여기서는 파일 배치·설정 파일·노드 스크립트를 본다.
# 실제 AWS·장치는 부르지 않는다(노드 스크립트는 PATH 앞에 둔 가짜 blkid·mkfs.xfs·mount 로 부른다).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

failures=0
passes=0

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

P=infra/platform

echo "platform tests"

# TASK-65#1 루트 스택: 파일 구성, bootstrap state 버킷 재사용(키 분리 platform/…), network 모듈 재사용, lock 파일(3플랫폼 해시) 커밋,
# make test 가 init(-backend=false)·validate·test 한다
for f in versions.tf providers.tf backend.tf variables.tf main.tf outputs.tf envs/prod.backend.hcl envs/prod.tfvars tests/platform.tftest.hcl; do
  check "TASK-65#1 $P/$f" test -f "$P/$f"
done
check "TASK-65#1 backend 는 S3 partial config" grep -q 'backend "s3" {}' "$P/backend.tf"
check "TASK-65#1 backend 키는 platform/terraform.tfstate(infra/main 과 분리) + use_lockfile" sh -c "
  grep -Eq '^key += \"platform/terraform.tfstate\"' $P/envs/prod.backend.hcl && grep -Eq '^use_lockfile += true' $P/envs/prod.backend.hcl
  ! grep -v '^#' $P/envs/prod.backend.hcl | grep -q 'main/prod/terraform.tfstate'"
check "TASK-65#1 network 는 infra/main/modules/network 재사용" grep -q 'source *= "../main/modules/network"' "$P/main.tf"
check "TASK-65#1 lock 파일 커밋 + darwin_arm64·linux_arm64·linux_amd64 해시(h1 3개 이상)" sh -c "
  git ls-files --error-unmatch $P/.terraform.lock.hcl >/dev/null && [ \"\$(grep -c '\"h1:' $P/.terraform.lock.hcl)\" -ge 3 ]"
check "TASK-65#1 gitignore 가 platform 모듈 lock 파일을 무시" git check-ignore -q --no-index "$P/modules/k3s_node/.terraform.lock.hcl"
check "TASK-65#1 scripts/test.sh 는 루트 스택 infra/platform 을 validate" grep -q 'infra/platform' scripts/test.sh
check "TASK-65#1 scripts/test.sh 가 이 테스트를 부른다" grep -q 'scripts/test/platform_test.sh' scripts/test.sh

# TASK-65#2 TASK-65#B1 k3s_node: 22 번 포트·SSH 키를 설정에 두지 않는다(접속은 SSM). 규칙 상세는 k3s_node.tftest.hcl
N=$P/modules/k3s_node
check "TASK-65#2 k3s_node 모듈 테스트 존재" test -f "$N/tests/k3s_node.tftest.hcl"
check "TASK-65#B1 k3s_node 설정에 22 번 포트가 없다" sh -c "! grep -Eq '(from|to)_port *= *22\\b' $N/*.tf"
check "TASK-65#B1 k3s_node 는 SSH 키(key_name)를 쓰지 않는다" sh -c "! grep -Eq '^\\s*key_name\\b' $N/*.tf"
check "TASK-65#B1 k3s_node 는 SSM Core 정책을 붙인다" grep -q 'AmazonSSMManagedInstanceCore' "$N/main.tf"

echo "platform tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
