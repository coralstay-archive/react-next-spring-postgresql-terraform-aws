#!/usr/bin/env bash
# EC2 에서 root 로 실행 (TASK-17#1). deploy-backend.sh 가 SSM send-command(AWS-RunShellScript)로 이 파일을
# compose·render-env.sh 와 함께 $DEPLOY_DIR 에 써 두고 부른다.
#   1) SSM → env 파일 (render-env.sh)
#   2) ECR 로그인(인스턴스 role) → IMAGE_TAG 로 compose pull · up -d
#   3) http://127.0.0.1:8080/api/actuator/health 가 UP 이 될 때까지 대기
#   4) 성공: $DEPLOY_DIR/current_tag 에 태그 기록, 오래된 이미지 정리
#      실패: api 로그를 보이고, 이전 태그(current_tag)가 있으면 그것으로 되돌린 뒤 실패로 끝난다
set -euo pipefail

: "${ECR_REPOSITORY_URL:?}" "${IMAGE_TAG:?}" "${AWS_REGION:?}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/board}"
SSM_PARAMETER_PATH="${SSM_PARAMETER_PATH:-/board/prod}"
LOG_GROUP="${LOG_GROUP:-/board/prod}"
DATA_DIR="${DATA_DIR:-/srv/pgdata}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/api/actuator/health}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-240}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-3}"
export ECR_REPOSITORY_URL AWS_REGION DEPLOY_DIR LOG_GROUP DATA_DIR

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="$DEPLOY_DIR/docker-compose.prod.yml"
tag_file="$DEPLOY_DIR/current_tag"
new_tag="$IMAGE_TAG"
prev_tag="$(cat "$tag_file" 2>/dev/null || true)"

# compose <tag> <args...>
compose() {
  local tag="$1"; shift
  IMAGE_TAG="$tag" docker compose -f "$compose_file" "$@"
}

wait_healthy() {
  local start; start=$(date +%s)
  while :; do
    if curl -fsS --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; then return 0; fi
    if [ $(( $(date +%s) - start )) -ge "$HEALTH_TIMEOUT" ]; then return 1; fi
    sleep "$HEALTH_INTERVAL"
  done
}

echo "==> deploy $new_tag (previous: ${prev_tag:-none})"
bash "$here/render-env.sh" "$SSM_PARAMETER_PATH" "$DEPLOY_DIR"

registry="${ECR_REPOSITORY_URL%%/*}"
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$registry"

compose "$new_tag" pull
compose "$new_tag" up -d --remove-orphans

if wait_healthy; then
  echo "$new_tag" >"$tag_file"
  echo "==> healthy: $new_tag"
  # 1주일 넘게 안 쓴 이미지만 지운다(직전 태그는 남아 있어 빠르게 되돌릴 수 있다)
  docker image prune -af --filter "until=168h" >/dev/null || true
  exit 0
fi

echo "==> health check failed for $new_tag (${HEALTH_TIMEOUT}s). api logs:" >&2
compose "$new_tag" logs --no-color --tail 100 api >&2 || true

if [ -z "$prev_tag" ] || [ "$prev_tag" = "$new_tag" ]; then
  echo "==> no previous tag to roll back to" >&2
  exit 1
fi

echo "==> rolling back to $prev_tag" >&2
compose "$prev_tag" pull api || true # 정리됐으면 다시 받는다(ECR 은 최근 20개를 둔다)
compose "$prev_tag" up -d --remove-orphans
if wait_healthy; then
  echo "==> rolled back to $prev_tag (deploy of $new_tag failed)" >&2
else
  echo "==> rollback to $prev_tag is also unhealthy" >&2
fi
exit 1
