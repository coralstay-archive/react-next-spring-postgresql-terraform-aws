---
id: TASK-52
title: '[AWS] k3s 스팟 노드 실배포(나중 단계)'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-29 01:46'
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
  - decision-11
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
- [ ] #1 infra/platform(TASK-65 얼개)으로 VPC/SG/IAM + k3s 노드 1대(t4g.large 스팟, arm64, EIP 1개)를 만든다. 별도 LB EC2 없이 Traefik을 노드에서 바로 노출, HAProxy LB 모듈은 기본 꺼 둠(decision-11)
- [ ] #2 External Secrets + SSM Parameter Store, 이미지는 GHCR, 업로드는 클러스터 안 MinIO(S3 대신, presigned URL은 Traefik 경유), CloudFront 없음. Cognito·SMS·SSM은 유지(decision-11)
- [ ] #3 모니터링은 클러스터 안 Prometheus·Alertmanager(CloudWatch 대신), 카나리 분석에 연결. 노드 로그(cloud-init·journal) 수집과 보존(OPS-37)
- [ ] #4 엣지 보안: 응답 헤더 정책(클릭재킹 차단)과 Traefik 레이트리밋. WAF는 미결 — CloudFront가 없으면 AWS WAF를 붙일 곳이 없으므로 이때 위치·필요성을 결정(유저: 그때 자세히 본다)
- [ ] #5 MQTT·Kafka TLS, 기존 EC2 compose 경로(infra/main 레거시) 철거 순서와 롤백 계획
- [ ] #6 인증서·DNS: cert-manager + Let's Encrypt 자동 갱신. 도메인은 미결 — 정하기 전에는 EIP의 sslip.io 이름을 쓰는 안 검토, 도메인 확정 시 2단계 apply 절차(OPS-4)·OAuth 콜백 갱신·메일 SPF·DKIM·DMARC
- [ ] #7 비용: AWS Budgets 알림, 미사용 시간 인스턴스 정지(make aws-stop·aws-start 또는 스케줄), Infracost 상한 검사(TASK-66) 통과
- [ ] #8 업그레이드 절차: k3s·Postgres·Kafka 버전 업그레이드 런북과 리허설
- [ ] #9 EC2 OS는 Rocky Linux 9.8 arm64(로컬 rocky-dev와 같은 버전, 10 아님), 같은 install.sh·k3s 설치 스크립트, SELinux enforcing 유지
- [ ] #10 데이터 볼륨 스냅샷 복원: data_snapshot_id로 새 볼륨 복원 절차와 리허설(OPS-1), 인스턴스 교체 런북과 태그 기반 SSM 대상(OPS-3)
- [ ] #11 비밀 값: SSM 파라미터 키별 회전 런북, 등록 스크립트 부분 실패 처리(OPS-28)
- [ ] #12 첫 실배포 확인: apply 후 https://<도메인 또는 sslip.io 이름>/api/actuator/health 200 (TASK-18 AC #1에서 이관, 유저 결정 2026-10-29). smoke_test.sh(SMOKE_BASE_URL)·make smoke 재사용
<!-- AC:END -->
