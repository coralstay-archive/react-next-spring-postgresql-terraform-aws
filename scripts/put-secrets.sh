#!/usr/bin/env bash
# 운영 앱 env 를 SSM Parameter Store(<SSM_PARAMETER_PATH>/<KEY>)에 넣는다 (TASK-17#3). 로컬에서 관리자 자격 증명으로 실행한다.
#   put-secrets.sh [--generate-missing] [--no-terraform] <입력 파일>
# 값의 출처(뒤가 앞을 덮는다):
#   1) terraform output app_env (비밀 아님: PUBLIC_BASE_URL, COOKIE_SECURE, CLIENT_IP_HEADER, UPLOADS_*, COGNITO_* ...)
#   2) terraform output cognito_client_secret → COGNITO_CLIENT_SECRET
#   3) 입력 파일의 KEY=VALUE 줄 (deploy/secrets.example.txt 를 deploy/secrets.local.txt 로 복사해 채운다. gitignore 됨)
#      # 주석·빈 줄은 건너뛰고, 값을 감싼 따옴표 한 쌍은 벗긴다(이스케이프 처리 없음). 빈 값은 넣지 않는다(앱 기본값).
# --generate-missing: DB_PASSWORD·JWT_SECRET·OTP_HASH_KEY 가 입력에도 SSM 에도 없으면 무작위(32바이트 hex)로 만든다.
#   SSM 에 이미 있으면 건드리지 않는다 — DB_PASSWORD 는 postgres 가 처음 초기화할 때만 쓰므로 바꾸면 접속이 깨진다.
# 이름에 SECRET·PASSWORD·KEY·TOKEN 이 들어간 키는 SecureString(aws/ssm KMS), 나머지는 String. 모두 --overwrite.
# 값은 명령줄에 올리지 않고 --cli-input-json 임시 파일(600)로 넘기며, 출력에는 시크릿 값을 쓰지 않는다(DRY_RUN 도 '****').
# EC2 쪽은 deploy/render-env.sh 가 배포마다 이 경로를 읽어 /opt/board/app.env 를 만든다 → 바꾼 뒤 make deploy-backend 로 반영.
# 환경: AWS_REGION·SSM_PARAMETER_PATH(없으면 terraform output region·ssm_parameter_path), TF_DIR(infra/main), DRY_RUN=1
set -euo pipefail
# shellcheck source=scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

usage() { echo "usage: put-secrets.sh [--generate-missing] [--no-terraform] <secrets-file>" >&2; exit 2; }

generate=0
use_tf=1
input=""
while [ $# -gt 0 ]; do
  case "$1" in
    --generate-missing) generate=1 ;;
    --no-terraform) use_tf=0 ;;
    -h | --help) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) [ -z "$input" ] || usage; input="$1" ;;
  esac
  shift
done
[ -n "$input" ] || usage
[ -f "$input" ] || die "입력 파일이 없다: $input"
command -v jq >/dev/null || die "jq 가 필요하다"

TF_DIR="${TF_DIR:-$ROOT/infra/main}"
REQUIRED_SECRETS=(DB_PASSWORD JWT_SECRET OTP_HASH_KEY)

# 키·값 목록 (bash 3.2 호환: 연관 배열 대신 나란한 배열)
keys=()
vals=()
set_kv() {
  local i
  for i in "${!keys[@]}"; do
    if [ "${keys[$i]}" = "$1" ]; then vals[i]="$2"; return; fi
  done
  keys+=("$1")
  vals+=("$2")
}
has_key() { local k; for k in "${keys[@]+"${keys[@]}"}"; do [ "$k" = "$1" ] && return 0; done; return 1; }
valid_key() { [[ "$1" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; }
is_secret() { [[ "$1" =~ (SECRET|PASSWORD|KEY|TOKEN) ]]; }

# 1·2) terraform output
if [ "$use_tf" = 1 ]; then
  log "terraform output ($TF_DIR)"
  tf_json="$(terraform -chdir="$TF_DIR" output -json)" || die "terraform output 실패 (init·apply 했는지, --no-terraform)"
  : "${AWS_REGION:=$(jq -r '.region.value // empty' <<<"$tf_json")}"
  : "${SSM_PARAMETER_PATH:=$(jq -r '.ssm_parameter_path.value // empty' <<<"$tf_json")}"
  while IFS=$'\t' read -r k v; do
    [ -n "$k" ] && set_kv "$k" "$v"
  done < <(jq -r '.app_env.value // {} | to_entries[] | select(.value != null and .value != "") | [.key, (.value | tostring)] | @tsv' <<<"$tf_json")
  cognito_secret="$(terraform -chdir="$TF_DIR" output -raw cognito_client_secret 2>/dev/null || true)"
  [ -n "$cognito_secret" ] && set_kv COGNITO_CLIENT_SECRET "$cognito_secret"
  unset cognito_secret tf_json
fi
SSM_PARAMETER_PATH="${SSM_PARAMETER_PATH:-/board/prod}"
SSM_PARAMETER_PATH="${SSM_PARAMETER_PATH%/}"
require_vars AWS_REGION

# 3) 입력 파일
skipped=()
lineno=0
while IFS= read -r line || [ -n "$line" ]; do
  lineno=$((lineno + 1))
  line="${line%$'\r'}"
  [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
  [[ "$line" == *=* ]] || die "$input:$lineno: KEY=VALUE 형식이 아니다"
  k="${line%%=*}"
  v="${line#*=}"
  k="${k#"${k%%[![:space:]]*}"}"; k="${k%"${k##*[![:space:]]}"}"
  valid_key "$k" || die "$input:$lineno: 키 형식이 아니다: $k"
  if [[ "$v" =~ ^\"(.*)\"$ ]] || [[ "$v" =~ ^\'(.*)\'$ ]]; then v="${BASH_REMATCH[1]}"; fi
  if [ -z "$v" ]; then skipped+=("$k"); continue; fi
  set_kv "$k" "$v"
done <"$input"
unset line v

# 이미 SSM 에 있는지 (DRY_RUN 은 부르지 않고 없는 것으로 본다)
ssm_exists() {
  local name="$SSM_PARAMETER_PATH/$1" err
  if is_dry_run; then
    echo "[dry-run] aws ssm get-parameter --region $AWS_REGION --name $name (존재 확인 — dry-run 은 없는 것으로 본다)"
    return 1
  fi
  if err="$(aws ssm get-parameter --region "$AWS_REGION" --name "$name" --query Parameter.Name --output text 2>&1 >/dev/null)"; then
    return 0
  fi
  [[ "$err" == *ParameterNotFound* ]] && return 1
  die "SSM 조회 실패($name): $err"
}

for k in "${REQUIRED_SECRETS[@]}"; do
  has_key "$k" && continue
  if [ "$generate" = 1 ]; then
    if ssm_exists "$k"; then
      log "$k: SSM 에 이미 있어 그대로 둔다"
    else
      log "$k: 무작위 생성"
      set_kv "$k" "$(openssl rand -hex 32)"
    fi
  else
    echo "WARNING: $k 가 입력에 없다. SSM 에 이미 있어야 한다(처음이면 --generate-missing)" >&2
  fi
done

[ "${#skipped[@]}" -eq 0 ] || log "빈 값이라 건너뜀: ${skipped[*]}"
[ "${#keys[@]}" -gt 0 ] || die "넣을 값이 없다"

work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT

log "SSM $SSM_PARAMETER_PATH/* ← ${#keys[@]} keys (region $AWS_REGION)"
for i in "${!keys[@]}"; do
  k="${keys[$i]}"
  name="$SSM_PARAMETER_PATH/$k"
  if is_secret "$k"; then type=SecureString; shown="****"; else type=String; shown="${vals[$i]}"; fi
  if is_dry_run; then
    printf '[dry-run] %s\n' "$(quote_args aws ssm put-parameter --region "$AWS_REGION" --name "$name" --type "$type" --value "$shown" --overwrite)"
    continue
  fi
  printf '%s' "${vals[$i]}" >"$work/value"
  jq -n --rawfile v "$work/value" --arg n "$name" --arg t "$type" '{Name: $n, Value: $v, Type: $t, Overwrite: true}' >"$work/input.json"
  aws ssm put-parameter --region "$AWS_REGION" --cli-input-json "file://$work/input.json" >/dev/null
  echo "  put $name ($type)"
done
rm -f "$work/value" "$work/input.json"
log "완료. EC2 에 반영하려면 make deploy-backend (render-env.sh 가 배포 때 env 파일을 다시 만든다)"
