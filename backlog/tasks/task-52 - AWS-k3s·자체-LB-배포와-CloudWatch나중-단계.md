---
id: TASK-52
title: '[AWS] k3s·자체 LB 배포와 CloudWatch(나중 단계)'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 12:33'
labels:
  - aws
  - platform
dependencies:
  - TASK-51
references:
  - decision-1
  - decision-2
  - decision-3
  - decision-9
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
실행하지 않는 계획용 draft. 로컬에서 만든 구성을 AWS에 올린다. 관리형 범위(혼합 권장)는 이 단계에서 확정한다.
선행: 로컬 플랫폼 전부. 관련: DRAFT-17~22(AWS 운영), DRAFT-20(모니터링).

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 자체 운영 LB EC2(HAProxy, TLS 종단) + k3s EC2(arm64) + VPC/SG/IAM(Terraform, tftest)
- [ ] #2 External Secrets + SSM, 이미지는 ECR 또는 GHCR, S3·CloudFront·Cognito·SMS 유지(혼합 확정 시)
- [ ] #3 CloudWatch 알람·로그(DRAFT-20과 합침), 카나리 분석에 연결
- [ ] #4 엣지 보안: AWS WAF(관리형 규칙·rate-based), 현재 정적 배포 응답 헤더 정책(클릭재킹 차단)
- [ ] #5 MQTT·Kafka TLS, 기존 EC2 compose 경로 철거 순서와 롤백 계획
- [ ] #6 인증서·DNS: cert-manager + Let's Encrypt 자동 갱신, 도메인 메일 SPF·DKIM·DMARC
- [ ] #7 비용: AWS Budgets 알림, 미사용 시간 인스턴스 자동 정지
- [ ] #8 업그레이드 절차: k3s·Postgres·Kafka 버전 업그레이드 런북과 리허설
- [ ] #9 EC2 OS는 Rocky Linux 9(arm64), 로컬 rocky-dev와 같은 k3s 설치 스크립트 사용, SELinux enforcing 유지
- [ ] #10 EC2 이미지는 로컬 rocky-dev와 같은 Rocky Linux 9.8 arm64(유저 확정, 10 아님)
<!-- AC:END -->
