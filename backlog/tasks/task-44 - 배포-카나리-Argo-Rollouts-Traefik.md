---
id: TASK-44
title: '[배포] 카나리: Argo Rollouts + Traefik'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 09:05'
labels:
  - platform
  - delivery
dependencies:
  - TASK-43
  - TASK-41
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
새 버전을 일부 트래픽에만 보내고 지표가 나쁘면 자동으로 되돌린다.
선행: 관측성, GitOps.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #3) 분석에 실패한 새 버전은 사람 개입 없이 트래픽 0%로 돌아간다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 backend Deployment → Rollout, TraefikService 가중치 20 → 50 → 100%
- [ ] #2 각 단계 Prometheus 분석(5xx 비율·p95) 통과해야 진행
- [ ] #3 일부러 5xx를 내는 이미지는 자동 롤백(스모크 스크립트로 재현)
- [ ] #4 DB 마이그레이션 expand/contract 규칙과 구버전-새 스키마 호환 테스트
- [ ] #5 세션은 JDBC 공유라 버전이 섞여도 로그인 유지
<!-- AC:END -->
