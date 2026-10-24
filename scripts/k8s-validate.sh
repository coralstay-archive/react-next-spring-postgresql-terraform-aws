#!/usr/bin/env bash
# k8s 매니페스트 검증 (TASK-36#5): kubectl kustomize(내장 kustomize)로 렌더 → kubeconform -strict 로 스키마 검증. 오프라인이다 —
# 스키마는 리포의 deploy/k8s/schemas/ 에서만 읽는다(네트워크 없이, 매번 같은 결과). make test(scripts/test/k8s_test.sh)가 부른다.
#   scripts/k8s-validate.sh [kustomization 디렉터리...]   기본: deploy/k8s/base + deploy/k8s/profiles/*
#   scripts/k8s-validate.sh --update-schemas              지금 매니페스트가 쓰는 종류의 스키마를 받아 deploy/k8s/schemas/ 에 둔다(네트워크)
# 스키마: yannh/kubernetes-json-schema 의 v$K8S_SCHEMA_VERSION-standalone-strict(클러스터 k3s 와 같은 마이너, deploy/k8s/k3d/cluster.yaml).
# 받는 곳은 움직이는 master 가 아니라 K8S_SCHEMA_REF 커밋(SHA)으로 고정한다 — 같은 SHA 면 같은 내용(git 객체)이라 체크섬 없이도 재현된다.
# 올릴 때: git ls-remote https://github.com/yannh/kubernetes-json-schema refs/heads/master 의 SHA 로 바꾸고 --update-schemas 후 diff 를 리뷰해 커밋한다.
# 리포에 스키마가 없는 종류는 실패한다(조용히 건너뛰지 않는다) — 새 종류를 쓰면 --update-schemas 후 커밋한다.
# CRD(Argo CD·Rollouts·Traefik IngressRoute 등)는 이 스키마 저장소에 없다. 쓰게 되면 CRD 스키마를 deploy/k8s/schemas/ 에 더하거나
# (kubeconform 의 openapi2jsonschema), 종류를 K8S_SKIP_KINDS 에 적어 건너뛴다(쉼표 구분 — 건너뛴 종류는 출력에 남는다).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K8S_SCHEMA_VERSION="1.36.0"
SCHEMA_DIR="$ROOT/deploy/k8s/schemas"
# 2026-10-24 의 master. deploy/k8s/schemas/*.json 은 이 커밋의 파일과 같다
K8S_SCHEMA_REF="f7d855e49c34b039c9ef2196444910275e31e09e"
SCHEMA_URL="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/${K8S_SCHEMA_REF}/v${K8S_SCHEMA_VERSION}-standalone-strict"
K8S_SKIP_KINDS="${K8S_SKIP_KINDS:-}"

die() { echo "ERROR: $*" >&2; exit 1; }
command -v kubectl >/dev/null 2>&1 || die "kubectl 이 없다(kustomize 내장)"

default_dirs() {
  echo "$ROOT/deploy/k8s/base"
  local d
  for d in "$ROOT"/deploy/k8s/profiles/*/; do echo "${d%/}"; done
}

# 렌더 결과의 apiVersion·kind → 스키마 파일 이름(kubeconform 규칙: <kind>[-<group>]-<version>.json, 소문자, group 의 첫 마디)
schema_files() {
  awk '
    /^apiVersion: / { av = $2 }
    /^kind: / {
      k = tolower($2); n = split(av, p, "/")
      if (n == 1) { print k "-" p[1] ".json" } else { split(p[1], g, "."); print k "-" g[1] "-" p[2] ".json" }
    }'
}

if [ "${1:-}" = "--update-schemas" ]; then
  mkdir -p "$SCHEMA_DIR"
  while IFS= read -r d; do kubectl kustomize "$d"; echo "---"; done < <(default_dirs) | schema_files | sort -u | while IFS= read -r f; do
    echo "--> $f"
    curl -fsSL --retry 3 -o "$SCHEMA_DIR/$f.part" "$SCHEMA_URL/$f" && mv "$SCHEMA_DIR/$f.part" "$SCHEMA_DIR/$f"
  done
  exit 0
fi

command -v kubeconform >/dev/null 2>&1 || die "kubeconform 이 없다(brew install kubeconform)"
dirs=("$@")
if [ "${#dirs[@]}" -eq 0 ]; then
  while IFS= read -r d; do dirs+=("$d"); done < <(default_dirs)
fi

rc=0
for d in "${dirs[@]}"; do
  echo "--> ${d#"$ROOT"/}"
  args=(-strict -summary -schema-location "$SCHEMA_DIR/{{.ResourceKind}}{{.KindSuffix}}.json")
  [ -z "$K8S_SKIP_KINDS" ] || args+=(-skip "$K8S_SKIP_KINDS")
  if ! out="$(kubectl kustomize "$d" | kubeconform "${args[@]}" - 2>&1)"; then
    rc=1
    if grep -q 'could not find schema' <<<"$out"; then
      out+=$'\n'"리포에 스키마가 없는 종류다 — scripts/k8s-validate.sh --update-schemas 로 받아 커밋한다(CRD 는 이 스크립트 머리말 참고)"
    fi
  fi
  printf '%s\n' "$out"
done
exit "$rc"
