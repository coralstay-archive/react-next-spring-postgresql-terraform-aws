#!/usr/bin/env bash
# 로컬 k3d 플랫폼 공통 함수 (TASK-36). source 해서 쓴다(scripts/k3d.sh).
# 8GB 머신에서 k3d 클러스터와 테스트(Testcontainers)를 같이 띄우지 않고, Docker VM 메모리·프로필 예산을 넘겨 조용히 뜨지 않게
# 사람의 기억이 아니라 이 함수들이 절차를 강제한다(doc-4 메모리 운영).
#   k3d_preflight <프로필...>   클러스터를 띄우거나 쓰는 모든 진입점(up·profile·smoke)이 먼저 부른다(TASK-36#7·#8):
#                               Docker 응답, VM 메모리 ≥ docker-min, Testcontainers 컨테이너 없음, 예산 합계 ≤ limit(FORCE=1 만 통과)
#   k3d_stop_if_running <누가>  클러스터가 떠 있으면 멈추고 출력한다(make test·test-integration·backend-test·backend-build·e2e 시작 시, TASK-36#6).
#                               k3d 가 PATH 에 없으면 docker 라벨 k3d.cluster=<이름> 으로 찾아 docker stop(실패하면 이유와 함께 실패)
#   kc <인자...>                kubectl --context k3d-<클러스터> — 다른 클러스터를 건드리지 않는다
# 예산·상한은 deploy/k8s/budgets.txt 하나에서 읽는다(B1). 테스트는 K3D_BUDGETS_FILE·K3D_PROFILES_DIR 로 바꾼다.
# TASK-64#4 맥에서는 k3d_preflight 가 먼저 Rocky 개발 VM 사전 검사(scripts/_vm.sh vm_preflight — VM 이 꺼져 있으면 켜지 않고 실패)를 지난다.
# (k3d → VM 안 k3s 재작업은 TASK-36)

# shellcheck source=scripts/_vm.sh
source "$(dirname "${BASH_SOURCE[0]}")/_vm.sh"

K3D_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K3D_CLUSTER="${K3D_CLUSTER:-board}"
K3D_CONTEXT="k3d-$K3D_CLUSTER"
K3D_REGISTRY_CONTAINER="k3d-registry.localhost"
K3D_BUDGETS_FILE="${K3D_BUDGETS_FILE:-$K3D_ROOT/deploy/k8s/budgets.txt}"
K3D_PROFILES_DIR="${K3D_PROFILES_DIR:-$K3D_ROOT/deploy/k8s/profiles}"

k3d_log() { echo "==> $*" >&2; }
k3d_die() { echo "ERROR: $*" >&2; exit 1; }
kc() { kubectl --context "$K3D_CONTEXT" "$@"; }

# 예산 파일의 값: k3d_budget docker-min | limit
k3d_budget() { awk -v k="$1" '$1 == k { print $2; exit }' "$K3D_BUDGETS_FILE"; }
# 프로필 예산(없으면 빈 값)
k3d_profile_budget() { awk -v p="$1" '$1 == "profile" && $2 == p { print $3; exit }' "$K3D_BUDGETS_FILE"; }
# 예산 파일에 있는 프로필(= 켤 수 있는 프로필) 목록
k3d_known_profiles() { awk '$1 == "profile" { print $2 }' "$K3D_BUDGETS_FILE"; }
# "core,gitops core" → "core gitops"(중복 제거, 순서 유지)
k3d_parse_profiles() { printf '%s\n' "$*" | tr ',' ' ' | tr -s ' ' '\n' | awk 'NF && !seen[$0]++' | tr '\n' ' ' | sed 's/ $//'; }

# 클러스터 상태: running | stopped | absent (k3d 가 없으면 absent)
k3d_cluster_state() {
  command -v k3d >/dev/null 2>&1 || { echo absent; return 0; }
  local servers
  servers="$(k3d cluster list --no-headers 2>/dev/null | awk -v n="$K3D_CLUSTER" '$1 == n { print $2; exit }')"
  case "$servers" in
    "") echo absent ;;
    0/*) echo stopped ;;
    *) echo running ;;
  esac
}

# 지금 켜진 프로필: 클러스터 리소스의 board.dev/profile 라벨(kustomize labels 가 붙인다)
k3d_active_profiles() {
  kc get deployment,statefulset,job -A -l board.dev/profile \
    -o 'jsonpath={range .items[*]}{.metadata.labels.board\.dev/profile}{"\n"}{end}' 2>/dev/null | awk 'NF && !seen[$0]++' | tr '\n' ' ' | sed 's/ $//'
}

k3d_preflight() {
  local profiles=("$@") reasons=() mem_bytes mem_mib min_mib limit tc_ids tc_count p b sum=0 detail="" unknown=()
  vm_preflight || return 1
  [ -f "$K3D_BUDGETS_FILE" ] || k3d_die "예산 파일이 없다: $K3D_BUDGETS_FILE"
  min_mib="$(k3d_budget docker-min)"; limit="$(k3d_budget limit)"
  [ -n "$min_mib" ] && [ -n "$limit" ] || k3d_die "예산 파일에 docker-min·limit 이 없다: $K3D_BUDGETS_FILE"

  # 1) Docker VM 메모리
  if ! mem_bytes="$(docker info --format '{{.MemTotal}}' 2>/dev/null)" || ! [[ "$mem_bytes" =~ ^[0-9]+$ ]]; then
    reasons+=("Docker 가 응답하지 않는다(docker info 실패).
     해결: Docker Desktop 을 켜고 다시 실행한다.")
  else
    mem_mib=$((mem_bytes / 1048576))
    if [ "$mem_mib" -lt "$min_mib" ]; then
      reasons+=("Docker VM 메모리 ${mem_mib}MiB < ${min_mib}MiB — Docker Desktop 메모리 5GB 미만이다(k3d 단일 노드 + 프로필이 들어가지 않는다).
     해결: Docker Desktop → Settings → Resources → Memory 를 5GB 이상으로 올리고 Apply & restart.")
    fi
  fi

  # 2) Testcontainers(make test·test-integration 의 백엔드 테스트)와 동시에 띄우지 않는다
  tc_ids="$(docker ps -q --filter label=org.testcontainers=true 2>/dev/null || true)"
  tc_count="$(printf '%s\n' "$tc_ids" | awk 'NF' | wc -l | tr -d ' ')"
  if [ "$tc_count" -gt 0 ]; then
    reasons+=("Testcontainers 컨테이너 ${tc_count}개가 실행 중이다(label org.testcontainers=true — make test·test-integration). 8GB 에서 테스트와 클러스터를 같이 띄우지 않는다.
     해결: 테스트가 끝나길 기다린다. 남은 컨테이너라면: docker rm -f \$(docker ps -q --filter label=org.testcontainers=true)")
  fi

  # 3) 프로필 예산 합계 ≤ 상한(FORCE=1 만 통과)
  for p in "${profiles[@]}"; do
    b="$(k3d_profile_budget "$p")"
    if [ -z "$b" ]; then unknown+=("$p"); continue; fi
    sum=$((sum + b)); detail+="${detail:+ + }$p $b"
  done
  if [ "${#unknown[@]}" -gt 0 ]; then
    reasons+=("예산이 없는 프로필: ${unknown[*]} (켤 수 있는 프로필: $(k3d_known_profiles | tr '\n' ' '| sed 's/ $//')).
     해결: 이름을 고치거나, 새 프로필이면 $K3D_BUDGETS_FILE 에 예산을 적고 deploy/k8s/profiles/<이름>/ 을 만든다.")
  fi
  k3d_log "메모리 예산: ${detail:-(없음)} = ${sum} MiB / 상한 ${limit} MiB (Docker VM ${mem_mib:-?} MiB)"
  if [ "$sum" -gt "$limit" ]; then
    if [ "${FORCE:-0}" = 1 ]; then
      echo "WARNING: 프로필 예산 합계 ${sum} MiB 가 상한 ${limit} MiB 를 넘는데 FORCE=1 이라 진행한다 — OOM·스왑으로 느려질 수 있다." >&2
    else
      reasons+=("프로필 예산 합계 ${sum} MiB (${detail}) > 상한 ${limit} MiB.
     해결: 프로필을 줄인다(예: make k3d-profile PROFILES=core). 알고 넘기려면 FORCE=1 (예산 검사만 건너뛴다).")
    fi
  fi

  if [ "${#reasons[@]}" -gt 0 ]; then
    echo "ERROR: k3d 사전 검사 실패 — 클러스터를 띄우거나 쓰지 않는다:" >&2
    local r; for r in "${reasons[@]}"; do echo "  - $r" >&2; done
    return 1
  fi
}

k3d_stop_if_running() {
  local who="${1:-이 명령}" ids
  if ! command -v k3d >/dev/null 2>&1; then
    # k3d 가 PATH 에 없어도(다른 셸·설치 경로) 클러스터 컨테이너는 떠 있을 수 있다 — docker 라벨로 찾아 멈춘다. docker 도 없으면(CI) 할 일이 없다
    command -v docker >/dev/null 2>&1 || return 0
    ids="$(docker ps -q --filter "label=k3d.cluster=$K3D_CLUSTER" 2>/dev/null || true)"
    [ -n "$ids" ] || return 0
    # shellcheck disable=SC2086 # 공백으로 나눈 컨테이너 id 목록
    docker stop $ids >/dev/null || k3d_die "k3d 가 PATH 에 없어 docker stop 으로 클러스터 '$K3D_CLUSTER' 컨테이너(label k3d.cluster=$K3D_CLUSTER)를 멈추려 했지만 실패했다 — 클러스터를 띄운 채 $who 를 하지 않는다.
  해결: docker stop \$(docker ps -q --filter label=k3d.cluster=$K3D_CLUSTER) 로 직접 멈추거나, k3d 를 설치하고 make k3d-stop"
    docker stop "$K3D_REGISTRY_CONTAINER" >/dev/null 2>&1 || true
    echo "==> k3d 클러스터 '$K3D_CLUSTER' 컨테이너가 떠 있어 docker stop 으로 멈췄다(k3d 가 PATH 에 없다) — $who 와 동시에 띄우지 않는다(메모리). 다시 쓰려면: make k3d-up" >&2
    return 0
  fi
  [ "$(k3d_cluster_state)" = running ] || return 0
  k3d cluster stop "$K3D_CLUSTER" >/dev/null
  docker stop "$K3D_REGISTRY_CONTAINER" >/dev/null 2>&1 || true
  echo "==> k3d 클러스터 '$K3D_CLUSTER' 가 떠 있어 멈췄다 — $who 와 동시에 띄우지 않는다(메모리). 다시 쓰려면: make k3d-up" >&2
}
