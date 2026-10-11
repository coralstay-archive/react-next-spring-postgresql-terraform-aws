---
id: TASK-17
title: 배포 스크립트 + GitHub Actions
status: To Do
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:19'
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
