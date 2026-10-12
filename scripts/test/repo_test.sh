#!/usr/bin/env bash
# shellcheck disable=SC2016 # sh -c '...' 안의 $ 는 하위 셸에서 풀린다
# 리포 골격 검증 테스트 (TASK-1). scripts/test.sh 가 호출한다.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

failures=0
passes=0

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    passes=$((passes + 1)); echo "  ok   - $name"
  else
    failures=$((failures + 1)); echo "  FAIL - $name"
  fi
}

has_make_target() { grep -Eq "^$1:" Makefile; }

echo "repo tests"

# TASK-1#3 Makefile + scripts/test.sh 로 백엔드·프론트 테스트를 일괄 실행한다
check "TASK-1#3 scripts/test.sh 실행 가능" test -x scripts/test.sh
check "TASK-1#3 Makefile 존재" test -f Makefile
for t in test dev-db dev-db-down backend-run backend-test frontend-dev frontend-test frontend-build; do
  check "TASK-1#3 make $t 타깃" has_make_target "$t"
done
check "TASK-1#3 make test 는 scripts/test.sh 를 호출" sh -c 'make -n test | grep -q "scripts/test.sh"'
check "TASK-1#3 scripts/test.sh 는 백엔드 테스트를 실행" grep -q "gradlew" scripts/test.sh
check "TASK-1#3 scripts/test.sh 는 프론트 테스트를 실행" grep -q "npm test" scripts/test.sh

# TASK-1#4 .claude-code-agile-hooks.json 에 testCommand 와 testFirst(sourceGlobs·testGlobs) 설정
check "TASK-1#4 hooks 설정이 testCommand·testFirst 를 갖는다" python3 - <<'PY'
import json, sys
c = json.load(open(".claude-code-agile-hooks.json"))
tf = c["testFirst"]
ok = (
    isinstance(c.get("testCommand"), str) and c["testCommand"].strip()
    and all(isinstance(tf.get(k), list) and tf[k] and all(isinstance(g, str) for g in tf[k])
            for k in ("sourceGlobs", "testGlobs"))
    and "backend/src/main/**" in tf["sourceGlobs"] and "frontend/src/**" in tf["sourceGlobs"]
    and "backend/src/test/**" in tf["testGlobs"] and "scripts/test/**" in tf["testGlobs"]
    and not any(g.startswith(("**", "frontend/**")) for g in tf["testGlobs"])
)
sys.exit(0 if ok else 1)
PY
check "TASK-1#4 testCommand 는 make test" sh -c 'python3 -c "import json;print(json.load(open(\".claude-code-agile-hooks.json\"))[\"testCommand\"])" | grep -qx "make test"'

# TASK-1#5 gitignore, env 예시 파일, README 초안
check "TASK-1#5 .gitignore 존재" test -f .gitignore
for p in .env .env.local frontend/node_modules/x frontend/.next/x frontend/out/x backend/build/x backend/.gradle/x infra/.terraform/x x.tfstate; do
  check "TASK-1#5 gitignore가 $p 를 무시" git check-ignore -q --no-index "$p"
done
check "TASK-1#5 gitignore가 gradle wrapper jar는 추적" sh -c '! git check-ignore -q --no-index backend/gradle/wrapper/gradle-wrapper.jar'
check "TASK-1#5 example.env 존재" test -f example.env
for k in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_PORT DB_URL DB_USERNAME DB_PASSWORD; do
  check "TASK-1#5 example.env 에 $k" grep -Eq "^$k=" example.env
done
check "TASK-1#5 README.md 존재" test -f README.md
for w in "make test" "make dev-db" "make backend-run" "make frontend-dev" "example.env" "/api/actuator/health"; do
  check "TASK-1#5 README 에 '$w' 안내" grep -qF "$w" README.md
done

# TASK-6#1 Terraform: fmt 검사 + 테스트가 있는 모듈마다 init(-backend=false)·test 를 scripts/test.sh 가 돌린다
check "TASK-6#1 scripts/test.sh 는 terraform fmt -check 를 실행" grep -q "terraform fmt -check -recursive" scripts/test.sh
check "TASK-6#1 scripts/test.sh 는 terraform test 를 실행" grep -Eq "terraform( -chdir=[^ ]+)? test" scripts/test.sh
check "TASK-6#1 scripts/test.sh 는 init 을 -backend=false 로 실행" grep -Eq "terraform( -chdir=[^ ]+)? init -backend=false" scripts/test.sh
check "TASK-6#1 hooks testGlobs 에 infra/**/*.tftest.hcl" python3 -c 'import json,sys;sys.exit(0 if "infra/**/*.tftest.hcl" in json.load(open(".claude-code-agile-hooks.json"))["testFirst"]["testGlobs"] else 1)'
check "TASK-6#1 cognito 모듈 테스트 존재" sh -c 'ls infra/main/modules/cognito/tests/*.tftest.hcl'
for p in infra/main/modules/cognito/.terraform/x infra/main/modules/cognito/.terraform.lock.hcl; do
  check "TASK-6#1 gitignore가 $p 를 무시(모듈은 lock 파일을 커밋하지 않는다)" git check-ignore -q --no-index "$p"
done
check "TASK-6#1 gitignore가 루트 스택 lock 파일은 추적" sh -c '! git check-ignore -q --no-index infra/main/envs/prod/.terraform.lock.hcl'

# TASK-11#3 로컬 첨부 저장소: docker compose 에 MinIO + 버킷 init(mc), make dev-storage, example.env 의 UPLOADS_* 값
check "TASK-11#3 docker-compose 에 minio 서비스" grep -Eq "^  minio:" docker-compose.yml
check "TASK-11#3 docker-compose 에 minio-init(버킷 생성) 서비스" grep -Eq "^  minio-init:" docker-compose.yml
check "TASK-11#3 minio-init 은 mc 로 버킷을 만든다" grep -q "mc mb" docker-compose.yml
check "TASK-11#3 MinIO CORS 는 프론트 개발 서버 origin 을 허용" grep -q "MINIO_API_CORS_ALLOW_ORIGIN" docker-compose.yml
check "TASK-11#3 docker-compose 와 테스트가 같은 MinIO 이미지" sh -c 'img=$(grep -Eo "cgr.dev/chainguard/minio@sha256:[0-9a-f]+" docker-compose.yml | sort -u); [ -n "$img" ] && grep -q "$img" backend/src/test/java/com/example/board/TestcontainersConfiguration.java'
check "TASK-11#3 make dev-storage 타깃" has_make_target dev-storage
for k in UPLOADS_BUCKET UPLOADS_REGION UPLOADS_ENDPOINT UPLOADS_PATH_STYLE UPLOADS_ACCESS_KEY UPLOADS_SECRET_KEY UPLOADS_PUBLIC_BASE_PATH; do
  check "TASK-11#3 example.env 에 $k" grep -Eq "^#? ?$k=" example.env
done
check "TASK-11#4 uploads 모듈 테스트 존재" sh -c 'ls infra/main/modules/uploads/tests/*.tftest.hcl'

# TASK-16#3 CloudFront Function(index 리라이트): /x/ → /x/index.html, 확장자 없는 경로도 index, 정적 파일은 그대로
check "TASK-16#3 CloudFront Function index 리라이트(node)" node scripts/test/cf_function_test.mjs

# TASK-16#6 Terraform 루트 스택: scripts/test.sh 가 infra/main·infra/bootstrap 을 init(-backend=false)·validate 하고,
# 루트 lock 파일은 커밋(모듈 lock 은 무시), prod 변수 파일·backend 설정, bootstrap 버킷은 prevent_destroy
check "TASK-16#6 scripts/test.sh 는 루트 스택(infra/main, infra/bootstrap)을 validate" sh -c 'grep -q "infra/main" scripts/test.sh && grep -q "infra/bootstrap" scripts/test.sh'
for d in infra/main infra/bootstrap; do
  check "TASK-16#6 $d/.terraform.lock.hcl 커밋됨" git ls-files --error-unmatch "$d/.terraform.lock.hcl"
done
for m in network backend_host frontend_cdn ci_oidc dns_cert; do
  check "TASK-16#6 모듈 $m 테스트 존재" sh -c "ls infra/main/modules/$m/tests/*.tftest.hcl"
  check "TASK-16#6 gitignore가 모듈 $m lock 파일을 무시" git check-ignore -q --no-index "infra/main/modules/$m/.terraform.lock.hcl"
done
check "TASK-16#6 루트 스택 테스트 존재" sh -c 'ls infra/main/tests/*.tftest.hcl'
check "TASK-16#6 bootstrap state 버킷 prevent_destroy" grep -q "prevent_destroy = true" infra/bootstrap/main.tf
check "TASK-16#6 main backend 는 S3 partial config" grep -q 'backend "s3"' infra/main/backend.tf
check "TASK-16#6 prod backend 설정은 use_lockfile" grep -Eq "^use_lockfile += true" infra/main/envs/prod.backend.hcl
for k in name availability_zone web_bucket_name uploads_bucket_name cognito_domain_prefix domain_name github_repository; do
  check "TASK-16#6 envs/prod.tfvars 에 $k" grep -Eq "^$k += " infra/main/envs/prod.tfvars
done
check "TASK-16#6 Terraform 이 SSM 파라미터(시크릿)를 만들지 않는다" sh -c '! grep -rq "resource \"aws_ssm_parameter\"" infra --include=*.tf'
for w in "infra/bootstrap" "envs/prod.tfvars" "terraform destroy" "CLIENT_IP_HEADER" "COOKIE_SECURE" "UPLOADS_REGION"; do
  check "TASK-16#6 README 인프라 절에 '$w'" grep -qF "$w" README.md
done

# TASK-18#2 OAuth 콘솔 체크리스트: 제공자 5종의 콜백, 운영·로컬 주소, 동의 항목(KOE205·제공 정보), SSM 키, cloudfront.net 대안
check "TASK-18#2 docs/oauth-setup.md 존재" test -f docs/oauth-setup.md
for p in google github naver kakao cognito; do
  check "TASK-18#2 oauth-setup 에 $p 콜백" grep -qF "/api/login/oauth2/code/$p" docs/oauth-setup.md
done
for w in "http://localhost:3000" "public_base_url" "KOE205" "제공 정보" "GOOGLE_CLIENT_SECRET" "GITHUB_CLIENT_ID" "NAVER_CLIENT_ID" "KAKAO_CLIENT_SECRET" "COGNITO_CLIENT_SECRET" "domain_name" "make put-secrets" "체크리스트"; do
  check "TASK-18#2 oauth-setup 에 '$w'" grep -qF -- "$w" docs/oauth-setup.md
done

# TASK-18#3 SMS: 샌드박스 해제, 한국 발신 등록, 지출 한도, SSM 키, 검증 번호 시험, 비용
check "TASK-18#3 docs/sms-setup.md 존재" test -f docs/sms-setup.md
for w in "SMS_PROVIDER" "SMS_AWS_REGION" "SMS_ORIGINATION_IDENTITY" "샌드박스" "production access" "South Korea" "Registrations" "지출 한도" "create-verified-destination-number" "sms-voice:SendTextMessage" "비용" "확인 필요"; do
  check "TASK-18#3 sms-setup 에 '$w'" grep -qF -- "$w" docs/sms-setup.md
done

# TASK-18#4 README(로컬 실행·배포·시크릿 목록·비용·destroy) + diagrams/*.dot → docs/diagrams/*.svg(센티넬 색 → CSS 변수, 라이트/다크)
for w in "## 빠른 시작" "## 로컬 실행" "## 배포 (TASK-17)" "## 시크릿·환경변수 목록" "## 비용 (us-east-1, 월, 대략)" "## 정리 (terraform destroy)" "docs/first-deploy.md" "docs/api.md"; do
  check "TASK-18#4 README 에 '$w'" grep -qF -- "$w" README.md
done
# 시크릿 목록은 example.env·secrets.example.txt 의 모든 키를 다룬다
while IFS= read -r k; do
  check "TASK-18#4 README 시크릿 목록에 $k" grep -qF "\`$k\`" README.md
done < <(grep -Eho '^#? ?[A-Z][A-Z0-9_]+=' example.env deploy/secrets.example.txt | sed -E 's/^# ?//; s/=$//' | sort -u)
check "TASK-18#4 make diagrams 타깃" has_make_target diagrams
for d in architecture auth-model deploy-pipeline; do
  check "TASK-18#4 diagrams/$d.dot 과 docs/diagrams/$d.svg" sh -c "test -f diagrams/$d.dot && git ls-files --error-unmatch docs/diagrams/$d.svg >/dev/null 2>&1 || test -f docs/diagrams/$d.svg"
  check "TASK-18#4 README 가 $d 그림을 쓴다" grep -q "<!--dot:$d-->" README.md
done
check "TASK-18#4 .dot 은 센티넬 색(#f000xx)만 쓴다" sh -c '! grep -hoE "(color|fillcolor|fontcolor|bgcolor)=\"#[0-9a-fA-F]{6}\"" diagrams/*.dot | grep -viE "\"#f000[0-9a-f]{2}\""'
check "TASK-18#4 SVG 는 라이트·다크 CSS 변수를 품는다" sh -c 'for f in docs/diagrams/*.svg; do grep -q "prefers-color-scheme: dark" "$f" && grep -q "var(--d-" "$f" || exit 1; done'
if command -v dot >/dev/null 2>&1; then
  check "TASK-18#4 커밋된 SVG·문서 블록이 .dot 과 같다(build-diagrams.py --check)" python3 scripts/build-diagrams.py --check
else
  echo "  WARNING: graphviz dot 이 없어 다이어그램 최신 여부 검사를 건너뛴다" >&2
fi

# TASK-18#5 Playwright E2E: frontend/e2e + playwright.config.ts(chromium), scripts/e2e.sh(로컬 스택 기동·정리), make e2e(make test 에는 없음)
check "TASK-18#5 make e2e 타깃은 scripts/e2e.sh 를 호출" sh -c 'make -n e2e | grep -q "scripts/e2e.sh"'
check "TASK-18#5 scripts/e2e.sh 실행 가능" test -x scripts/e2e.sh
check "TASK-18#5 make test(scripts/test.sh)는 E2E 를 돌리지 않는다" sh -c '! grep -Eq "e2e|playwright" scripts/test.sh'
check "TASK-18#5 playwright 설정: e2e 디렉터리, chromium 프로젝트 하나" sh -c 'grep -q "testDir: \"./e2e\"" frontend/playwright.config.ts && [ "$(grep -c "name: \"" frontend/playwright.config.ts)" = 1 ] && grep -q "name: \"chromium\"" frontend/playwright.config.ts'
check "TASK-18#5 @playwright/test devDependency" sh -c 'grep -q "\"@playwright/test\"" frontend/package.json'
check "TASK-18#5 vitest 는 e2e 를 집지 않는다(src 만)" grep -q 'include: \["src/' frontend/vitest.config.mts
check "TASK-18#5 E2E 시나리오(제목에 TASK-18#5) 존재" sh -c '[ "$(grep -c "test(\"TASK-18#5" frontend/e2e/smoke.spec.ts)" -ge 8 ]'
for w in "SMS_PROVIDER=console" "ADMIN_EMAIL" "next dev" "compose" "down -v" "playwright test"; do
  check "TASK-18#5 scripts/e2e.sh 에 '$w'" grep -qF -- "$w" scripts/e2e.sh
done
check "TASK-18#5 gitignore 가 E2E 결과물을 무시" git check-ignore -q --no-index frontend/e2e/artifacts/x

# TASK-19#1 SC2015(A && B || C) 수정이 반영돼 있다(구버전 shellcheck 가 CI 에서 잡은 패턴)
if command -v shellcheck >/dev/null 2>&1; then
  check "TASK-19#1 deploy_test.sh 에 SC2015 없음" shellcheck -S info -i SC2015 scripts/test/deploy_test.sh
fi
# TASK-19#2 B1 main 에 들어가는 변경은 PR CI 를 거친다: ci.yml 은 pull_request 에서 make test 를 돌린다
check "TASK-19#2 TASK-19#B1 ci.yml 은 pull_request 에서 make test 실행" sh -c 'grep -Eq "^  pull_request:" .github/workflows/ci.yml && grep -q "run: make test" .github/workflows/ci.yml'
# TASK-19#3 CI shellcheck 를 로컬과 같은 버전으로 고정(구버전 Ubuntu 패키지가 로컬에서 안 나는 경고를 낸 사고)
check "TASK-19#3 ci.yml 은 SHELLCHECK_VERSION 을 고정한다" grep -Eq '^\s*SHELLCHECK_VERSION: "?[0-9]+\.[0-9]+\.[0-9]+"?' .github/workflows/ci.yml
check "TASK-19#3 ci.yml 은 shellcheck tarball 을 sha256 으로 검증한다" sh -c 'grep -q "SHELLCHECK_SHA256" .github/workflows/ci.yml && grep -q "sha256sum -c" .github/workflows/ci.yml'
check "TASK-19#3 deploy_test.sh 는 로컬 shellcheck 버전을 ci.yml 고정값과 비교한다" grep -q "SHELLCHECK_VERSION" scripts/test/deploy_test.sh

echo "repo tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
