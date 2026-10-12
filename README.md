# 게시판 (Next.js + Spring Boot + PostgreSQL + Terraform/AWS)

로그인, 글·댓글·좋아요, 검색, 이미지 첨부, 관리자 화면을 갖춘 게시판이다. 한 AWS 계정(us-east-1)에 Terraform으로 올린다.

- **프론트**: Next.js 16 정적 export → S3 + CloudFront
- **백엔드**: Spring Boot 4 (Java 25) + PostgreSQL 18 → EC2 한 대의 Docker Compose. CloudFront가 `/api/*`를 넘긴다
- **로그인**: 이메일·비밀번호, 휴대폰 OTP(End User Messaging SMS), Google·GitHub·Naver·Kakao·Cognito. 계정 하나에 여러 수단을 연결할 수 있다
- **배포**: `make deploy`(로컬)와 GitHub Actions(main push, OIDC)가 같은 스크립트를 쓴다

전체 설계는 [`backlog/docs/doc-1-architecture-plan.md`](backlog/docs/doc-1-architecture-plan.md), 설계 결정은 [`backlog/decisions/`](backlog/decisions/)에 있다.

<!--dot:architecture-->
<p align="center"><img src="docs/diagrams/architecture.svg" alt="아키텍처 — 브라우저 → CloudFront(정적 S3·/api EC2·/uploads S3) → EC2 Docker Compose(Spring Boot + PostgreSQL), ECR·SSM·CloudWatch·Cognito·End User Messaging·외부 IdP"></p>
<!--/dot:architecture-->

## 빠른 시작

```bash
cp example.env .env                   # (선택) 로컬 env — 이미지 첨부(UPLOADS_*)·ADMIN_EMAIL 을 쓰려면 필요
make frontend-install                 # 프론트 의존성 (처음 한 번)
make dev-db dev-storage               # PostgreSQL + MinIO (docker compose)
make backend-run                      # 터미널 1: http://localhost:8080/api
make frontend-dev                     # 터미널 2: http://localhost:3000
```

<http://localhost:3000/signup/>에서 가입한다. 관리자가 필요하면 `.env`의 `ADMIN_EMAIL`을 채우고 그 이메일로 가입한 뒤 백엔드를 다시 시작한다
(가입만으로는 관리자가 되지 않고, 기동할 때 이미 있는 그 LOCAL 계정을 ADMIN으로 올린다).
휴대폰 로그인의 인증번호는 백엔드 로그(`[SMS console] ...`)에 찍힌다. 테스트는 `make test`, 브라우저 E2E는 `make e2e`로 돌린다.

| 문서 | 내용 |
|---|---|
| [docs/first-deploy.md](docs/first-deploy.md) | 첫 배포 런북: 자격 증명 → bootstrap → apply → 시크릿 → 배포 → 스모크 → GitHub Actions → 롤백 시험 → 정리 |
| [docs/oauth-setup.md](docs/oauth-setup.md) | 소셜 로그인 콘솔 설정(콜백 URL, 동의 항목)과 확인 체크리스트 |
| [docs/sms-setup.md](docs/sms-setup.md) | End User Messaging SMS: 샌드박스, 한국 발신, 지출 한도 |
| [docs/api.md](docs/api.md) | 백엔드 API 레퍼런스(인증·게시판·댓글·검색·첨부·관리자) |
| [docs/frontend.md](docs/frontend.md) | 프론트 라우트·상태·`data-testid` |
| [diagrams/](diagrams/) | 다이어그램 원본(DOT) |

## 구성

| 디렉토리 | 내용 |
|---|---|
| `backend/` | Spring Boot 4.1 · Java 25 · Gradle Kotlin DSL. 모든 API는 `/api` 아래 (`server.servlet.context-path=/api`) |
| `frontend/` | Next.js 16 · App Router · `output: 'export'` · TypeScript · Tailwind CSS v4 · shadcn/ui. E2E는 `frontend/e2e/`(Playwright) |
| `docker-compose.yml` | 로컬 PostgreSQL 18 + 첨부 저장소 MinIO(S3 호환, TASK-11) |
| `infra/` | Terraform: `infra/bootstrap`(state 버킷), `infra/main`(운영 루트 스택 + `modules/*`). [인프라](#인프라-terraform-task-16) 참고 |
| `deploy/` | EC2에서 쓰는 compose·배포 스크립트, 시크릿 입력 예시. [배포](#배포-task-17) 참고 |
| `.github/workflows/` | `ci.yml`(테스트·빌드), `deploy.yml`(main 배포) |
| `scripts/` | 로컬 `make`와 CI가 같이 호출하는 스크립트 (`scripts/test.sh`, 배포, `e2e.sh`, `build-diagrams.py`) |
| `diagrams/` → `docs/diagrams/` | DOT 원본 → 렌더된 SVG(생성물) |
| `backlog/` | backlog.md 태스크·결정·문서 |

## 준비물

- Java 25, Node.js 24, Docker (Docker Desktop 등)
- GNU Make
- (배포) aws CLI v2, Terraform ≥ 1.10, jq, docker buildx — [docs/first-deploy.md](docs/first-deploy.md#0-준비물)
- (선택) graphviz(`make diagrams`), shellcheck·actionlint(`make test`가 있으면 쓴다)

## 로컬 실행

```bash
# (선택) 환경변수 파일 — 복사하지 않아도 기본값으로 동작한다
cp example.env .env

make dev-db         # PostgreSQL 기동 (docker compose)
make dev-storage    # (선택) 첨부 저장소 MinIO 기동 + 버킷 준비 — 이미지 첨부를 쓸 때
make backend-run    # 백엔드 http://localhost:8080/api
make frontend-dev   # 프론트 http://localhost:3000
```

백엔드 상태 확인:

```bash
curl http://localhost:8080/api/actuator/health
# {"status":"UP", ...}
```

프론트 개발 서버(`next dev`)는 `/api/*`를 백엔드(`BACKEND_URL`, 기본 `http://localhost:8080`)로 프록시한다(`frontend/next.config.ts`의 `rewrites`).
브라우저는 운영(CloudFront가 `/api/*`를 EC2로 보냄)처럼 한 오리진(`http://localhost:3000`)만 보므로 `SESSION`·`XSRF-TOKEN` 쿠키,
CSRF, 소셜 로그인 리다이렉트가 그대로 동작한다. 백엔드의 `PUBLIC_BASE_URL` 기본값 `http://localhost:3000`이 이 구성을 전제로 한다.

```bash
curl -i http://localhost:3000/api/auth/csrf   # 프록시 경유: 200 + Set-Cookie: XSRF-TOKEN=...; Path=/; SameSite=Lax
```

`next build`(= `make frontend-build`)는 정적 export(`frontend/out/`)라 rewrites가 없다. 개발 서버에서만 trailing slash 리다이렉트를 꺼서
`/api/auth/csrf`가 `/api/auth/csrf/`로 바뀌지 않게 한다.

DB 스키마는 Flyway(`backend/src/main/resources/db/migration`)가 관리한다.
끝나면 `make dev-db-down`으로 DB와 MinIO를 내린다(데이터는 `pgdata`·`miniodata` 볼륨에 남는다).

로컬 첨부(MinIO): `make dev-storage`가 MinIO(API `http://localhost:9000`, 콘솔 `http://localhost:9001`, 계정 `minioadmin`/`minioadmin`)를
띄우고 one-shot 컨테이너 `minio-init`(mc)이 버킷 `board-uploads`를 만든 뒤 `uploads/` 아래를 익명 읽기로 연다.
백엔드는 `example.env`의 `UPLOADS_*` 값(복사한 `.env`)으로 MinIO를 쓴다. 이 값이 없으면(`UPLOADS_BUCKET` 비어 있음) 앱은 뜨지만
첨부 API는 503 `uploads_unavailable`이다. 로컬에서 첨부 `url`은 `UPLOADS_PUBLIC_BASE_PATH`(`http://localhost:9000/board-uploads/uploads/`)
기준이라 브라우저로 바로 열린다. CORS는 MinIO 전역 설정 `MINIO_API_CORS_ALLOW_ORIGIN`(= `PUBLIC_BASE_URL`, 기본 `http://localhost:3000`)으로
준다(MinIO는 버킷 CORS API를 구현하지 않는다). Docker Hub의 `minio/minio` 이미지는 더 이상 받을 수 없어 `cgr.dev/chainguard/minio`를 digest로 고정해 쓴다.

소셜 로그인을 로컬에서 쓰려면 제공자 콘솔에 `http://localhost:3000/api/login/oauth2/code/{id}`를 등록하고 id·secret을 `.env`에 넣는다([docs/oauth-setup.md](docs/oauth-setup.md)).

## 테스트

```bash
make test   # = scripts/test.sh : 리포 검증 + 배포 스크립트 + Terraform(fmt·validate·test) + 백엔드(./gradlew test) + 프론트(npm test)
make e2e    # Playwright E2E (느림, make test 에 없음) — 아래
make smoke  # 배포 스모크: SMOKE_BASE_URL=https://<cloudfront> make smoke (없으면 SKIP)
```

- 백엔드: JUnit 5 + Testcontainers(PostgreSQL, MinIO — 컨테이너는 각각 하나를 모든 통합 테스트가 같이 쓴다). **Docker가 떠 있어야 한다.**
- 프론트: Vitest + Testing Library(`frontend/src/**/*.test.ts(x)`).
- 리포: `scripts/test/repo_test.sh` (Makefile 타깃, hooks 설정, gitignore, 문서, 다이어그램이 원본과 같은지 등 골격 검증)
- 배포: `scripts/test/deploy_test.sh` (DRY_RUN·가짜 aws/docker로 배포 스크립트, 워크플로 actionlint, shellcheck, 가짜 HTTP 서버로 `smoke_test.sh` 검증)
- Terraform: `terraform fmt -check -recursive infra` + 루트 스택(`infra/bootstrap`, `infra/main`)과 `tests/*.tftest.hcl`이 있는 모듈마다 `terraform init -backend=false`·`validate`·`test`.
  테스트는 `mock_provider "aws"`라 AWS 자격 증명이 필요 없다(provider는 처음 한 번 내려받아 `~/.terraform.d/plugin-cache`에 둔다).
  `terraform`이 없으면 경고만 내고 건너뛴다. 재사용 모듈의 `.terraform.lock.hcl`은 커밋하지 않고, 루트 스택(`infra/bootstrap`, `infra/main`)의 lock 파일은 커밋한다.
- CloudFront Function: `scripts/test/cf_function_test.mjs`(Node 로 index 리라이트 함수를 직접 실행, `repo_test.sh`가 호출).

개별 실행: `make backend-test`, `make frontend-test`, 정적 export 빌드는 `make frontend-build`(결과물 `frontend/out/`).
전체 타깃 목록은 `make help`.

### E2E (Playwright, TASK-18)

`make e2e`(= `scripts/e2e.sh`)가 실제 로컬 스택을 띄우고 시나리오를 돌린 뒤 정리한다. 처음 한 번 `cd frontend && npx playwright install chromium`이 필요하다.

1. PostgreSQL + MinIO: compose 별도 프로젝트 `board-e2e`(포트 55432·59000, `frontend/e2e/docker-compose.e2e.yml`)로 띄워 개발용 컨테이너와 겹치지 않고, 매번 빈 DB로 시작한다.
2. 백엔드: `bootJar` → `java -jar`(:18080, `SMS_PROVIDER=console`, `ADMIN_EMAIL=admin-e2e@example.com`). 로그는 `frontend/e2e/artifacts/backend.log`이고,
   휴대폰 OTP 시나리오가 이 로그에서 인증번호를 읽는다(테스트 전용 API 없음). 관리자는 운영 절차와 같이 `ADMIN_EMAIL`로 API 가입 → 백엔드 재시작으로
   올려 두고, 시나리오는 그 계정으로 로그인한다.
3. 프론트: `next dev`(:3100, `/api` → :18080). **같은 `frontend/`의 `next dev`는 하나만 뜬다** — `make frontend-dev`를 먼저 끈다.
4. `npx playwright test`(chromium) → 결과·스크린샷·trace는 `frontend/e2e/artifacts/`(gitignore). 끝나면 프로세스를 끄고 `compose down -v`.

시나리오(`frontend/e2e/smoke.spec.ts`, 직렬): A 가입·로그아웃·로그인 → 글쓰기(카테고리·태그·이미지 업로드) → 목록·태그·검색 → 좋아요·댓글 →
B 가입, A의 글에 수정·삭제 버튼 없음 + API PATCH 403 → 관리자 정지 → B의 다음 쓰기 실패·로그인 403 → 휴대폰 OTP 로그인 → 삭제 확인 창(취소·확인).
인자는 그대로 넘긴다: `make e2e E2E_ARGS="--headed"`, `./scripts/e2e.sh -g OTP`. `E2E_KEEP=1`이면 끝나도 스택을 남긴다.

### 다이어그램

DOT 원본은 `diagrams/*.dot`이고, `make diagrams`(= `scripts/build-diagrams.py`)가 `docs/diagrams/*.svg`로 렌더한 뒤 문서의 `<!--dot:이름-->` 블록을 갱신한다.

- 색은 `.dot`에 센티넬 hex로 쓰고, 빌드가 CSS 변수로 바꾼다. SVG 안의 `<style>`이 라이트 값과 `@media (prefers-color-scheme: dark)` 다크 값을 정의하므로, SVG 파일 하나가 보는 쪽 테마를 따른다.
- GitHub는 Markdown 안의 인라인 `<svg>`를 지우고 페이지 CSS를 SVG에 넘기지 않는다. 그래서 변수 정의를 SVG 안에 넣고 `<img>`로 참조한다. `<img>`의 SVG는 OS·브라우저의 색 테마를 따른다.
  GitHub 테마만 따로 바꾼 경우에도 읽히도록 배경을 칠해 둔다.
- `make test`가 `build-diagrams.py --check`로 커밋된 SVG가 원본과 같은지 확인한다(`dot`이 없으면 건너뛴다. 글꼴 측정이 환경마다 달라 CI에서는 검사하지 않는다).
- 시퀀스 다이어그램(OAuth 로그인 흐름)은 DOT으로 표현할 수 없어 [docs/api.md](docs/api.md)에 mermaid로 썼다.

## 인증·인가 요약

<!--dot:auth-model-->
<p align="center"><img src="docs/diagrams/auth-model.svg" alt="인증 모델 — users 1:N user_identities(provider, subject), local_credentials·refresh_tokens, 요청은 Bearer JWT 와 SESSION 쿠키(+CSRF) 중 하나로 인증된 뒤 공통 인가 필터를 지난다"></p>
<!--/dot:auth-model-->

- 계정 `users` 1 : N `user_identities(provider, subject)`. 비밀번호는 `local_credentials`에만 있다. 이메일이 같아도 자동으로 합치지 않고, 로그인한 뒤 "연결"한다.
- 요청 하나에 보안 체인 하나: `Authorization: Bearer <JWT>`가 있으면 JWT, 없으면 `SESSION` 쿠키(+ 쓰기는 `X-XSRF-TOKEN`). 정지 계정·제한 세션의 쓰기는 공통 필터가 403으로 막는다.
- 상세 규칙(엔드포인트, 오류 코드, 휴대폰 OTP 한도, 재활용 번호 보호)은 [docs/api.md](docs/api.md#인증인가-모델-백엔드)에 있다.

## 인프라 (Terraform, TASK-16)

> 첫 apply 는 [docs/first-deploy.md](docs/first-deploy.md) 순서로 한다(유저 확인 후). 로컬 검증은 `make test`(fmt·validate·mock_provider 테스트)다.

### 구성

```
infra/bootstrap/          state S3 버킷(버전·SSE·퍼블릭 차단·HTTPS 강제·prevent_destroy). 로컬 state 로 1회 apply
infra/main/               운영 루트 스택 (backend.tf: S3 partial config + use_lockfile, providers.tf: aws + aws.us_east_1)
  envs/prod.backend.hcl   backend 설정(bootstrap 출력 backend_config)
  envs/prod.tfvars        prod 변수
  modules/network         VPC 10.20.0.0/16, public subnet 1개(AZ 변수), IGW, 기본 경로
  modules/backend_host    EC2 t4g.small(AL2023 arm64, SSM 파라미터 AMI, IMDSv2, hop 2, 루트 암호화), EIP,
                          데이터 EBS gp3 20GB(/dev/sdf → /srv/pgdata, 비어 있을 때만 포맷, fstab UUID+nofail, final_snapshot),
                          DLM 일일 스냅샷 7개, SG(8080 ← CloudFront origin-facing prefix list 만, 22 없음),
                          인스턴스 role(SSM Core, ECR pull, Logs, SSM /board/prod/* 읽기 + KMS(SSM 경유), sms-voice:SendTextMessage, uploads 정책),
                          로그 그룹 /board/prod(14일), user_data(docker + compose plugin, awslogs 기본 로그 드라이버, /opt/board)
  modules/frontend_cdn    ECR(scan on push, 최근 20개), web S3 + OAC, CloudFront(아래)
  modules/uploads         첨부 버킷(TASK-11). CloudFront /uploads/* OAC 정책은 배포 ARN 으로 여기서 붙는다
  modules/cognito         User Pool + Hosted UI(TASK-6). 콜백은 PUBLIC_BASE_URL 에서
  modules/ci_oidc         GitHub OIDC provider + main 브랜치 전용 배포 role (github_repository 를 주면 켜짐)
  modules/dns_cert        Route53 zone(조회/생성) + ACM(us-east-1, DNS 검증) + alias A/AAAA (domain_name 을 주면 켜짐)
```

CloudFront 동작:

| 경로 | 오리진 | 캐시 | 그 밖 |
|---|---|---|---|
| 기본(`/*`) | web S3(OAC) | Managed-CachingOptimized | viewer-request CF Function: `/x/` → `/x/index.html`, 확장자 없는 `/x` → `/x/index.html` |
| `/api/*` | EC2 EIP public DNS, HTTP 8080 | Managed-CachingDisabled | 모든 메서드, HTTPS 전용, 함수 없음(트레일링 슬래시 리라이트 없음). origin request policy = 모든 viewer 헤더·쿠키·쿼리 + `CloudFront-Viewer-Address` |
| `/uploads/*` | uploads S3(OAC) | Managed-CachingOptimized | GET/HEAD |

- `/api/*` 헤더: `Authorization`은 cache key 나 "모든 viewer 헤더" origin request policy 로만 전달된다. CachingDisabled 는 키를 둘 수 없으므로
  `allViewerAndWhitelistCloudFront` + `CloudFront-Viewer-Address`(앱의 `CLIENT_IP_HEADER`) 커스텀 정책을 쓴다. 이 정책은 viewer `Host`도 넘기지만
  오리진이 가상 호스트 없는 Tomcat 이라 문제없다(OAuth redirect-uri 는 `PUBLIC_BASE_URL`로 고정).
- `custom_error_response`(404 페이지)는 두지 않는다. 배포 전체에 걸려 `/api/*`의 404·403 JSON 을 HTML 로 덮기 때문이다. 없는 정적 경로는 S3 404.
- 가격 등급 PriceClass_100, 인증서는 `*.cloudfront.net` 기본(decision-9). `domain_name`을 주면 aliases + ACM.

### 처음 적용 순서

bootstrap(state 버킷, 계정당 1회) → `envs/prod.backend.hcl` → `envs/prod.tfvars`의 `CHANGE-ME` 채우기 → `terraform -chdir=infra/main init -backend-config=envs/prod.backend.hcl`
→ `plan -var-file=envs/prod.tfvars -out=prod.tfplan` → `apply prod.tfplan`. 단계별 명령과 확인 항목은 [docs/first-deploy.md](docs/first-deploy.md) 1–4단계에 있다.

`envs/prod.tfvars` 항목: `region`(us-east-1), `name`, `availability_zone`, `instance_type`, `data_volume_size`, `web_bucket_name`·`uploads_bucket_name`(전역 고유),
`ecr_repository_name`, `ssm_parameter_path`(`/board/prod`), `log_group_name`, `cognito_domain_prefix`(전역 고유), `cognito_deletion_protection`,
`cognito_extra_callback_urls`/`cognito_extra_logout_urls`(로컬 Cognito 로그인용, 선택), `domain_name`·`subject_alternative_names`·`create_hosted_zone`(비우면 cloudfront.net),
`github_repository`(`owner/repo`, 비우면 CI role 없음)·`github_branch`, `existing_github_oidc_provider_arn`(계정에 이미 있으면).

### apply 뒤 앱 설정

`terraform output app_env`가 비밀이 아닌 값을 준다. TASK-17 `put-secrets.sh`가 시크릿과 함께 SSM `/board/prod/*`에 넣는다.

| env | 값 |
|---|---|
| `PUBLIC_BASE_URL` | `https://<cloudfront_domain 또는 domain_name>` (출력 `public_base_url`) |
| `COOKIE_SECURE` | `true` |
| `CLIENT_IP_HEADER` | `CloudFront-Viewer-Address` |
| `UPLOADS_BUCKET` / `UPLOADS_REGION` | 출력 `uploads_bucket` / `us-east-1` (앱 기본값 `ap-northeast-2`라 반드시 명시) |
| `UPLOADS_PUBLIC_BASE_PATH` | `/uploads/` (CloudFront 같은 오리진). `UPLOADS_ENDPOINT`·`UPLOADS_PATH_STYLE`·`UPLOADS_ACCESS_KEY`·`UPLOADS_SECRET_KEY`는 비움(인스턴스 프로파일) |
| `COGNITO_ISSUER_URI` / `COGNITO_CLIENT_ID` / `COGNITO_DOMAIN` | 출력 `cognito` |
| `COGNITO_CLIENT_SECRET` | `terraform output -raw cognito_client_secret` → SSM SecureString (state 에는 남는다) |
| `SMS_PROVIDER` / `SMS_AWS_REGION` | `aws` / `us-east-1` |

Terraform 은 SSM 파라미터(시크릿)를 만들지 않는다 — 값이 state 에 남지 않게 한다. 인스턴스 role 은 `/board/prod/*`만 읽는다.
GitHub Actions 는 `ci_role_arn`을 assume 하고, 배포 대상 값(ECR URL·web 버킷·배포 ID·인스턴스 ID)은 리포 variables 로 넣는다(CI 는 state 를 읽지 않는다).
EC2 의 PostgreSQL 데이터는 `/srv/pgdata`(데이터 EBS)이고 deploy compose 가 컨테이너의 `/var/lib/postgresql`에 바인드한다. 배포 디렉터리는 `/opt/board`.
컨테이너 로그는 docker 기본 로그 드라이버 `awslogs`(그룹 `/board/prod`)로 CloudWatch 에 간다.

비용은 [비용](#비용-us-east-1-월-대략), 삭제 순서는 [정리](#정리-terraform-destroy)를 본다.

## 배포 (TASK-17)

<!--dot:deploy-pipeline-->
<p align="center"><img src="docs/diagrams/deploy-pipeline.svg" alt="배포 파이프라인 — 로컬 make deploy 와 GitHub Actions deploy.yml 이 같은 스크립트로 백엔드 이미지를 ECR 에 올리고 SSM send-command 로 EC2 에 배포(헬스 실패 시 롤백), 프론트는 S3 sync 후 CloudFront invalidation"></p>
<!--/dot:deploy-pipeline-->

> 스크립트·워크플로는 `make test`에서 DRY_RUN·가짜 aws/docker로 검증한다. 실제 첫 배포는 [docs/first-deploy.md](docs/first-deploy.md)를 따른다.

로컬(`make deploy`)과 GitHub Actions(`deploy.yml`)가 같은 스크립트를 쓴다. 다른 점은 대상 값의 출처뿐이다.
로컬은 `scripts/tf-outputs.sh`가 `terraform output`에서 읽고, CI 는 리포 variables 에서 읽는다.
모든 스크립트는 `DRY_RUN=1`이면 aws·docker·npm 명령을 `[dry-run] ...`로 출력만 한다. 예: `make deploy DRY_RUN=1`

```
backend/Dockerfile            JDK 25 빌드 스테이지(bootJar → jarmode=tools 레이어) → eclipse-temurin:25-jre, uid 10001, -XX:MaxRAMPercentage=60
scripts/build-backend.sh      ECR 로그인 + buildx linux/arm64 --push  (태그: git short sha 12자리 + latest)
scripts/deploy-backend.sh     SSM send-command(AWS-RunShellScript) → EC2 에서 remote-deploy.sh, get-command-invocation 폴링
scripts/deploy-frontend.sh    npm ci·build → S3 sync 2회(캐시 헤더) → CloudFront invalidation /*
scripts/put-secrets.sh        입력 파일 + terraform app_env·cognito secret → SSM /board/prod/*
scripts/tf-outputs.sh         terraform output → AWS_REGION, ECR_REPOSITORY_URL, WEB_BUCKET, DISTRIBUTION_ID, INSTANCE_ID, ...
deploy/docker-compose.prod.yml  EC2 compose: api(:8080) + postgres:18-alpine(/srv/pgdata), awslogs, healthcheck, restart
deploy/remote-deploy.sh       (EC2) render-env → ECR 로그인 → pull·up → 헬스 대기 → 실패 시 롤백
deploy/render-env.sh          (EC2) SSM /board/prod/* → /opt/board/app.env, db.env (600)
deploy/secrets.example.txt    put-secrets 입력 예시 (실제 입력 deploy/secrets.local.txt 는 gitignore)
.github/workflows/ci.yml      PR·main 이 아닌 브랜치: make test + bootJar + 프론트 build
.github/workflows/deploy.yml  main push·수동: ci.yml → OIDC → build-backend → deploy-backend → deploy-frontend
```

### 처음 배포 순서

```bash
make tf-outputs                                             # 0) apply 가 끝났는지 확인
cp deploy/secrets.example.txt deploy/secrets.local.txt      # 1) ADMIN_EMAIL, OAuth id·secret, SMS_* 를 채운다
make put-secrets PUT_SECRETS_FLAGS=--generate-missing DRY_RUN=1   # 2) 무엇을 넣을지 확인(시크릿은 '****')
make put-secrets PUT_SECRETS_FLAGS=--generate-missing       #    DB_PASSWORD·JWT_SECRET·OTP_HASH_KEY 는 SSM 에 없을 때만 만든다
make deploy DRY_RUN=1 && make deploy                        # 3) 커밋된 HEAD 기준(커밋 안 된 변경은 build-backend 가 거부)
SMOKE_BASE_URL="$(terraform -chdir=infra/main output -raw public_base_url)" make smoke   # 4) health·캐시 헤더·CSRF·/api/posts
```

전체 순서(SSM 등록 확인, GitHub Actions 연결, 롤백 시험 포함)는 [docs/first-deploy.md](docs/first-deploy.md)에 있다.

로컬 배포에 필요한 것: aws CLI v2(관리자 자격 증명), docker buildx(Apple Silicon 은 arm64 네이티브, x86 은 QEMU), jq, terraform, Node 24.

### 백엔드 배포 흐름

1. `build-backend.sh`: `<ECR>:<sha>`·`:latest`를 linux/arm64 로 빌드해 push 한다(`--provenance=false`: attestation 이 ECR 수명 주기 20개를 차지하지 않게).
2. `deploy-backend.sh`: ECR 에 `<sha>`가 있는지 확인한다. compose 파일, `render-env.sh`, `remote-deploy.sh`를 base64 로 명령에 실어 send-command 를 보낸다.
   S3 를 거치지 않아 CI role 에 추가 권한이 필요 없다. 그 뒤 `get-command-invocation`을 끝날 때까지 폴링하고(기본 900초), 원격 stdout·stderr 를 출력한다. 실패면 종료 코드가 0 이 아니다.
3. EC2(`/opt/board`)의 `remote-deploy.sh`:
   SSM → `app.env`·`db.env` 렌더 → 인스턴스 role 로 ECR 로그인 → `IMAGE_TAG=<sha> docker compose pull && up -d` →
   `http://127.0.0.1:8080/api/actuator/health` 대기(240초).
   성공하면 `/opt/board/current_tag`에 태그를 쓰고 1주일 넘은 이미지를 정리한다. 실패하면 api 로그 100줄을 보이고 `current_tag`(직전 성공 태그)로 되돌린 뒤 실패로 끝난다.
4. postgres 데이터: postgres 18 이미지의 `PGDATA`는 `/var/lib/postgresql/18/docker`다. 데이터 EBS `/srv/pgdata`를 부모 `/var/lib/postgresql`에 바인드한다.
   메모리 한도는 api 1200m(힙 60% ≈ 720m), db 512m 다(t4g.small 2GB).

### 시크릿 흐름 (로컬 → SSM → EC2 env 파일)

- `put-secrets.sh <파일>`의 값 출처는 다음 순서로 덮어쓴다. ① `terraform output app_env`(비밀 아님) ② `cognito_client_secret` ③ 입력 파일.
  빈 값은 넣지 않는다(앱 기본값). 이미 넣은 값을 지우려면 `aws ssm delete-parameter --name /board/prod/<KEY>`를 쓴다.
- 이름에 SECRET·PASSWORD·KEY·TOKEN 이 들어간 키는 `SecureString`(aws/ssm KMS), 나머지는 `String`이다. 모두 `--overwrite`한다.
  값은 명령줄이 아니라 `--cli-input-json` 임시 파일(600)로 넘기고, 출력(DRY_RUN 포함)에는 `****`만 남긴다.
- EC2 의 `render-env.sh`는 배포마다 `get-parameters-by-path --recursive --with-decryption`으로 `/opt/board/app.env`(api)와 `db.env`(POSTGRES_* 만)를 600 으로 다시 쓴다.
  값은 compose env_file 큰따옴표 형식으로 쓴다(`\ " $` 줄바꿈 이스케이프, 보간 없음). `DB_URL`·`DB_USERNAME`이 없으면 `jdbc:postgresql://db:5432/board`·`board`를 쓴다.
  필수 키(DB_PASSWORD, JWT_SECRET, OTP_HASH_KEY)가 없으면 배포가 실패한다. 개발용 기본값으로 운영이 뜨지 않게 하려는 것이다.
- 시크릿을 바꾼 뒤에는 `make deploy-backend`로 반영한다(같은 코드면 이미지 태그도 같다).

### GitHub Actions 설정

1. `envs/prod.tfvars`의 `github_repository = "owner/repo"`로 apply → `terraform output ci_role_arn`.
2. 리포 Settings → Secrets and variables → Actions → **Variables**(시크릿 아님, 전부 terraform output):

| variable | 값 |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `ci_role_arn` |
| `AWS_REGION` | `region` (us-east-1) |
| `ECR_REPOSITORY_URL` | `ecr_repository_url` |
| `WEB_BUCKET` | `web_bucket` |
| `DISTRIBUTION_ID` | `distribution_id` |
| `INSTANCE_ID` | `instance_id` |

- OIDC: role trust 는 `repo:<owner/repo>:ref:refs/heads/main`과 정확히 일치해야 한다. GitHub **environment**를 job 에 붙이면 OIDC `sub`가
  `repo:<owner/repo>:environment:<name>`으로 바뀌어 assume 이 실패한다. 그래서 environment 를 쓰지 않는다(승인 게이트가 필요하면 ci_oidc trust 도 함께 바꾼다).
  `deploy` job 은 `github.ref == refs/heads/main`일 때만 돈다.
- `deploy.yml`은 `ci.yml`(workflow_call)이 통과해야 배포하고, `concurrency: deploy-prod`(진행 중 취소 안 함)로 한 번에 하나만 돈다.
  러너는 `ubuntu-24.04-arm`이다(arm64 이미지를 에뮬레이션 없이 빌드). 비공개 리포라면 플랜에서 arm64 호스티드 러너를 쓸 수 있는지 확인한다.
- CI role 권한: ECR push, web 버킷 sync, invalidation, 이 인스턴스에 `AWS-RunShellScript` send-command, 명령 결과 조회. SSM 파라미터·state 는 읽지 않는다.
  그래서 `put-secrets`는 로컬에서만 한다.
- 액션은 메이저 버전 태그(`@v7` 등)로 고정했다. 공급망을 더 조이려면 커밋 SHA 고정 + Dependabot(github-actions)으로 바꾼다.
- `ci.yml`은 actionlint 를 설치하고 `REQUIRE_LINTERS=1`로 `make test`를 돌린다. 로컬은 shellcheck·actionlint 가 없으면 그 검사만 건너뛴다.

### 롤백

- 자동: 헬스체크가 실패하면 `remote-deploy.sh`가 `/opt/board/current_tag`(직전 성공 태그)로 되돌리고 배포를 실패 처리한다.
- 수동(특정 태그로): 빌드 없이 배포만 한다. 태그는 ECR 에 남아 있어야 한다(최근 20개).
  ```bash
  IMAGE_TAG=<이전 sha 12자리> ./scripts/tf-outputs.sh -- ./scripts/deploy-backend.sh
  ```
  `make deploy-backend`는 현재 코드로 다시 빌드하므로 롤백에 쓰지 않는다.
- 주의: Flyway 마이그레이션은 되돌리지 않는다. 새 버전이 스키마를 바꿨다면 이전 이미지가 그 스키마에서 뜨는지 먼저 확인한다(마이그레이션은 하위 호환으로 쓴다).
- 프론트: 이전 커밋을 체크아웃해 `make deploy-frontend`.

### 로그

- 컨테이너 로그: CloudWatch Logs 그룹 `/board/prod`(14일). 스트림 이름은 `api/<컨테이너 이름>`, `db/<컨테이너 이름>`이다(compose `tag`).
  ```bash
  aws logs tail /board/prod --follow --log-stream-name-prefix api/
  ```
- 배포 명령 출력: `deploy-backend.sh`가 원격 stdout·stderr 를 그대로 출력한다(SSM 은 24,000자까지 보관). 지난 명령은 `aws ssm list-command-invocations --details`로 본다.
- 호스트 접속(SSH 없음): `aws ssm start-session --target <instance_id>` → `sudo -i; cd /opt/board; cat current_tag; docker compose -f docker-compose.prod.yml ps`.

### 문제 해결

| 증상 | 확인 |
|---|---|
| `send-command`가 `InvalidInstanceId` | 첫 부팅 직후 SSM agent 등록 전이다. `aws ssm describe-instance-information`에 나올 때까지 기다린다 |
| `render-env: 필수 파라미터가 없다` | `make put-secrets PUT_SECRETS_FLAGS=--generate-missing` |
| DB 접속 실패(password authentication failed) | `DB_PASSWORD`는 `/srv/pgdata`를 처음 초기화할 때만 쓰인다. SSM 값을 원래대로 돌리거나 `docker compose exec db psql -U board -c "ALTER USER board PASSWORD '...'"` 후 재배포 |
| 헬스 대기 시간 초과 | 출력된 api 로그 → CloudWatch `api/` 스트림. 시작이 느리면 `HEALTH_TIMEOUT`(remote-deploy.sh, 기본 240) |
| `ECR 에 이미지가 없다` | `build-backend.sh`와 태그가 다르다(커밋 안 된 변경, 다른 HEAD). `IMAGE_TAG`를 맞춘다 |
| CI 의 `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `github_repository`·브랜치·environment 미사용, `AWS_DEPLOY_ROLE_ARN` 확인 |
| `build-backend`가 커밋 안 된 변경을 거부 | 커밋하거나 `ALLOW_DIRTY=1`(태그가 내용과 달라진다) |

## 시크릿·환경변수 목록

앱은 모든 설정을 환경변수로 받는다. 로컬은 repo 루트의 `.env`(`example.env`를 복사, git에 올리지 않음)이고, 운영은 SSM Parameter Store `/board/prod/<KEY>`에 둔다.
운영 값은 `make put-secrets`가 넣고, EC2의 `render-env.sh`가 배포할 때마다 env 파일로 만든다([시크릿 흐름](#시크릿-흐름-로컬--ssm--ec2-env-파일)).

- **시크릿**: 이름에 SECRET·PASSWORD·KEY·TOKEN이 들어간 키는 SSM `SecureString`이다. 출력에는 `****`만 남긴다.
- **운영 필수**: 없으면 배포가 실패하거나(●) 그 기능이 꺼진다(○). 나머지는 앱 기본값을 쓴다.
- **운영 출처**: `terraform`은 `terraform output app_env`/`cognito_client_secret`이고 put-secrets가 자동으로 넣는다. `생성`은 `--generate-missing`이 무작위로 만든다. `입력`은 `deploy/secrets.local.txt`에 적는다.

| 키 | 시크릿 | 운영 필수 | 운영 출처 | 기본값 · 설명 |
|---|:-:|:-:|---|---|
| `DB_PASSWORD` | ● | ● | 생성 | 로컬 `board`. 운영은 postgres 데이터 디렉터리를 처음 만들 때만 쓰인다(그 뒤에 바꾸면 접속이 깨진다) |
| `DB_URL` / `DB_USERNAME` | | | (render-env 기본) | 로컬 `jdbc:postgresql://localhost:5432/board` / `board`. 운영은 `jdbc:postgresql://db:5432/board` / `board` |
| `JWT_SECRET` | ● | ● | 생성 | 자체 서명 JWT HS256 키, 32바이트 이상. 로컬은 개발용 기본값(운영 금지). 빈 값이면 기동 실패 |
| `OTP_HASH_KEY` | ● | ● | 생성 | OTP 코드 해시(HMAC-SHA256) 키, 32바이트 이상. 로컬은 개발용 기본값(운영 금지) |
| `JWT_REFRESH_TTL` | | | 입력(선택) | `P14D`. refresh 토큰 수명(ISO-8601). access 토큰은 15분 고정 |
| `COOKIE_SECURE` | | ● | terraform(`true`) | `false`. `SESSION`·`XSRF-TOKEN`·`VID` 쿠키 Secure 플래그 |
| `PUBLIC_BASE_URL` | | ● | terraform | `http://localhost:3000`. 브라우저가 보는 공개 주소(끝 `/` 없이). OAuth redirect-uri와 로그인 후 리다이렉트를 이 값으로 만든다 |
| `CLIENT_IP_HEADER` | | ● | terraform(`CloudFront-Viewer-Address`) | 비어 있음 = remote address. OTP 발송 한도의 클라이언트 IP |
| `ADMIN_EMAIL` | | ○ | 입력 | 비어 있음. 기동할 때 이 이메일(대소문자 무시)의 **LOCAL** 계정이 이미 있으면 ADMIN으로 올린다. 가입만으로는 ADMIN이 되지 않는다 — 가입 → 소유 확인 → 재시작([first-deploy 7](docs/first-deploy.md#7-스모크-ac1)) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 시크릿만 | ○ | 입력 | 비우면 Google 로그인 꺼짐. [oauth-setup](docs/oauth-setup.md#google) |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | 시크릿만 | ○ | 입력 | 비우면 꺼짐. 운영·로컬 OAuth App을 따로 만든다 |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 시크릿만 | ○ | 입력 | Client ID / Client Secret. 비우면 꺼짐 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 시크릿만 | ○ | 입력 | REST API 키 / 보안 → Client Secret. 비우면 꺼짐 |
| `COGNITO_CLIENT_ID` | | ○ | terraform | Cognito 로그인. 있으면 `COGNITO_ISSUER_URI`·`COGNITO_DOMAIN`도 필요(없으면 기동 실패) |
| `COGNITO_CLIENT_SECRET` | ● | ○ | terraform(`cognito_client_secret`) | App Client secret(state에는 남는다) |
| `COGNITO_ISSUER_URI` / `COGNITO_DOMAIN` | | ○ | terraform | `https://cognito-idp.<region>.amazonaws.com/<pool id>` / `https://<prefix>.auth.<region>.amazoncognito.com` |
| `SMS_PROVIDER` | | ● | 입력(`aws`) | `console`(로그로만, 로컬 전용) · `memory`(테스트) · `aws`. 그 밖의 값이면 기동 실패. [sms-setup](docs/sms-setup.md) |
| `SMS_AWS_REGION` | | | terraform(`us-east-1`) | `us-east-1` |
| `SMS_ORIGINATION_IDENTITY` | | | 입력 | 발신 번호·sender ID·pool. 비우면 계정 기본 |
| `SMS_CONFIGURATION_SET` | | | 입력(선택) | 전송 이벤트 기록 |
| `SMS_DEFAULT_REGION` | | | 입력(선택) | `KR`. `+` 없이 들어온 번호의 지역 |
| `OTP_PHONE_DAILY_LIMIT` / `OTP_IP_HOURLY_LIMIT` | | | 입력(선택) | `10` / `20`. 같은 번호 24시간 N회, 같은 IP 1시간 M회(같은 번호 60초 1회는 고정) |
| `UPLOADS_BUCKET` | | ○ | terraform | 비어 있음 = 첨부 API 503 `uploads_unavailable`(앱은 뜬다) |
| `UPLOADS_REGION` | | ● | terraform(`us-east-1`) | 앱 기본값이 `ap-northeast-2`라 운영은 반드시 명시. 로컬 MinIO는 `us-east-1` |
| `UPLOADS_PUBLIC_BASE_PATH` | | | terraform(`/uploads/`) | 첨부 공개 URL 앞부분(url = 이 값 + 키에서 `uploads/` 뒤, 운영은 CloudFront 같은 오리진). 로컬은 `http://localhost:9000/board-uploads/uploads/` |
| `UPLOADS_ENDPOINT` / `UPLOADS_PATH_STYLE` | | | (운영은 비움) | 로컬 MinIO `http://localhost:9000` / `true`. 운영 S3는 비움 / `false` |
| `UPLOADS_ACCESS_KEY` / `UPLOADS_SECRET_KEY` | 시크릿만 | | (운영은 비움) | 로컬 MinIO 정적 자격 증명. 운영은 비워서 EC2 인스턴스 프로파일을 쓴다 |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_PORT` | | | (로컬 전용) | `board` / `board` / `board` / `5432`. 로컬 PostgreSQL 컨테이너. 운영 db 컨테이너 값은 render-env가 `DB_*`에서 만든다 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / `MINIO_PORT` / `MINIO_CONSOLE_PORT` | | | (로컬 전용) | `minioadmin` / `minioadmin` / `9000` / `9001` |
| `BACKEND_URL` | | | (로컬 전용) | `http://localhost:8080`. `next dev`가 `/api`를 넘길 주소 |

배포 도구가 쓰는 값(앱 env 아님):

| 이름 | 어디 | 내용 |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `ECR_REPOSITORY_URL`, `WEB_BUCKET`, `DISTRIBUTION_ID`, `INSTANCE_ID` | GitHub 리포 **Variables**(시크릿 아님) | terraform output. 로컬은 `scripts/tf-outputs.sh`가 같은 이름으로 채운다 |
| `DRY_RUN`, `IMAGE_TAG`, `SKIP_BUILD`, `ALLOW_DIRTY`, `HEALTH_TIMEOUT` | 배포 스크립트 | [배포](#배포-task-17) |
| `SMOKE_BASE_URL` | `make smoke` | 스모크 대상(운영 `public_base_url`) |
| `E2E_ARGS`, `E2E_KEEP`, `E2E_*_PORT` | `make e2e` | [E2E](#e2e-playwright-task-18) |

## 비용 (us-east-1, 월, 대략)

가정: 온디맨드 가격(2026 기준 공개 요금, 세금 제외), 트래픽은 작은 실습 규모(CloudFront 월 수 GB, 요청 수십만 건), 프리 티어 크레딧 제외, 한 달 = 730시간.

| 항목 | 산식 | 월 |
|---|---|--:|
| EC2 t4g.small | $0.0168/h × 730 | ≈ $12.3 |
| EBS gp3 루트 20GB + 데이터 20GB | 40GB × $0.08 | ≈ $3.2 |
| EBS 스냅샷(DLM 7개, 증분) + 최종 스냅샷 | 변경분 수 GB × $0.05 | ≈ $0.3–1 |
| 퍼블릭 IPv4(EIP) | $0.005/h × 730 | ≈ $3.7 |
| CloudFront | 상시 무료 한도(월 1TB·1천만 요청) 안 | ≈ $0 |
| S3(web·uploads·state) | 수 GB + 요청 | < $0.5 |
| ECR | 이미지 20개 × 약 0.15GB × $0.10/GB | ≈ $0.3 |
| CloudWatch Logs | 수집 $0.50/GB, 14일 보관 | < $1 |
| SSM Parameter Store(Standard), KMS `aws/ssm` | 무료(표준 파라미터·관리형 키) | $0 |
| Cognito | 소규모 MAU는 무료 구간 **(요금제 확인 필요)** | ≈ $0 |
| **합계(기본)** | | **≈ $20–21** |
| (선택) Route 53 hosted zone + 도메인 | $0.50/zone + 도메인 연 $10–15 | ≈ $0.5 + 도메인 |
| (선택) SMS | 건당(한국행 단가) + 발신 번호 월 요금 — [sms-setup](docs/sms-setup.md#5-비용-메모) | 사용량 |

- 가장 큰 항목은 EC2와 IPv4다. 쓰지 않을 때 비용을 줄이려면 인스턴스를 멈춘다(EBS와 EIP 요금은 계속 나간다). 완전히 없애려면 아래 정리 순서를 따른다.
- 단가는 바뀐다. 적용 전에 [AWS Pricing Calculator](https://calculator.aws/)로 다시 계산하고, **AWS Budgets** 월 예산 알림을 건다.

## 정리 (terraform destroy)

순서와 명령은 [docs/first-deploy.md 12단계](docs/first-deploy.md#12-정리-destroy-순서)에 있다. 요약:

1. `cognito_deletion_protection = false`(필요하면 `uploads_force_destroy = true`)로 apply.
2. web 버킷을 비우고, ECR 이미지를 지운다.
3. `terraform -chdir=infra/main destroy -var-file=envs/prod.tfvars`. 데이터 EBS는 최종 스냅샷을 남긴다.
4. Terraform 밖의 것을 지운다: SSM `/board/prod/*`, 스냅샷, SMS 발신 번호·등록, GitHub Variables, 제공자 콘솔의 콜백.
5. state 버킷(bootstrap)은 `prevent_destroy`라 남는다. 지우려면 `infra/bootstrap/README.md`를 따른다.

## 개발 워크플로

이 리포는 [backlog.md](https://github.com/MrLesk/Backlog.md) 태스크 단위로 작업한다.
`.claude-code-agile-hooks.json`에 테스트 명령(`make test`)과 test-first 대상 경로(`testFirst`)가 설정되어 있다.
