#!/usr/bin/env bash
# Terraform 월 비용 추정 (TASK-66#3). make cost·cost-diff 와 CI(.github/workflows/cost.yml)가 부른다. 설정은 저장소 루트 infracost.yml.
#   scripts/cost.sh breakdown [상한 USD, 기본 30]   breakdown → 표 → cost-guard(platform 프로젝트, B1)
#   scripts/cost.sh diff [기준 ref, 기본 main]       기준 ref 의 infra(git archive)를 breakdown 해 지금과 infracost diff
# 맥이면 Rocky 개발 VM 안에서 돈다(scripts/_vm.sh vm_proxy_if_mac — infracost 는 VM 에 설치돼 있다, install.sh dev).
# B2 가격 API 키는 저장소·.env 에 두지 않는다: VM 은 infracost configure set api_key(~/.config/infracost), CI 는 secret
# INFRACOST_API_KEY. 키(또는 infracost)가 없으면 경고 후 0 으로 건너뛴다. 키는 출력하지 않는다.
# 환경변수: COST_OUT_DIR(결과 JSON 을 남길 디렉터리 — breakdown.json·base.json·diff.json, 없으면 임시), INFRACOST(실행 파일).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=scripts/_vm.sh
source "$ROOT/scripts/_vm.sh"
vm_proxy_if_mac "./scripts/cost.sh $(printf '%q ' "$@")"

cd "$ROOT"
IC="${INFRACOST:-infracost}"
CFG="infracost.yml"
# 새 CLI(v2) 업데이트 안내를 끈다 — v0.10.x 로 고정했다(install.sh·scripts/ci/install-infracost.sh)
export INFRACOST_SKIP_UPDATE_CHECK=true

log() { echo "==> [cost] $*" >&2; }
warn_skip() { echo "WARNING: [cost] $* — 비용 추정을 건너뛴다" >&2; exit 0; }
usage() { echo "사용: $0 breakdown [상한 USD, 기본 30] | diff [기준 ref, 기본 main]" >&2; exit 2; }

cmd="${1:-}"; arg="${2:-}"
case "$cmd" in breakdown | diff) ;; *) usage ;; esac

command -v "$IC" >/dev/null 2>&1 || warn_skip "infracost 가 없다(VM: sudo /usr/local/sbin/rocky-install.sh --profile dev …, CI: scripts/ci/install-infracost.sh)"
# 키 확인: 환경변수 또는 저장된 설정. 값은 비었는지만 보고 출력하지 않는다
if [ -z "${INFRACOST_API_KEY:-}" ]; then
  saved="$("$IC" configure get api_key 2>/dev/null || true)"
  if [ -z "$saved" ]; then
    warn_skip "Infracost API 키가 없다. 등록(VM 안에서 한 번): ssh $VM_SSH_HOST 로 들어가
  infracost configure set api_key <키> (README 비용 추정). 저장소·.env 에는 넣지 않는다"
  fi
  unset saved
fi

if [ -n "${COST_OUT_DIR:-}" ]; then
  out="$COST_OUT_DIR"; mkdir -p "$out"
else
  out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
fi

case "$cmd" in
  breakdown)
    log "breakdown ($CFG)"
    "$IC" breakdown --config-file "$CFG" --format json --out-file "$out/breakdown.json"
    "$IC" output --path "$out/breakdown.json" --format table
    "$ROOT/scripts/cost-guard.sh" --project platform "$out/breakdown.json" ${arg:+"$arg"}
    ;;
  diff)
    ref="${arg:-main}"
    git rev-parse --verify -q "$ref^{commit}" >/dev/null || { echo "ERROR: [cost] 기준 ref 가 없다: $ref" >&2; exit 1; }
    base="$(mktemp -d)"
    # 기준 트리는 infra 만 꺼낸다. 설정 파일이 그 ref 에 없으면(도입 전) 지금 것을 써서 같은 프로젝트로 비교한다
    git archive "$ref" infra | tar -x -C "$base"
    for f in "$CFG" infracost-usage.yml infracost-usage-main.yml; do
      if git cat-file -e "$ref:$f" 2>/dev/null; then git show "$ref:$f" >"$base/$f"; else cp "$f" "$base/$f"; fi
    done
    log "기준 $ref breakdown"
    (cd "$base" && "$IC" breakdown --config-file "$CFG" --format json --out-file "$out/base.json") || { rm -rf "$base"; exit 1; }
    rm -rf "$base"
    log "diff (지금 ↔ $ref)"
    "$IC" diff --config-file "$CFG" --compare-to "$out/base.json" --format json --out-file "$out/diff.json"
    "$IC" output --path "$out/diff.json" --format diff
    ;;
esac
