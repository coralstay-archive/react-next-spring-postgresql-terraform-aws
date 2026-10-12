#!/usr/bin/env bash
# 배포 스크립트·compose·워크플로 테스트 (TASK-17). scripts/test.sh 가 호출한다.
# 실제 AWS 는 부르지 않는다: DRY_RUN=1 출력 검사 + PATH 앞에 둔 가짜 aws·docker·terraform·curl·npm 으로만 검증한다.
# 린터(shellcheck, actionlint)가 없으면 경고 후 건너뛴다(REQUIRE_LINTERS=1 이면 실패 — CI).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

failures=0
passes=0
OUT=""

# check <이름> <명령...> — 명령 출력은 실패할 때만 보인다
check() {
  local name="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then
    passes=$((passes + 1)); echo "  ok   - $name"
  else
    failures=$((failures + 1)); echo "  FAIL - $name"
    printf '%s\n' "$out" | sed 's/^/         | /' | tail -40
  fi
}

# 직전 run_capture 출력에 줄이 그대로 있는지
has_line() { grep -qxF -- "$1" <<<"$OUT" || { echo "missing line: $1"; echo "--- output:"; echo "$OUT"; return 1; }; }
has_text() { grep -qF -- "$1" <<<"$OUT" || { echo "missing text: $1"; echo "--- output:"; echo "$OUT"; return 1; }; }
lacks_text() { ! grep -qF -- "$1" <<<"$OUT" || { echo "unexpected text: $1"; echo "--- output:"; echo "$OUT"; return 1; }; }
# 명령을 돌려 OUT 에 stdout+stderr 를 담고 종료 코드를 RC 에 둔다
run_capture() { OUT="$("$@" 2>&1)"; RC=$?; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAKE="$TMP/fake"
mkdir -p "$FAKE/bin" "$FAKE/state"
export FAKE_STATE="$FAKE/state"

need_linter() {
  if command -v "$1" >/dev/null 2>&1; then return 0; fi
  if [ "${REQUIRE_LINTERS:-0}" = 1 ]; then echo "  FAIL - $1 이 없다(REQUIRE_LINTERS=1)"; failures=$((failures + 1)); else echo "  WARNING: $1 이 없어 건너뛴다" >&2; fi
  return 1
}

# ---------------------------------------------------------------- 가짜 명령 (PATH 앞에 둔다)
cat >"$FAKE/bin/aws" <<'SH'
#!/usr/bin/env bash
# 가짜 aws: 인자를 로그에 남기고 서브커맨드별로 정해진 응답을 낸다
set -u
S="$FAKE_STATE"
printf 'aws %s\n' "$*" >>"$S/calls.log"
file_arg() { local a; for a in "$@"; do case "$a" in file://*) cat "${a#file://}";; esac; done; }
case "$1 $2" in
  "ecr get-login-password") echo FAKE_ECR_TOKEN ;;
  "ecr describe-images") echo '{"imageDetails":[{}]}' ;;
  "ssm send-command") file_arg "$@" >"$S/send-params.json"; echo cmd-123 ;;
  "ssm get-command-invocation")
    n=$(cat "$S/inv_count" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" >"$S/inv_count"
    read -r -a st <<<"${FAKE_STATUSES:-Success}"
    i=$((n - 1)); [ "$i" -ge "${#st[@]}" ] && i=$((${#st[@]} - 1))
    jq -n --arg s "${st[$i]}" '{Status:$s, StandardOutputContent:"remote stdout line", StandardErrorContent:"remote stderr line", ResponseCode:(if $s=="Success" then 0 else 1 end)}'
    ;;
  "ssm get-parameters-by-path") cat "$FAKE_PARAMS_JSON" ;;
  "ssm get-parameter")
    name=""; prev=""; for a in "$@"; do [ "$prev" = "--name" ] && name="$a"; prev="$a"; done
    if grep -qxF -- "$name" <<<"${FAKE_EXISTING:-}"; then echo "$name"; else echo "An error occurred (ParameterNotFound)" >&2; exit 254; fi
    ;;
  "ssm put-parameter") { file_arg "$@"; echo; } >>"$S/put.jsonl" ;;
  *) : ;;
esac
SH

cat >"$FAKE/bin/docker" <<'SH'
#!/usr/bin/env bash
# 가짜 docker: compose up 때의 IMAGE_TAG 를 "실행 중 태그"로 기록한다
set -u
S="$FAKE_STATE"
printf 'docker %s [IMAGE_TAG=%s]\n' "$*" "${IMAGE_TAG:-}" >>"$S/calls.log"
case " $* " in
  *" login "*) cat >/dev/null ;;
  *" compose "*" up "*) printf '%s\n' "${IMAGE_TAG:-}" >"$S/running_tag" ;;
  *" compose "*" logs "*) echo "fake api logs" ;;
esac
exit 0
SH

cat >"$FAKE/bin/curl" <<'SH'
#!/usr/bin/env bash
# 가짜 curl: 실행 중 태그가 FAKE_BAD_TAG 면 헬스체크 실패
S="$FAKE_STATE"
printf 'curl %s\n' "$*" >>"$S/calls.log"
running="$(cat "$S/running_tag" 2>/dev/null || true)"
if [ -n "${FAKE_BAD_TAG:-}" ] && [ "$running" = "$FAKE_BAD_TAG" ]; then exit 22; fi
echo '{"status":"UP"}'
SH

cat >"$FAKE/bin/terraform" <<'SH'
#!/usr/bin/env bash
# 가짜 terraform: output 만 흉내 낸다(FAKE_TF_JSON = terraform output -json 형식)
S="$FAKE_STATE"
printf 'terraform %s\n' "$*" >>"$S/calls.log"
args=(); for a in "$@"; do case "$a" in -chdir=*) ;; *) args+=("$a");; esac; done
case "${args[*]}" in
  "output -json") cat "$FAKE_TF_JSON" ;;
  "output -json "*) jq ".\"${args[2]}\".value" "$FAKE_TF_JSON" ;;
  "output -raw "*) jq -j ".\"${args[2]}\".value // empty" "$FAKE_TF_JSON" ;;
  *) exit 1 ;;
esac
SH

cat >"$FAKE/bin/npm" <<'SH'
#!/usr/bin/env bash
printf 'npm %s\n' "$*" >>"$FAKE_STATE/calls.log"
SH
chmod +x "$FAKE/bin/"*

reset_fake() { rm -rf "${FAKE_STATE:?}"/*; }
# 가짜 PATH 로 명령 실행
with_fakes() { PATH="$FAKE/bin:$PATH" "$@"; }

echo "deploy tests"

# ================================================================ TASK-17#1 백엔드 이미지·배포
# Dockerfile: 멀티스테이지, eclipse-temurin:25-jre 런타임, non-root, MaxRAMPercentage=60
check "TASK-17#1 backend/Dockerfile 존재" test -f backend/Dockerfile
check "TASK-17#1 Dockerfile 런타임은 eclipse-temurin:25-jre" grep -Eq '^FROM eclipse-temurin:25-jre( |$)' backend/Dockerfile
check "TASK-17#1 Dockerfile 은 JDK 25 빌드 스테이지(멀티스테이지)" grep -Eq '^FROM eclipse-temurin:25-jdk AS build' backend/Dockerfile
check "TASK-17#1 Dockerfile 은 non-root USER" sh -c 'grep -E "^USER " backend/Dockerfile | tail -1 | grep -Evq "USER (root|0)( |$)"'
check "TASK-17#1 Dockerfile JAVA_TOOL_OPTIONS MaxRAMPercentage=60" grep -q 'MaxRAMPercentage=60' backend/Dockerfile
check "TASK-17#1 backend/.dockerignore 가 build·.gradle 을 뺀다" sh -c 'grep -qx "build" backend/.dockerignore && grep -qx ".gradle" backend/.dockerignore'
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  check "TASK-17#1 docker buildx build --check (Dockerfile lint)" docker buildx build --check --platform linux/arm64 backend
else
  echo "  WARNING: docker 데몬이 없어 Dockerfile 검사를 건너뛴다" >&2
fi

for f in scripts/build-backend.sh scripts/deploy-backend.sh deploy/remote-deploy.sh; do
  check "TASK-17#1 $f 실행 가능" test -x "$f"
done

# build-backend: ECR 로그인 + buildx linux/arm64 → <repo>:<tag>, latest, push
t_build_backend_dry() {
  run_capture env DRY_RUN=1 AWS_REGION=us-east-1 ECR_REPOSITORY_URL=123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api IMAGE_TAG=abc123 ./scripts/build-backend.sh
  [ "$RC" = 0 ] || { echo "rc=$RC"; echo "$OUT"; return 1; }
  has_line "[dry-run] aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com" &&
    has_line "[dry-run] docker buildx build --platform linux/arm64 --provenance=false --tag 123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api:abc123 --tag 123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api:latest --push $ROOT/backend"
}
check "TASK-17#1 build-backend DRY_RUN: ECR 로그인 + arm64 buildx push(태그 sha·latest)" t_build_backend_dry
t_build_backend_requires() {
  run_capture env -u ECR_REPOSITORY_URL DRY_RUN=1 AWS_REGION=us-east-1 IMAGE_TAG=abc ./scripts/build-backend.sh
  [ "$RC" != 0 ] && has_text "ECR_REPOSITORY_URL"
}
check "TASK-17#1 build-backend 는 ECR_REPOSITORY_URL 없으면 실패" t_build_backend_requires
t_build_backend_default_tag() {
  local sha; sha="$(git rev-parse --short=12 HEAD)"
  run_capture env -u IMAGE_TAG DRY_RUN=1 ALLOW_DIRTY=1 AWS_REGION=us-east-1 ECR_REPOSITORY_URL=r.example/board-api ./scripts/build-backend.sh
  [ "$RC" = 0 ] && has_text "--tag r.example/board-api:$sha "
}
check "TASK-17#1 build-backend 기본 태그는 git short sha(12)" t_build_backend_default_tag

# deploy-backend: DRY_RUN 은 send-command(AWS-RunShellScript, 인스턴스)와 원격 명령을 보이고 실행하지 않는다
DEPLOY_ENV=(AWS_REGION=us-east-1 ECR_REPOSITORY_URL=123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api INSTANCE_ID=i-0abc IMAGE_TAG=abc123)
t_deploy_backend_dry() {
  reset_fake
  run_capture with_fakes env DRY_RUN=1 "${DEPLOY_ENV[@]}" ./scripts/deploy-backend.sh
  [ "$RC" = 0 ] || { echo "rc=$RC"; echo "$OUT"; return 1; }
  has_text "[dry-run] aws ssm send-command --region us-east-1 --instance-ids i-0abc --document-name AWS-RunShellScript" &&
    has_text "ECR_REPOSITORY_URL=123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api IMAGE_TAG=abc123" &&
    has_text "bash /opt/board/remote-deploy.sh" &&
    ! test -s "$FAKE_STATE/calls.log"
}
check "TASK-17#1 deploy-backend DRY_RUN: send-command(AWS-RunShellScript·인스턴스·태그), aws 미호출" t_deploy_backend_dry

# 실제 경로(가짜 aws): 원격 명령은 compose·render-env·remote-deploy 를 base64 로 실어 /opt/board 에 쓰고 실행한다
t_deploy_backend_success() {
  reset_fake
  run_capture with_fakes env FAKE_STATUSES="Pending InProgress Success" POLL_INTERVAL=0 "${DEPLOY_ENV[@]}" ./scripts/deploy-backend.sh
  [ "$RC" = 0 ] || { echo "rc=$RC"; echo "$OUT"; return 1; }
  has_text "remote stdout line" &&
    grep -q "aws ssm send-command --region us-east-1 --instance-ids i-0abc --document-name AWS-RunShellScript" "$FAKE_STATE/calls.log" &&
    grep -q "aws ecr describe-images" "$FAKE_STATE/calls.log" &&
    [ "$(grep -c 'get-command-invocation' "$FAKE_STATE/calls.log")" = 3 ] || return 1
  # 실린 파일을 풀어 원본과 같은지 확인
  local d="$TMP/remote"; rm -rf "$d"; mkdir -p "$d"
  jq -r '.commands[]' "$FAKE_STATE/send-params.json" >"$d/cmds.sh"
  local f
  for f in docker-compose.prod.yml render-env.sh remote-deploy.sh; do
    grep -q "base64 -d > /opt/board/$f.tmp" "$d/cmds.sh" || { echo "no $f in commands"; cat "$d/cmds.sh"; return 1; }
    sed -n "s#^echo \([A-Za-z0-9+/=]*\) | base64 -d > /opt/board/$f.tmp\$#\1#p" "$d/cmds.sh" | base64 -d >"$d/$f"
    cmp -s "$d/$f" "deploy/$f" || { echo "$f differs"; return 1; }
  done
  jq -e '.executionTimeout[0] | tonumber > 0' "$FAKE_STATE/send-params.json" >/dev/null
}
check "TASK-17#1 deploy-backend: send-command 에 파일 3개(base64) 실어 보내고 Success 까지 폴링" t_deploy_backend_success
t_deploy_backend_failure() {
  reset_fake
  run_capture with_fakes env FAKE_STATUSES="InProgress Failed" POLL_INTERVAL=0 "${DEPLOY_ENV[@]}" ./scripts/deploy-backend.sh
  [ "$RC" != 0 ] && has_text "remote stderr line" && has_text "Failed"
}
check "TASK-17#1 deploy-backend: 원격 실패면 0 이 아닌 종료 + stderr 출력" t_deploy_backend_failure
t_deploy_backend_bad_tag() {
  run_capture with_fakes env DRY_RUN=1 AWS_REGION=us-east-1 ECR_REPOSITORY_URL=r/x INSTANCE_ID=i-1 IMAGE_TAG='a;rm -rf /' ./scripts/deploy-backend.sh
  [ "$RC" != 0 ]
}
check "TASK-17#1 deploy-backend 는 이상한 태그 문자를 거부" t_deploy_backend_bad_tag

# remote-deploy.sh (EC2 에서 실행): env 렌더 → ECR 로그인 → pull/up → 헬스 대기 → 실패 시 이전 태그로 롤백
REMOTE_PARAMS="$TMP/params.json"
cat >"$REMOTE_PARAMS" <<'JSON'
{"Parameters":[
  {"Name":"/board/prod/DB_PASSWORD","Value":"pw"},
  {"Name":"/board/prod/JWT_SECRET","Value":"jwt"},
  {"Name":"/board/prod/OTP_HASH_KEY","Value":"otp"}
]}
JSON
remote_deploy() {
  local dir="$1" tag="$2"; shift 2
  with_fakes env DEPLOY_DIR="$dir" ECR_REPOSITORY_URL=123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api IMAGE_TAG="$tag" \
    AWS_REGION=us-east-1 SSM_PARAMETER_PATH=/board/prod LOG_GROUP=/board/prod FAKE_PARAMS_JSON="$REMOTE_PARAMS" \
    HEALTH_TIMEOUT=1 HEALTH_INTERVAL=0.2 "$@" bash "$dir/remote-deploy.sh"
}
remote_dir() { local d="$TMP/host-$1"; rm -rf "$d"; mkdir -p "$d"; cp deploy/remote-deploy.sh deploy/render-env.sh deploy/docker-compose.prod.yml "$d/" 2>/dev/null; echo "$d"; }
t_remote_success() {
  reset_fake
  local d; d="$(remote_dir ok)"
  run_capture remote_deploy "$d" tag-good
  [ "$RC" = 0 ] || { echo "rc=$RC"; echo "$OUT"; return 1; }
  [ "$(cat "$d/current_tag")" = tag-good ] &&
    grep -q "aws ecr get-login-password --region us-east-1" "$FAKE_STATE/calls.log" &&
    grep -q "docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com" "$FAKE_STATE/calls.log" &&
    grep -q "compose .* pull .*IMAGE_TAG=tag-good" "$FAKE_STATE/calls.log" &&
    grep -q "compose .* up -d .*IMAGE_TAG=tag-good" "$FAKE_STATE/calls.log" &&
    grep -q "curl .*http://127.0.0.1:8080/api/actuator/health" "$FAKE_STATE/calls.log"
}
check "TASK-17#1 remote-deploy: pull·up·헬스 OK 면 current_tag 기록" t_remote_success
t_remote_rollback() {
  reset_fake
  local d; d="$(remote_dir rb)"
  echo tag-old >"$d/current_tag"
  run_capture remote_deploy "$d" tag-bad FAKE_BAD_TAG=tag-bad
  [ "$RC" != 0 ] || { echo "expected failure"; echo "$OUT"; return 1; }
  [ "$(cat "$FAKE_STATE/running_tag")" = tag-old ] && [ "$(cat "$d/current_tag")" = tag-old ] &&
    has_text "rolled back to tag-old" && has_text "fake api logs"
}
check "TASK-17#1 remote-deploy: 헬스 실패면 이전 태그로 롤백하고 실패 종료" t_remote_rollback
t_remote_first_fail() {
  reset_fake
  local d; d="$(remote_dir first)"
  run_capture remote_deploy "$d" tag-bad FAKE_BAD_TAG=tag-bad
  [ "$RC" != 0 ] && [ ! -e "$d/current_tag" ] && has_text "no previous tag"
}
check "TASK-17#1 remote-deploy: 이전 태그가 없으면 롤백 없이 실패" t_remote_first_fail

# deploy/docker-compose.prod.yml: dummy env 로 compose config 검증
t_compose_config() {
  local d="$TMP/compose"; mkdir -p "$d"
  echo 'MARKER_APP="1"' >"$d/app.env"; echo 'MARKER_DB="1"' >"$d/db.env"
  local json
  json="$(DEPLOY_DIR="$d" ECR_REPOSITORY_URL=r.example/board-api IMAGE_TAG=abc AWS_REGION=us-east-1 LOG_GROUP=/board/prod \
    docker compose -f deploy/docker-compose.prod.yml config --format json)" || return 1
  jq -e '
    .services.api.image == "r.example/board-api:abc"
    and .services.db.image == "postgres:18-alpine"
    and (.services.db.volumes | any(.type == "bind" and .source == "/srv/pgdata" and .target == "/var/lib/postgresql"))
    and .services.api.depends_on.db.condition == "service_healthy"
    and (.services.db.healthcheck.test | tostring | test("pg_isready"))
    and .services.api.restart == "unless-stopped" and .services.db.restart == "unless-stopped"
    and (.services.api.ports | any(.target == 8080 and .published == "8080"))
    and ((.services.db.ports // []) | length == 0)
    and .services.api.logging.driver == "awslogs" and .services.db.logging.driver == "awslogs"
    and .services.api.logging.options["awslogs-group"] == "/board/prod"
    and .services.api.logging.options["awslogs-region"] == "us-east-1"
    and (.services.api.logging.options.tag | test("api"))
    and (.services.db.logging.options.tag | test("db"))
    and .services.api.environment.MARKER_APP == "1" and (.services.api.environment.MARKER_DB == null)
    and .services.db.environment.MARKER_DB == "1" and (.services.db.environment.MARKER_APP == null)
  ' <<<"$json" >/dev/null || { echo "$json"; return 1; }
}
if command -v docker >/dev/null 2>&1; then
  check "TASK-17#1 docker-compose.prod.yml: api+postgres18(/srv/pgdata 바인드)·awslogs·healthcheck·restart" t_compose_config
else
  echo "  WARNING: docker 가 없어 compose config 검사를 건너뛴다" >&2
fi

# ================================================================ TASK-17#2 프론트 배포
check "TASK-17#2 scripts/deploy-frontend.sh 실행 가능" test -x scripts/deploy-frontend.sh
FRONT_ENV=(AWS_REGION=us-east-1 WEB_BUCKET=board-prod-web-x DISTRIBUTION_ID=E123ABC)
t_front_dry() {
  reset_fake
  run_capture with_fakes env DRY_RUN=1 SKIP_BUILD=1 "${FRONT_ENV[@]}" ./scripts/deploy-frontend.sh
  [ "$RC" = 0 ] || { echo "rc=$RC"; echo "$OUT"; return 1; }
  # 1) 해시 붙은 정적 자산: 1년 immutable (지우지 않는다 — 열린 탭이 옛 청크를 받을 수 있게)
  has_line "[dry-run] aws s3 sync $ROOT/frontend/out/_next/static s3://board-prod-web-x/_next/static --region us-east-1 --cache-control 'public, max-age=31536000, immutable'" &&
    # 2) 나머지(HTML, RSC .txt 등): no-cache + --delete, _next/static 은 빼고
    has_line "[dry-run] aws s3 sync $ROOT/frontend/out s3://board-prod-web-x --region us-east-1 --delete --exclude '_next/static/*' --cache-control no-cache" &&
    has_line "[dry-run] aws cloudfront create-invalidation --distribution-id E123ABC --paths '/*'" &&
    lacks_text "npm" && ! test -s "$FAKE_STATE/calls.log"
}
check "TASK-17#2 deploy-frontend DRY_RUN: static immutable → 나머지 no-cache --delete → invalidation /*" t_front_dry
t_front_order() {
  run_capture with_fakes env DRY_RUN=1 SKIP_BUILD=1 "${FRONT_ENV[@]}" ./scripts/deploy-frontend.sh
  # 자산을 먼저 올리고 HTML 을 나중에 바꾼다 → 새 HTML 이 아직 없는 청크를 가리키는 순간이 없다
  local a b c
  a=$(grep -n "_next/static --region" <<<"$OUT" | cut -d: -f1); b=$(grep -n -- "--delete" <<<"$OUT" | cut -d: -f1); c=$(grep -n "create-invalidation" <<<"$OUT" | cut -d: -f1)
  [ -n "$a" ] && [ -n "$b" ] && [ -n "$c" ] && [ "$a" -lt "$b" ] && [ "$b" -lt "$c" ]
}
check "TASK-17#2 deploy-frontend 순서: 정적 자산 → HTML → invalidation" t_front_order
t_front_build() {
  run_capture with_fakes env DRY_RUN=1 "${FRONT_ENV[@]}" ./scripts/deploy-frontend.sh
  [ "$RC" = 0 ] && has_line "[dry-run] npm --prefix $ROOT/frontend ci" && has_line "[dry-run] npm --prefix $ROOT/frontend run build"
}
check "TASK-17#2 deploy-frontend 는 npm ci && npm run build 를 먼저 한다(SKIP_BUILD 없으면)" t_front_build
t_front_requires() {
  run_capture with_fakes env -u WEB_BUCKET DRY_RUN=1 SKIP_BUILD=1 AWS_REGION=us-east-1 DISTRIBUTION_ID=E1 ./scripts/deploy-frontend.sh
  [ "$RC" != 0 ] && has_text "WEB_BUCKET"
}
check "TASK-17#2 deploy-frontend 는 WEB_BUCKET 없으면 실패" t_front_requires
t_front_real_needs_out() {
  reset_fake
  run_capture with_fakes env SKIP_BUILD=1 FRONTEND_OUT_DIR="$TMP/no-such-out" "${FRONT_ENV[@]}" ./scripts/deploy-frontend.sh
  [ "$RC" != 0 ] && has_text "index.html" && ! grep -q "s3 sync" "$FAKE_STATE/calls.log" 2>/dev/null
}
check "TASK-17#2 deploy-frontend 는 빌드 결과(index.html)가 없으면 sync 하지 않고 실패" t_front_real_needs_out
t_front_real() {
  reset_fake
  local out="$TMP/out"; mkdir -p "$out/_next/static/chunks"; echo '<html>' >"$out/index.html"; echo x >"$out/_next/static/chunks/a.js"
  run_capture with_fakes env SKIP_BUILD=1 FRONTEND_OUT_DIR="$out" "${FRONT_ENV[@]}" ./scripts/deploy-frontend.sh
  [ "$RC" = 0 ] || { echo "$OUT"; return 1; }
  grep -qF "aws s3 sync $out/_next/static s3://board-prod-web-x/_next/static --region us-east-1 --cache-control public, max-age=31536000, immutable" "$FAKE_STATE/calls.log" &&
    grep -qF "aws cloudfront create-invalidation --distribution-id E123ABC --paths /*" "$FAKE_STATE/calls.log"
}
check "TASK-17#2 deploy-frontend 실제 경로(가짜 aws): sync 2회 + invalidation 호출" t_front_real

# ================================================================ TASK-17#3 시크릿: put-secrets(로컬 → SSM), render-env(EC2: SSM → env 파일)
# terraform output -json 형식의 가짜 출력(AC#5 tf-outputs 테스트도 쓴다)
export FAKE_TF_JSON="$TMP/tf-output.json"
cat >"$FAKE_TF_JSON" <<'JSON'
{
  "region": {"sensitive": false, "type": "string", "value": "us-east-1"},
  "ecr_repository_url": {"sensitive": false, "type": "string", "value": "123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api"},
  "web_bucket": {"sensitive": false, "type": "string", "value": "board-prod-web-x"},
  "distribution_id": {"sensitive": false, "type": "string", "value": "E123ABC"},
  "instance_id": {"sensitive": false, "type": "string", "value": "i-0abc"},
  "ssm_parameter_path": {"sensitive": false, "type": "string", "value": "/board/prod"},
  "log_group": {"sensitive": false, "type": "string", "value": "/board/prod"},
  "deploy_dir": {"sensitive": false, "type": "string", "value": "/opt/board"},
  "ci_role_arn": {"sensitive": false, "type": "string", "value": "arn:aws:iam::123456789012:role/board-prod-gha-deploy"},
  "app_env": {"sensitive": false, "type": ["object", {}], "value": {
    "PUBLIC_BASE_URL": "https://d111.cloudfront.net", "COOKIE_SECURE": "true", "CLIENT_IP_HEADER": "CloudFront-Viewer-Address",
    "UPLOADS_BUCKET": "board-prod-uploads-x", "UPLOADS_REGION": "us-east-1", "UPLOADS_PUBLIC_BASE_PATH": "/uploads/",
    "COGNITO_ISSUER_URI": "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_X", "COGNITO_CLIENT_ID": "cid123",
    "COGNITO_DOMAIN": "https://x.auth.us-east-1.amazoncognito.com", "SMS_AWS_REGION": "us-east-1"}},
  "cognito_client_secret": {"sensitive": true, "type": "string", "value": "COGNITO-SECRET-VALUE"}
}
JSON
SECRETS_IN="$TMP/secrets-input.txt"
cat >"$SECRETS_IN" <<'TXT'
# 주석과 빈 줄은 건너뛴다

JWT_SECRET=jwt-secret-value-0123456789abcdef0123456789
GOOGLE_CLIENT_ID=gid.apps.example
GOOGLE_CLIENT_SECRET="google-secret-value"
ADMIN_EMAIL=admin@example.com
KAKAO_CLIENT_SECRET=
PUBLIC_BASE_URL=https://board.example.com
SMS_PROVIDER=aws
TXT

check "TASK-17#3 scripts/put-secrets.sh 실행 가능" test -x scripts/put-secrets.sh
check "TASK-17#3 deploy/render-env.sh 실행 가능" test -x deploy/render-env.sh
check "TASK-17#3 deploy/secrets.example.txt(입력 예시) 존재" test -f deploy/secrets.example.txt
check "TASK-17#3 gitignore 가 실제 입력 deploy/secrets.local.txt 를 무시" git check-ignore -q --no-index deploy/secrets.local.txt
check "TASK-17#3 gitignore 가 예시 deploy/secrets.example.txt 는 추적" sh -c '! git check-ignore -q --no-index deploy/secrets.example.txt'
for k in DB_PASSWORD JWT_SECRET OTP_HASH_KEY ADMIN_EMAIL GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GITHUB_CLIENT_ID GITHUB_CLIENT_SECRET \
  NAVER_CLIENT_ID NAVER_CLIENT_SECRET KAKAO_CLIENT_ID KAKAO_CLIENT_SECRET SMS_PROVIDER SMS_ORIGINATION_IDENTITY; do
  check "TASK-17#3 secrets.example.txt 에 $k" grep -Eq "^#? ?$k=" deploy/secrets.example.txt
done

t_put_dry() {
  reset_fake
  run_capture with_fakes env -u AWS_REGION DRY_RUN=1 ./scripts/put-secrets.sh "$SECRETS_IN"
  [ "$RC" = 0 ] || { echo "rc=$RC"; echo "$OUT"; return 1; }
  local p="[dry-run] aws ssm put-parameter --region us-east-1 --name /board/prod"
  has_line "$p/JWT_SECRET --type SecureString --value '****' --overwrite" &&
    has_line "$p/GOOGLE_CLIENT_SECRET --type SecureString --value '****' --overwrite" &&
    has_line "$p/COGNITO_CLIENT_SECRET --type SecureString --value '****' --overwrite" &&
    has_line "$p/GOOGLE_CLIENT_ID --type String --value gid.apps.example --overwrite" &&
    has_line "$p/ADMIN_EMAIL --type String --value admin@example.com --overwrite" &&
    has_line "$p/COGNITO_CLIENT_ID --type String --value cid123 --overwrite" &&
    has_line "$p/COOKIE_SECURE --type String --value true --overwrite" &&
    has_line "$p/CLIENT_IP_HEADER --type String --value CloudFront-Viewer-Address --overwrite" &&
    has_line "$p/UPLOADS_BUCKET --type String --value board-prod-uploads-x --overwrite" &&
    has_line "$p/PUBLIC_BASE_URL --type String --value https://board.example.com --overwrite" &&
    has_line "$p/SMS_PROVIDER --type String --value aws --overwrite" &&
    lacks_text "KAKAO_CLIENT_SECRET --type" &&
    lacks_text "jwt-secret-value" && lacks_text "google-secret-value" && lacks_text "COGNITO-SECRET-VALUE" &&
    ! grep -q "^aws " "$FAKE_STATE/calls.log" 2>/dev/null
}
check "TASK-17#3 put-secrets DRY_RUN: 시크릿 SecureString·마스킹, 나머지 String, app_env·cognito secret 병합, 입력이 우선, 빈 값은 건너뜀" t_put_dry
t_put_generate_dry() {
  run_capture with_fakes env DRY_RUN=1 AWS_REGION=us-east-1 ./scripts/put-secrets.sh --generate-missing "$SECRETS_IN"
  [ "$RC" = 0 ] || { echo "$OUT"; return 1; }
  local p="[dry-run] aws ssm put-parameter --region us-east-1 --name /board/prod"
  has_line "$p/DB_PASSWORD --type SecureString --value '****' --overwrite" &&
    has_line "$p/OTP_HASH_KEY --type SecureString --value '****' --overwrite" &&
    [ "$(grep -c -- "/JWT_SECRET --type" <<<"$OUT")" = 1 ]
}
check "TASK-17#3 put-secrets --generate-missing: 없는 DB_PASSWORD·OTP_HASH_KEY 생성(마스킹), 입력에 있는 JWT_SECRET 은 그대로" t_put_generate_dry
t_put_real() {
  reset_fake
  run_capture with_fakes env AWS_REGION=us-east-1 FAKE_EXISTING="/board/prod/DB_PASSWORD" ./scripts/put-secrets.sh --generate-missing "$SECRETS_IN"
  [ "$RC" = 0 ] || { echo "$OUT"; return 1; }
  local put="$FAKE_STATE/put.jsonl"
  lacks_text "jwt-secret-value" && lacks_text "COGNITO-SECRET-VALUE" || return 1
  jq -se '
      (map({(.Name): .}) | add) as $m
      | $m["/board/prod/JWT_SECRET"].Value == "jwt-secret-value-0123456789abcdef0123456789"
      and $m["/board/prod/JWT_SECRET"].Type == "SecureString" and $m["/board/prod/JWT_SECRET"].Overwrite == true
      and $m["/board/prod/GOOGLE_CLIENT_SECRET"].Value == "google-secret-value"
      and $m["/board/prod/COGNITO_CLIENT_SECRET"].Value == "COGNITO-SECRET-VALUE"
      and $m["/board/prod/GOOGLE_CLIENT_ID"].Type == "String"
      and ($m["/board/prod/OTP_HASH_KEY"].Value | length >= 32)
      and ($m["/board/prod/DB_PASSWORD"] == null)
    ' "$put" >/dev/null || { cat "$put"; return 1; }
  # 값은 명령줄(argv)이 아니라 --cli-input-json 파일로 넘긴다
  ! grep -q -- "--value" "$FAKE_STATE/calls.log" && grep -q "put-parameter --region us-east-1 --cli-input-json file://" "$FAKE_STATE/calls.log"
}
check "TASK-17#3 put-secrets 실제 경로(가짜 aws): 값은 cli-input-json 파일로, SSM 에 이미 있는 DB_PASSWORD 는 다시 만들지 않음" t_put_real
t_put_warn_missing() {
  run_capture with_fakes env DRY_RUN=1 AWS_REGION=us-east-1 ./scripts/put-secrets.sh "$SECRETS_IN"
  [ "$RC" = 0 ] && has_text "DB_PASSWORD" && has_text "--generate-missing" && lacks_text "/DB_PASSWORD --type"
}
check "TASK-17#3 put-secrets: --generate-missing 없이 필수 시크릿이 없으면 경고만(생성 안 함)" t_put_warn_missing
t_put_bad_key() {
  local f="$TMP/bad.txt"; printf 'bad-key=1\n' >"$f"
  run_capture with_fakes env DRY_RUN=1 AWS_REGION=us-east-1 ./scripts/put-secrets.sh "$f"
  [ "$RC" != 0 ] && has_text "bad-key"
}
check "TASK-17#3 put-secrets: 키 형식이 아닌 줄은 거부" t_put_bad_key
t_put_usage() { run_capture with_fakes env DRY_RUN=1 ./scripts/put-secrets.sh; [ "$RC" != 0 ] && has_text "usage"; }
check "TASK-17#3 put-secrets: 입력 파일 인자가 없으면 usage" t_put_usage

# render-env: 까다로운 값($, ", \, ', 공백, #, 백틱, 줄바꿈)이 compose env_file 에서 그대로 읽히는지
RENDER_PARAMS="$TMP/render-params.json"
cat >"$RENDER_PARAMS" <<'JSON'
{"Parameters":[
  {"Name":"/board/prod/DB_PASSWORD","Type":"SecureString","Value":"pw$HOME \"dq\" \\back 'sq' `bt` # hash"},
  {"Name":"/board/prod/JWT_SECRET","Type":"SecureString","Value":"line1\nline2  "},
  {"Name":"/board/prod/OTP_HASH_KEY","Type":"SecureString","Value":"otp${X}key\\"},
  {"Name":"/board/prod/PUBLIC_BASE_URL","Type":"String","Value":"https://board.example.com"},
  {"Name":"/board/prod/nested/IGNORED","Type":"String","Value":"x"},
  {"Name":"/board/prod/bad-key","Type":"String","Value":"x"}
]}
JSON
mode_of() { python3 -c 'import os,sys;print(oct(os.stat(sys.argv[1]).st_mode & 0o777))' "$1"; }
t_render_env() {
  reset_fake
  local d="$TMP/render"; rm -rf "$d"
  run_capture with_fakes env AWS_REGION=us-east-1 FAKE_PARAMS_JSON="$RENDER_PARAMS" deploy/render-env.sh /board/prod "$d"
  [ "$RC" = 0 ] || { echo "$OUT"; return 1; }
  grep -qF "aws ssm get-parameters-by-path --region us-east-1 --path /board/prod --recursive --with-decryption" "$FAKE_STATE/calls.log" || return 1
  [ "$(mode_of "$d/app.env")" = 0o600 ] && [ "$(mode_of "$d/db.env")" = 0o600 ] || { echo "mode"; return 1; }
  local json
  json="$(DEPLOY_DIR="$d" ECR_REPOSITORY_URL=r/x IMAGE_TAG=t AWS_REGION=us-east-1 docker compose -f deploy/docker-compose.prod.yml config --format json)" || return 1
  # compose config 는 $ 를 $$ 로 다시 이스케이프해 보인다
  jq -n --slurpfile p "$RENDER_PARAMS" --argjson c "$json" '
    ($p[0].Parameters | map({(.Name | sub("^/board/prod/"; "")): .Value}) | add) as $want
    | ($c.services.api.environment | map_values(gsub("\\$\\$"; "$"))) as $api
    | ($c.services.db.environment | map_values(gsub("\\$\\$"; "$"))) as $db
    | $api.DB_PASSWORD == $want.DB_PASSWORD and $api.JWT_SECRET == $want.JWT_SECRET and $api.OTP_HASH_KEY == $want.OTP_HASH_KEY
      and $api.PUBLIC_BASE_URL == "https://board.example.com"
      and $api.DB_URL == "jdbc:postgresql://db:5432/board" and $api.DB_USERNAME == "board"
      and ($api | has("IGNORED") | not) and ($api | has("bad-key") | not)
      and $db.POSTGRES_PASSWORD == $want.DB_PASSWORD and $db.POSTGRES_USER == "board" and $db.POSTGRES_DB == "board"
      and ($db | has("JWT_SECRET") | not)
  ' >/dev/null || { echo "$json" | jq '.services | map_values(.environment)'; return 1; }
}
if command -v docker >/dev/null 2>&1; then
  check "TASK-17#3 render-env: SSM → app.env·db.env(600), 특수문자 값이 compose 에서 그대로, DB_URL 기본값" t_render_env
fi
t_render_missing() {
  local d="$TMP/render-missing"; rm -rf "$d"; local p="$TMP/p2.json"
  echo '{"Parameters":[{"Name":"/board/prod/DB_PASSWORD","Value":"x"}]}' >"$p"
  run_capture with_fakes env AWS_REGION=us-east-1 FAKE_PARAMS_JSON="$p" deploy/render-env.sh /board/prod "$d"
  [ "$RC" != 0 ] && has_text "JWT_SECRET" && [ ! -e "$d/app.env" ]
}
check "TASK-17#3 render-env: 필수 시크릿이 없으면 파일을 만들지 않고 실패" t_render_missing

# ================================================================ TASK-17#4 GitHub Actions
CI=.github/workflows/ci.yml
DEPLOY=.github/workflows/deploy.yml
check "TASK-17#4 $CI 존재" test -f "$CI"
check "TASK-17#4 $DEPLOY 존재" test -f "$DEPLOY"
if need_linter actionlint; then
  check "TASK-17#4 actionlint (ci.yml, deploy.yml)" actionlint -shellcheck "$(command -v shellcheck || true)" "$CI" "$DEPLOY"
fi
# ci: PR + main 이 아닌 push + workflow_call(deploy 가 재사용), java 25·node 24·terraform, make test, 빌드
check "TASK-17#4 ci: pull_request 트리거" grep -Eq '^  pull_request:' "$CI"
check "TASK-17#4 ci: main 이 아닌 브랜치 push" sh -c "grep -A3 -E '^  push:' $CI | grep -q 'branches-ignore'"
check "TASK-17#4 ci: workflow_call(deploy 가 재사용)" grep -Eq '^  workflow_call:' "$CI"
check "TASK-17#4 ci: setup-java temurin 25" sh -c "grep -q 'actions/setup-java@' $CI && grep -Eq 'java-version: \"?25\"?' $CI && grep -q 'distribution: temurin' $CI"
check "TASK-17#4 ci: setup-node 24 + npm 캐시" sh -c "grep -q 'actions/setup-node@' $CI && grep -Eq 'node-version: \"?24\"?' $CI && grep -q 'cache: npm' $CI"
check "TASK-17#4 ci: setup-terraform (wrapper 끔)" sh -c "grep -q 'hashicorp/setup-terraform@' $CI && grep -q 'terraform_wrapper: false' $CI"
check "TASK-17#4 ci: make test (린터 필수)" sh -c "grep -q 'run: make test' $CI && grep -q 'REQUIRE_LINTERS: \"1\"' $CI"
check "TASK-17#4 ci: actionlint 설치" grep -q 'download-actionlint.bash' "$CI"
check "TASK-17#4 ci: 빌드(bootJar, 프론트 build)" sh -c "grep -q 'bootJar' $CI && grep -q 'npm run build' $CI"
check "TASK-17#4 ci: 권한은 contents: read" grep -q 'contents: read' "$CI"
# deploy: main push + 수동, OIDC, arm 러너, 직렬화, 같은 스크립트, environment 없음
check "TASK-17#4 deploy: main push + workflow_dispatch" sh -c "grep -A2 -E '^  push:' $DEPLOY | grep -Eq 'branches: \\[main\\]' && grep -Eq '^  workflow_dispatch:' $DEPLOY"
check "TASK-17#4 deploy: id-token: write" grep -q 'id-token: write' "$DEPLOY"
check "TASK-17#4 deploy: concurrency deploy-prod, 취소 안 함" sh -c "grep -q 'group: deploy-prod' $DEPLOY && grep -q 'cancel-in-progress: false' $DEPLOY"
check "TASK-17#4 deploy: ubuntu-24.04-arm 러너" grep -q 'runs-on: ubuntu-24.04-arm' "$DEPLOY"
check "TASK-17#4 deploy: configure-aws-credentials role-to-assume = vars.AWS_DEPLOY_ROLE_ARN" sh -c "grep -q 'aws-actions/configure-aws-credentials@' $DEPLOY && grep -q 'role-to-assume: \${{ vars.AWS_DEPLOY_ROLE_ARN }}' $DEPLOY && grep -q 'aws-region: \${{ vars.AWS_REGION }}' $DEPLOY"
for v in ECR_REPOSITORY_URL WEB_BUCKET DISTRIBUTION_ID INSTANCE_ID AWS_REGION; do
  check "TASK-17#4 deploy: 리포 variable $v" grep -q "$v: \${{ vars.$v }}" "$DEPLOY"
done
check "TASK-17#4 deploy: 테스트(ci.yml) 통과 뒤 배포" sh -c "grep -q 'uses: ./.github/workflows/ci.yml' $DEPLOY && grep -q 'needs: test' $DEPLOY"
t_deploy_order() {
  local a b c
  a=$(grep -n 'scripts/build-backend.sh' "$DEPLOY" | cut -d: -f1); b=$(grep -n 'scripts/deploy-backend.sh' "$DEPLOY" | cut -d: -f1); c=$(grep -n 'scripts/deploy-frontend.sh' "$DEPLOY" | cut -d: -f1)
  [ -n "$a" ] && [ -n "$b" ] && [ -n "$c" ] && [ "$a" -lt "$b" ] && [ "$b" -lt "$c" ]
}
check "TASK-17#4 deploy: build-backend → deploy-backend → deploy-frontend 순서" t_deploy_order
check "TASK-17#4 deploy: GitHub environment 를 쓰지 않는다(OIDC sub 가 바뀐다)" sh -c "! grep -Eq '^ +environment:' $DEPLOY"
check "TASK-17#4 deploy: main 에서만 배포 job 실행" grep -q "if: github.ref == 'refs/heads/main'" "$DEPLOY"
t_actions_pinned() {
  # 모든 uses: 는 메이저 버전 태그(@vN)로 고정한다(로컬 ./ 재사용 워크플로 제외)
  local bad
  bad="$(grep -hE '^\s*(- )?uses:' "$CI" "$DEPLOY" | grep -v 'uses: ./' | grep -Ev '@v[0-9]+$' || true)"
  [ -z "$bad" ] || { echo "$bad"; return 1; }
}
check "TASK-17#4 액션은 메이저 버전(@vN)으로 고정" t_actions_pinned

# ================================================================ TASK-17#5 로컬 make deploy = CI 와 같은 스크립트 (값은 terraform output)
check "TASK-17#5 scripts/tf-outputs.sh 실행 가능" test -x scripts/tf-outputs.sh
t_tf_outputs_print() {
  reset_fake
  run_capture with_fakes ./scripts/tf-outputs.sh
  [ "$RC" = 0 ] || { echo "$OUT"; return 1; }
  has_line "export AWS_REGION=us-east-1" &&
    has_line "export ECR_REPOSITORY_URL=123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api" &&
    has_line "export WEB_BUCKET=board-prod-web-x" &&
    has_line "export DISTRIBUTION_ID=E123ABC" &&
    has_line "export INSTANCE_ID=i-0abc" &&
    has_line "export SSM_PARAMETER_PATH=/board/prod" &&
    has_line "export LOG_GROUP=/board/prod" &&
    has_line "export DEPLOY_DIR=/opt/board" &&
    grep -q "terraform -chdir=$ROOT/infra/main output -json" "$FAKE_STATE/calls.log"
}
check "TASK-17#5 tf-outputs: terraform output -json → export 줄(CI 리포 variables 와 같은 이름)" t_tf_outputs_print
t_tf_outputs_exec() {
  # 셸에 남은 다른 리전 값보다 terraform 값이 이긴다(엉뚱한 리전에 배포 방지)
  # shellcheck disable=SC2016 # 하위 셸에서 풀린다
  run_capture with_fakes env AWS_REGION=ap-northeast-2 ./scripts/tf-outputs.sh -- sh -c 'echo "R=$AWS_REGION I=$INSTANCE_ID B=$WEB_BUCKET"'
  [ "$RC" = 0 ] && has_line "R=us-east-1 I=i-0abc B=board-prod-web-x"
}
check "TASK-17#5 tf-outputs -- <명령>: 값을 export 하고 실행(terraform 값이 셸 값보다 우선)" t_tf_outputs_exec
t_tf_outputs_missing() {
  local j="$TMP/tf-empty.json"; echo '{}' >"$j"
  run_capture with_fakes env FAKE_TF_JSON="$j" ./scripts/tf-outputs.sh
  [ "$RC" != 0 ] && has_text "terraform"
}
check "TASK-17#5 tf-outputs: 출력이 없으면(apply 전) 실패" t_tf_outputs_missing

for t in deploy deploy-backend deploy-frontend put-secrets; do
  check "TASK-17#5 make $t 타깃" grep -Eq "^$t:" Makefile
done
check "TASK-17#5 make deploy-backend 는 tf-outputs 로 build-backend·deploy-backend 를 부른다" sh -c 'make -n deploy-backend | grep -q "tf-outputs.sh -- ./scripts/build-backend.sh" && make -n deploy-backend | grep -q "tf-outputs.sh -- ./scripts/deploy-backend.sh"'
check "TASK-17#5 make deploy-frontend 는 tf-outputs 로 deploy-frontend 를 부른다" sh -c 'make -n deploy-frontend | grep -q "tf-outputs.sh -- ./scripts/deploy-frontend.sh"'
check "TASK-17#5 make put-secrets 는 put-secrets.sh 에 SECRETS_FILE 을 넘긴다" sh -c 'make -n put-secrets | grep -q "put-secrets.sh .*deploy/secrets.local.txt"'
CLEAN_ENV=(-u AWS_REGION -u ECR_REPOSITORY_URL -u WEB_BUCKET -u DISTRIBUTION_ID -u INSTANCE_ID -u SSM_PARAMETER_PATH -u LOG_GROUP -u DEPLOY_DIR)
t_make_deploy_dry() {
  reset_fake
  run_capture with_fakes env "${CLEAN_ENV[@]}" DRY_RUN=1 IMAGE_TAG=abc123 SKIP_BUILD=1 make -s deploy
  [ "$RC" = 0 ] || { echo "$OUT"; return 1; }
  has_line "[dry-run] docker buildx build --platform linux/arm64 --provenance=false --tag 123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api:abc123 --tag 123456789012.dkr.ecr.us-east-1.amazonaws.com/board-api:latest --push $ROOT/backend" &&
    has_text "[dry-run] aws ssm send-command --region us-east-1 --instance-ids i-0abc --document-name AWS-RunShellScript" &&
    has_line "[dry-run] aws s3 sync $ROOT/frontend/out s3://board-prod-web-x --region us-east-1 --delete --exclude '_next/static/*' --cache-control no-cache" &&
    has_line "[dry-run] aws cloudfront create-invalidation --distribution-id E123ABC --paths '/*'" &&
    ! grep -q "^aws " "$FAKE_STATE/calls.log"
}
check "TASK-17#5 DRY_RUN=1 make deploy: terraform output 값으로 CI 와 같은 명령(빌드·send-command·sync·invalidation)" t_make_deploy_dry
t_make_put_secrets_dry() {
  reset_fake
  run_capture with_fakes env "${CLEAN_ENV[@]}" DRY_RUN=1 make -s put-secrets SECRETS_FILE="$SECRETS_IN"
  [ "$RC" = 0 ] && has_line "[dry-run] aws ssm put-parameter --region us-east-1 --name /board/prod/JWT_SECRET --type SecureString --value '****' --overwrite" &&
    lacks_text "jwt-secret-value"
}
check "TASK-17#5 DRY_RUN=1 make put-secrets SECRETS_FILE=...: 마스킹된 put-parameter" t_make_put_secrets_dry

# README 배포 절
for w in "## 배포 (TASK-17)" "make put-secrets" "make deploy" "AWS_DEPLOY_ROLE_ARN" "ECR_REPOSITORY_URL" "WEB_BUCKET" "DISTRIBUTION_ID" "INSTANCE_ID" \
  "current_tag" "environment" "DRY_RUN=1" "/board/prod" "--generate-missing"; do
  check "TASK-17#5 README 배포 절에 '$w'" grep -qF -- "$w" README.md
done

# ---------------------------------------------------------------- 배포 스모크 (TASK-18#1)
# scripts/test/smoke_test.sh 를 가짜 HTTP 서버(CloudFront+S3+API 흉내)에 돌려 검사 로직을 확인한다. 실제 배포 대상은 make smoke.
SMOKE="scripts/test/smoke_test.sh"
check "TASK-18#1 smoke_test.sh 실행 가능" test -x "$SMOKE"
check "TASK-18#1 make smoke 타깃은 smoke_test.sh 를 호출" sh -c 'make -n smoke | grep -q "scripts/test/smoke_test.sh"'
check "TASK-18#1 make test(scripts/test.sh)는 실제 배포 스모크를 부르지 않는다" sh -c '! grep -q "smoke_test.sh" scripts/test.sh'
run_capture env -u SMOKE_BASE_URL "$SMOKE"
# rc_and_text <기대 종료 코드: 0|nonzero> <문자열> — 직전 run_capture 결과 검사
rc_and_text() {
  if [ "$1" = 0 ]; then [ "$RC" = 0 ] || { echo "rc=$RC"; echo "$OUT"; return 1; }
  else [ "$RC" != 0 ] || { echo "rc=0 (실패를 기대)"; echo "$OUT"; return 1; }; fi
  has_text "$2"
}
check "TASK-18#1 SMOKE_BASE_URL 이 없으면 SKIP·종료 코드 0" rc_and_text 0 "SKIP - SMOKE_BASE_URL"

cat >"$TMP/fake_site.py" <<'PY'
# 가짜 배포: 정상(기본) 또는 FAKE_SMOKE_BROKEN=<항목> 으로 한 가지를 망가뜨린다
import http.server, os, sys
broken = os.environ.get("FAKE_SMOKE_BROKEN", "")
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, ctype, body, headers=()):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        for k, v in headers: self.send_header(k, v)
        b = body.encode()
        self.send_header("Content-Length", str(len(b)))
        self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        p = self.path
        if p == "/api/actuator/health":
            self.send(503 if broken == "health" else 200, "application/vnd.spring-boot.actuator.v3+json",
                      '{"status":"DOWN"}' if broken == "health" else '{"status":"UP"}')
        elif p == "/":
            cache = "max-age=3600" if broken == "index" else "no-cache"
            self.send(200, "text/html", '<html><script src="/_next/static/chunks/main-abc123.js" async=""></script></html>',
                      [("Cache-Control", cache)])
        elif p == "/_next/static/chunks/main-abc123.js":
            cache = "no-cache" if broken == "asset" else "public, max-age=31536000, immutable"
            self.send(200, "application/javascript", "1", [("Cache-Control", cache)])
        elif p == "/api/auth/csrf":
            hs = [] if broken == "csrf" else [("Set-Cookie", "XSRF-TOKEN=t0k; Path=/; SameSite=Lax")]
            self.send(200, "application/json", "{}", hs)
        elif p == "/api/posts":
            self.send(200, "text/html" if broken == "posts" else "application/json", '{"items":[],"page":0}')
        else:
            self.send(404, "text/plain", "nf")
s = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(s.server_address[1]))
s.serve_forever()
PY
# smoke_against <망가뜨릴 항목|""> — 가짜 서버를 띄워 smoke_test.sh 를 돌리고 OUT·RC 를 남긴다
smoke_against() {
  local portf="$TMP/smoke_port" pid port
  rm -f "$portf"
  FAKE_SMOKE_BROKEN="$1" python3 "$TMP/fake_site.py" "$portf" &
  pid=$!
  for _ in $(seq 50); do [ -s "$portf" ] && break; sleep 0.1; done
  port="$(cat "$portf" 2>/dev/null)"
  run_capture env SMOKE_BASE_URL="http://127.0.0.1:$port/" SMOKE_TIMEOUT=5 "$SMOKE"
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
}
smoke_against ""
check "TASK-18#1 정상 배포면 5개 항목 모두 통과" rc_and_text 0 "smoke: 5 passed, 0 failed"
check "TASK-18#1 자산 경로는 / 의 HTML 에서 찾는다" has_text "/_next/static/chunks/main-abc123.js → 200 + immutable"
for b in health index asset csrf posts; do
  smoke_against "$b"
  check "TASK-18#1 $b 가 틀리면 실패(1개)" rc_and_text nonzero "smoke: 4 passed, 1 failed"
done

# ---------------------------------------------------------------- shellcheck (모든 배포 스크립트)
if need_linter shellcheck; then
  for f in scripts/*.sh deploy/*.sh scripts/test/*.sh; do
    [ -f "$f" ] || continue
    check "TASK-17#1 shellcheck $f" shellcheck -x "$f"
  done
fi

echo "deploy: $passes passed, $failures failed"
[ "$failures" -eq 0 ]
