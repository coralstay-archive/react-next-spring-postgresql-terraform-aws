#!/usr/bin/env bash
# 배포 스크립트 공통 함수 (TASK-17). source 해서 쓴다.
# DRY_RUN=1 이면 aws·docker·npm 명령을 "[dry-run] ..." 로 출력만 하고 실행하지 않는다.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ROOT

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }
is_dry_run() { [ "${DRY_RUN:-0}" = 1 ]; }

# 사람이 읽을 수 있게 인자를 인용한다(안전한 문자만이면 그대로, 아니면 작은따옴표)
quote_args() {
  local out="" a
  for a in "$@"; do
    if [[ "$a" =~ ^[A-Za-z0-9_./:=@%+,-]+$ ]]; then
      out+="$a "
    else
      out+="'${a//\'/\'\\\'\'}' "
    fi
  done
  printf '%s' "${out% }"
}

# run <명령...> — DRY_RUN 이면 출력만
run() {
  if is_dry_run; then
    printf '[dry-run] %s\n' "$(quote_args "$@")"
  else
    "$@"
  fi
}

# require_vars VAR... — 비어 있으면 실패
require_vars() {
  local v missing=()
  for v in "$@"; do [ -n "${!v:-}" ] || missing+=("$v"); done
  [ "${#missing[@]}" -eq 0 ] || die "필요한 환경변수가 없다: ${missing[*]} (로컬: make 타깃이 scripts/tf-outputs.sh 로 채운다, CI: 리포 variables)"
}

# 이미지 태그: IMAGE_TAG 가 있으면 그것, 없으면 git short sha(12). 커밋 안 된 변경이 있으면 거부(ALLOW_DIRTY=1 로 무시)
resolve_image_tag() {
  if [ -n "${IMAGE_TAG:-}" ]; then
    :
  else
    if [ "${ALLOW_DIRTY:-0}" != 1 ] && [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ]; then
      die "커밋 안 된 변경이 있다. 이미지 태그(git sha)가 내용과 달라지니 커밋하거나 ALLOW_DIRTY=1 / IMAGE_TAG 를 준다"
    fi
    IMAGE_TAG="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
  fi
  [[ "$IMAGE_TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "이미지 태그 형식이 잘못됐다: $IMAGE_TAG"
  export IMAGE_TAG
}
