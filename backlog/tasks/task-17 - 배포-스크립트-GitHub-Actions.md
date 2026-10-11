---
id: TASK-17
title: 배포 스크립트 + GitHub Actions
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 23:34'
labels:
  - infra
  - ci
dependencies:
  - TASK-16
references:
  - decision-1
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
로컬과 GHA가 공유하는 배포 파이프라인을 만든다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 scripts/build-backend.sh(arm64 이미지 → ECR), deploy-backend.sh(ssm send-command → compose pull/up → 헬스체크)
- [ ] #2 deploy-frontend.sh(build → s3 sync → invalidation)
- [ ] #3 put-secrets.sh: SSM Parameter Store 시크릿 입력, EC2에서 환경 파일 생성
- [ ] #4 ci.yml(PR: 테스트·빌드·tf validate), deploy.yml(main: OIDC로 배포, ubuntu-24.04-arm)
- [ ] #5 make deploy로 로컬에서도 동일 동작
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. **AC#1 백엔드 빌드·배포**:
   - `backend/Dockerfile`: 멀티스테이지, `eclipse-temurin:25-jre` arm64, non-root, `-XX:MaxRAMPercentage=60`.
   - `scripts/build-backend.sh`: buildx linux/arm64 → ECR push, 태그는 git sha.
   - `scripts/deploy-backend.sh`: `aws ssm send-command`(AWS-RunShellScript)로 EC2에서 put-secrets가 만든 env 파일 갱신 → `docker compose -f deploy/docker-compose.prod.yml pull && up -d` → `/api/actuator/health` 대기 → 실패 시 이전 태그로 롤백. 대기는 get-command-invocation 폴링.
   - `deploy/docker-compose.prod.yml`: api + postgres(데이터 EBS 경로 바인드), awslogs 드라이버, healthcheck, restart.
2. **AC#2 프론트 배포**: `scripts/deploy-frontend.sh`: `npm ci && npm run build` → `aws s3 sync out/ s3://web --delete`. HTML은 `no-cache`, `_next/static`은 immutable 캐시. 이후 CloudFront invalidation `/*`.
3. **AC#3 시크릿**: `scripts/put-secrets.sh`: 대화형 또는 파일로 SSM SecureString `/board/prod/*`를 입력한다. 대상: DB_PASSWORD, JWT_SECRET, OTP_HASH_KEY, OAuth 5종 id·secret, COGNITO_*, ADMIN_EMAIL, PUBLIC_BASE_URL, COOKIE_SECURE=true, CLIENT_IP_HEADER, SMS_*. EC2 측 `render-env.sh`가 get-parameters-by-path로 env 파일(권한 600)을 생성한다.
4. **AC#4 GitHub Actions**:
   - `ci.yml`(PR): setup-java 25, setup-node 24, setup-terraform, `make test`. Testcontainers는 Docker가 있는 ubuntu 러너에서 실행한다.
   - `deploy.yml`(main push): OIDC assume → `ubuntu-24.04-arm`에서 build-backend, deploy-backend, deploy-frontend를 순서대로 실행하고 concurrency group을 둔다.
5. **AC#5 로컬 동일 동작**: `make deploy`, `deploy-backend`, `deploy-frontend`가 같은 스크립트를 호출한다. 필요한 값(리전, ECR URL, 버킷, distribution, instance id)은 `terraform output -json`에서 읽는다(`scripts/tf-outputs.sh`).
- 테스트: `scripts/test/*`에 shellcheck(가능하면), 스크립트 dry-run 모드(`DRY_RUN=1`이면 aws 호출을 출력만) 검증, workflow YAML 구문 검사(actionlint가 있으면).

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->
