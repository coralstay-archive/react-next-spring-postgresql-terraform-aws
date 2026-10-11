#!/usr/bin/env bash
# 로컬 배포용: terraform output(infra/main)에서 배포 대상 값을 읽어 CI 리포 variables 와 같은 이름으로 내보낸다 (TASK-17#5).
#   tf-outputs.sh              → export 줄 출력      eval "$(scripts/tf-outputs.sh)"
#   tf-outputs.sh -- <명령...>  → 값을 export 하고 명령 실행 (make deploy* 가 이렇게 쓴다)
# terraform 값이 셸에 이미 있는 값보다 우선한다(셸의 AWS_REGION 이 달라 엉뚱한 리전에 배포하지 않게).
# 환경: TF_DIR(기본 infra/main). terraform init(-backend-config=envs/prod.backend.hcl) 이 돼 있어야 한다.
set -euo pipefail
# shellcheck source=scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

TF_DIR="${TF_DIR:-$ROOT/infra/main}"
command -v jq >/dev/null || die "jq 가 필요하다"

# 환경변수 이름 = terraform output 이름 (앞 5개는 필수)
MAP=(
  AWS_REGION=region
  ECR_REPOSITORY_URL=ecr_repository_url
  WEB_BUCKET=web_bucket
  DISTRIBUTION_ID=distribution_id
  INSTANCE_ID=instance_id
  SSM_PARAMETER_PATH=ssm_parameter_path
  LOG_GROUP=log_group
  DEPLOY_DIR=deploy_dir
)
REQUIRED=5

json="$(terraform -chdir="$TF_DIR" output -json)" || die "terraform output 실패 ($TF_DIR: init 했는지 확인)"

lines=()
i=0
for m in "${MAP[@]}"; do
  var="${m%%=*}"
  out="${m#*=}"
  val="$(jq -r --arg o "$out" '.[$o].value // empty | tostring' <<<"$json")"
  if [ -z "$val" ]; then
    [ "$i" -lt "$REQUIRED" ] && die "terraform output '$out' 이 없다 ($TF_DIR 를 terraform apply 했는지 확인)"
  else
    printf -v "$var" '%s' "$val"
    export "${var?}"
    lines+=("export $var=$(quote_args "$val")")
  fi
  i=$((i + 1))
done

if [ "${1:-}" = "--" ]; then
  shift
  [ $# -gt 0 ] || die "usage: tf-outputs.sh -- <command...>"
  exec "$@"
fi
printf '%s\n' "${lines[@]}"
