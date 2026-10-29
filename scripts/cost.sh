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
# 맥 셸의 INFRACOST_API_KEY 는 VM 으로 넘기지 않는다(ssh 인자·환경에 키를 싣지 않는다) — VM 에 저장된 키를 쓴다
if vm_on_mac && [ -n "${INFRACOST_API_KEY:-}" ]; then
  vm_warn "맥 셸의 INFRACOST_API_KEY 는 VM 으로 넘기지 않는다 — VM 안에서 infracost configure set api_key 로 등록한 키를 쓴다(README 비용 추정)"
fi
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
  infracost configure set api_key <키> (README 비용 추정). 저장소·.env 에는 넣지 않는다. 맥 셸의 INFRACOST_API_KEY 는
  VM 으로 넘어가지 않는다"
  fi
  unset saved
fi

tmpdirs=(); trap 'rm -rf "${tmpdirs[@]}"' EXIT
if [ -n "${COST_OUT_DIR:-}" ]; then
  out="$COST_OUT_DIR"; mkdir -p "$out"
else
  out="$(mktemp -d)"; tmpdirs+=("$out")
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
    command -v yq >/dev/null 2>&1 || { echo "ERROR: [cost] yq 가 없다(기준 설정에서 프로젝트를 고른다)" >&2; exit 1; }
    base="$(mktemp -d)"; tmpdirs+=("$base")
    # 기준 트리는 infra 만 꺼낸다(그 ref 에 infra 가 없으면 빈 트리). 설정 파일이 그 ref 에 없으면(도입 전) 지금 것을 쓴다
    if git cat-file -e "$ref:infra" 2>/dev/null; then git archive "$ref" infra | tar -x -C "$base"; fi
    if git cat-file -e "$ref:$CFG" 2>/dev/null; then git show "$ref:$CFG" >"$base/$CFG"; else cp "$CFG" "$base/$CFG"; fi
    # 기준 트리에 없는 프로젝트(예: infra/platform 도입 전의 main)는 뺀다 — 기준 비용 0(새 프로젝트)으로 비교된다
    while IFS= read -r p; do
      if [ ! -d "$base/$p" ]; then
        log "기준 $ref 에 $p 가 없다 — 그 프로젝트는 기준 비용 0 으로 본다"
        P="$p" yq -i 'del(.projects[] | select(.path == strenv(P)))' "$base/$CFG"
      fi
    done < <(yq -r '.projects[].path' "$base/$CFG")
    # 남은 프로젝트의 usage 파일: 그 ref 의 것, 없으면 지금 것
    while IFS= read -r u; do
      [ -n "$u" ] || continue
      if git cat-file -e "$ref:$u" 2>/dev/null; then git show "$ref:$u" >"$base/$u"; elif [ -f "$u" ]; then cp "$u" "$base/$u"; fi
    done < <(yq -r '.projects[].usage_file // ""' "$base/$CFG" | sort -u)
    if [ "$(yq -r '.projects | length' "$base/$CFG")" = 0 ]; then
      log "기준 $ref 에 비교할 프로젝트가 없다 — 기준 비용 0 (빈 breakdown)"
      printf '{"version":"0.2","currency":"USD","projects":[],"totalHourlyCost":"0","totalMonthlyCost":"0","timeGenerated":"%s"}\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$out/base.json"
    else
      log "기준 $ref breakdown"
      (cd "$base" && "$IC" breakdown --config-file "$CFG" --format json --out-file "$out/base.json")
    fi
    log "diff (지금 ↔ $ref)"
    "$IC" diff --config-file "$CFG" --compare-to "$out/base.json" --format json --out-file "$out/diff.json"
    "$IC" output --path "$out/diff.json" --format diff
    ;;
esac
