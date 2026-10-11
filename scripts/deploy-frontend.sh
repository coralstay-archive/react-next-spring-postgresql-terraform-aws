#!/usr/bin/env bash
# 프론트(정적 export)를 S3 에 올리고 CloudFront 캐시를 비운다 (TASK-17#2).
#   1) npm ci && npm run build → frontend/out   (SKIP_BUILD=1 이면 건너뛴다)
#   2) out/_next/static → Cache-Control "public, max-age=31536000, immutable" (파일명에 해시가 있다. 지우지 않는다:
#      배포 직전에 열린 탭이 옛 청크를 lazy-load 할 수 있다. 쌓이는 양은 작다)
#   3) 나머지(HTML, RSC 페이로드 .txt, public/ 파일) → "no-cache"(매번 재검증) + --delete
#   4) CloudFront invalidation /*
# 자산을 먼저, HTML 을 나중에 올려 새 HTML 이 아직 없는 청크를 가리키는 순간이 없게 한다.
# 필요: AWS_REGION, WEB_BUCKET, DISTRIBUTION_ID   선택: SKIP_BUILD=1, FRONTEND_OUT_DIR, DRY_RUN=1
set -euo pipefail
# shellcheck source=scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

require_vars AWS_REGION WEB_BUCKET DISTRIBUTION_ID
frontend="$ROOT/frontend"
out="${FRONTEND_OUT_DIR:-$frontend/out}"

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  log "프론트 빌드"
  run npm --prefix "$frontend" ci
  run npm --prefix "$frontend" run build
fi

if ! is_dry_run && [ ! -f "$out/index.html" ]; then
  die "빌드 결과가 없다: $out/index.html (npm run build 또는 SKIP_BUILD 해제)"
fi

log "s3://$WEB_BUCKET ← $out"
run aws s3 sync "$out/_next/static" "s3://$WEB_BUCKET/_next/static" --region "$AWS_REGION" \
  --cache-control "public, max-age=31536000, immutable"
run aws s3 sync "$out" "s3://$WEB_BUCKET" --region "$AWS_REGION" --delete \
  --exclude "_next/static/*" --cache-control "no-cache"

log "CloudFront invalidation ($DISTRIBUTION_ID)"
run aws cloudfront create-invalidation --distribution-id "$DISTRIBUTION_ID" --paths "/*"
log "완료"
