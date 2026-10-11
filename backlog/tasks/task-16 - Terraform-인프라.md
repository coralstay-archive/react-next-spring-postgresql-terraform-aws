---
id: TASK-16
title: Terraform 인프라
status: To Do
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:19'
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
