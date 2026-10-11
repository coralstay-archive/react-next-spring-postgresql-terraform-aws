#!/usr/bin/env bash
# EC2 에 백엔드 이미지를 배포한다 (TASK-17#1). SSH 없이 SSM send-command(AWS-RunShellScript) 하나로:
#   deploy/docker-compose.prod.yml, deploy/render-env.sh, deploy/remote-deploy.sh 를 base64 로 실어 $DEPLOY_DIR 에 쓰고
#   remote-deploy.sh 를 실행한다(SSM → env 파일, ECR pull, up -d, 헬스 대기, 실패 시 이전 태그로 롤백).
# 그 뒤 get-command-invocation 을 폴링해 결과(stdout·stderr)를 보이고, 실패면 0 이 아닌 코드로 끝난다.
# 필요: AWS_REGION, ECR_REPOSITORY_URL, INSTANCE_ID
# 선택: IMAGE_TAG(기본 git short sha — build-backend.sh 와 같음), SSM_PARAMETER_PATH(/board/prod), LOG_GROUP(/board/prod),
#       DEPLOY_DIR(/opt/board), DEPLOY_TIMEOUT(900초), POLL_INTERVAL(5초), DRY_RUN=1
# CI role 권한: ssm:SendCommand(이 인스턴스 + AWS-RunShellScript), ssm:GetCommandInvocation, ecr:DescribeImages 로 충분하다.
set -euo pipefail
# shellcheck source=scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

require_vars AWS_REGION ECR_REPOSITORY_URL INSTANCE_ID
resolve_image_tag
SSM_PARAMETER_PATH="${SSM_PARAMETER_PATH:-/board/prod}"
LOG_GROUP="${LOG_GROUP:-/board/prod}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/board}"
DEPLOY_TIMEOUT="${DEPLOY_TIMEOUT:-900}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"
command -v jq >/dev/null || die "jq 가 필요하다"

# 원격 셸 한 줄에 그대로 들어가므로 안전한 문자만 허용한다
for v in ECR_REPOSITORY_URL SSM_PARAMETER_PATH LOG_GROUP DEPLOY_DIR AWS_REGION INSTANCE_ID; do
  [[ "${!v}" =~ ^[A-Za-z0-9._/:-]+$ ]] || die "$v 값에 허용하지 않는 문자가 있다: ${!v}"
done
repo_name="${ECR_REPOSITORY_URL#*/}"

if ! is_dry_run; then
  log "ECR 에 $repo_name:$IMAGE_TAG 가 있는지 확인"
  aws ecr describe-images --region "$AWS_REGION" --repository-name "$repo_name" --image-ids "imageTag=$IMAGE_TAG" >/dev/null ||
    die "ECR 에 이미지가 없다: $ECR_REPOSITORY_URL:$IMAGE_TAG (먼저 build-backend.sh)"
fi

b64() { base64 <"$1" | tr -d '\n'; }

commands=(
  "set -eu"
  "mkdir -p $DEPLOY_DIR"
)
for f in docker-compose.prod.yml render-env.sh remote-deploy.sh; do
  commands+=(
    "echo $(b64 "$ROOT/deploy/$f") | base64 -d > $DEPLOY_DIR/$f.tmp"
    "mv $DEPLOY_DIR/$f.tmp $DEPLOY_DIR/$f"
  )
done
commands+=(
  "chmod 700 $DEPLOY_DIR/render-env.sh $DEPLOY_DIR/remote-deploy.sh"
  "ECR_REPOSITORY_URL=$ECR_REPOSITORY_URL IMAGE_TAG=$IMAGE_TAG AWS_REGION=$AWS_REGION SSM_PARAMETER_PATH=$SSM_PARAMETER_PATH LOG_GROUP=$LOG_GROUP DEPLOY_DIR=$DEPLOY_DIR bash $DEPLOY_DIR/remote-deploy.sh"
)

params="$(mktemp)"
trap 'rm -f "$params"' EXIT
# executionTimeout: 원격 스크립트 최대 실행 시간(pull + 헬스 대기 + 롤백)
jq -n --arg timeout "$DEPLOY_TIMEOUT" '{commands: $ARGS.positional, executionTimeout: [$timeout]}' --args "${commands[@]}" >"$params"

send=(aws ssm send-command --region "$AWS_REGION" --instance-ids "$INSTANCE_ID" --document-name AWS-RunShellScript
  --comment "deploy $IMAGE_TAG" --parameters "file://$params" --query Command.CommandId --output text)

if is_dry_run; then
  printf '[dry-run] %s\n' "$(quote_args "${send[@]}")"
  echo "[dry-run] remote commands:"
  for c in "${commands[@]}"; do
    case "$c" in
      "echo "*"| base64 -d"*) echo "  echo <base64 ${#c} chars> | base64 -d > ${c##*> }" ;;
      *) echo "  $c" ;;
    esac
  done
  echo "[dry-run] aws ssm get-command-invocation --region $AWS_REGION --command-id <id> --instance-id $INSTANCE_ID (poll until done)"
  exit 0
fi

log "send-command → $INSTANCE_ID ($IMAGE_TAG)"
command_id="$("${send[@]}")"
log "command id: $command_id"

deadline=$(( $(date +%s) + DEPLOY_TIMEOUT + 120 ))
status=""
while :; do
  sleep "$POLL_INTERVAL"
  # 보낸 직후에는 InvocationDoesNotExist 가 날 수 있어 실패를 무시하고 다시 묻는다
  if inv="$(aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" --instance-id "$INSTANCE_ID" --output json 2>/dev/null)"; then
    status="$(jq -r '.Status' <<<"$inv")"
    case "$status" in
      Pending | InProgress | Delayed | Cancelling) ;;
      *) break ;;
    esac
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    die "배포 명령이 ${DEPLOY_TIMEOUT}s 안에 끝나지 않았다 (command $command_id, status ${status:-unknown})"
  fi
done

echo "----- remote stdout -----"
jq -r '.StandardOutputContent // ""' <<<"$inv"
echo "----- remote stderr -----"
jq -r '.StandardErrorContent // ""' <<<"$inv"
echo "-------------------------"

if [ "$status" != Success ]; then
  die "배포 실패: status=$status (command $command_id). 위 로그 참고, 전체는 CloudWatch $LOG_GROUP"
fi
log "배포 완료: $IMAGE_TAG"
