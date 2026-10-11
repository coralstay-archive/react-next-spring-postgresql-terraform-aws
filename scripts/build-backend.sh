#!/usr/bin/env bash
# 백엔드 이미지를 linux/arm64(t4g)로 빌드해 ECR 에 push 한다 (TASK-17#1).
# 필요: AWS_REGION, ECR_REPOSITORY_URL (로컬은 make deploy-backend 가 terraform output 으로 채운다, CI 는 리포 variables)
# 선택: IMAGE_TAG(기본 git short sha), DRY_RUN=1, ALLOW_DIRTY=1
# 태그는 <sha> 와 latest 둘. 배포·롤백은 sha 태그만 쓴다(latest 는 사람이 보기 위한 것).
set -euo pipefail
# shellcheck source=scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

require_vars AWS_REGION ECR_REPOSITORY_URL
resolve_image_tag
registry="${ECR_REPOSITORY_URL%%/*}"

log "ECR 로그인 ($registry)"
if is_dry_run; then
  echo "[dry-run] aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $registry"
else
  aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$registry"
fi

log "이미지 빌드·push $ECR_REPOSITORY_URL:$IMAGE_TAG (linux/arm64)"
# --provenance=false: attestation manifest 가 ECR 에 태그 없는 이미지로 쌓여 수명 주기(최근 20개)를 차지하지 않게 한다
run docker buildx build \
  --platform linux/arm64 \
  --provenance=false \
  --tag "$ECR_REPOSITORY_URL:$IMAGE_TAG" \
  --tag "$ECR_REPOSITORY_URL:latest" \
  --push \
  "$ROOT/backend"

log "완료: $ECR_REPOSITORY_URL:$IMAGE_TAG"
