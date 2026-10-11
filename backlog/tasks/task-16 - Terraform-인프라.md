---
id: TASK-16
title: Terraform 인프라
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 23:05'
labels:
  - infra
dependencies:
  - TASK-1
references:
  - decision-1
  - decision-2
  - decision-3
  - decision-9
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
AWS 인프라를 코드로 만든다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 infra/bootstrap: state S3 버킷(use_lockfile)
- [ ] #2 network(VPC, public subnet), backend_host(EC2 t4g.small, EIP, 데이터 EBS, DLM, SSM role, SG=CloudFront prefix list)
- [ ] #3 ECR, S3 web/uploads(OAC), CloudFront(/api/*, /uploads/*, CF Function)
- [ ] #4 ci_oidc: GitHub OIDC provider + 배포 role(최소 권한)
- [ ] #5 dns_cert: domain_name 지정 시 Route53 + ACM(us-east-1) 활성화
- [ ] #6 fmt -check / validate / plan 통과
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (apply 없이 plan까지만. 실제 apply는 TASK-18에서 유저 확인 후)
1. **AC#1 bootstrap** `infra/bootstrap`: state S3 버킷(버전 관리, 암호화, 퍼블릭 차단, `use_lockfile`). 로컬 state로 1회 apply하는 절차를 문서화한다.
2. **AC#2 network·backend_host**:
   - `modules/network`: VPC, public subnet 1개, IGW.
   - `modules/backend_host`: EC2 t4g.small AL2023 arm64(SSM AMI 파라미터), IMDSv2 필수, 루트 볼륨 암호화. 데이터 EBS(gp3 20GB, 별도 AZ 고정, `/var/lib/postgresql` 마운트, user_data에서 포맷과 fstab 처리). EIP. DLM 일일 스냅샷 7개 보관.
   - SG: 8080은 CloudFront origin-facing managed prefix list만 허용, 22는 닫는다.
   - 인스턴스 role: SSM Core, ECR pull, CloudWatch Logs, SSM Parameter 읽기(`/board/prod/*`), `sms-voice:SendTextMessage`, uploads 버킷 Put/Get/Head/Tagging.
   - user_data: docker, compose plugin, awslogs 드라이버 설정.
3. **AC#3 ECR·S3·CloudFront** `modules/frontend_cdn`:
   - ECR 리포(이미지 스캔, lifecycle 최근 20개).
   - web 버킷 + OAC.
   - CloudFront: 기본은 S3(CachingOptimized + CF Function `/x/` → `/x/index.html`). `/api/*`는 EC2 EIP public DNS:8080 origin(HTTP), CachingDisabled, 오리진 요청 정책은 AllViewerExceptHostHeader + `CloudFront-Viewer-Address`. `/uploads/*`는 uploads 버킷 OAC.
   - `modules/uploads`(TASK-11 작성분)와 `modules/cognito`(TASK-6)를 연결한다.
4. **AC#4 ci_oidc**: GitHub OIDC provider와 배포 role. trust는 `repo:<owner>/<repo>:ref:refs/heads/main` + pull_request 읽기 전용 role을 분리한다. 권한은 ECR push, S3 web sync, CloudFront invalidation, `ssm:SendCommand`(해당 인스턴스와 문서만), ssm GetCommandInvocation.
5. **AC#5 dns_cert**: `domain_name`이 비어 있으면 count=0. 지정 시 Route53 hosted zone 생성 또는 조회, ACM(us-east-1) DNS 검증, CloudFront alias.
6. **AC#6 검증**: 모듈별 terraform test(mock_provider), `envs/prod.tfvars`, 루트 `.terraform.lock.hcl` 커밋, `terraform validate`. 실제 plan은 자격증명이 있을 때만 실행하고 결과를 기록한다.
- 출력: CloudFront 도메인, EC2 instance id, ECR URL, web 버킷, distribution id, Cognito 출력, OIDC role ARN.
- SSM Parameter는 Terraform으로 만들지 않는다. 시크릿 값이 state에 남지 않게 TASK-17 put-secrets.sh로 입력한다.

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->
