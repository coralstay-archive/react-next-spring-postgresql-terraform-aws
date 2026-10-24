---
id: TASK-36
title: '[플랫폼] k3d 로컬 플랫폼 부트스트랩'
status: In Progress
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 05:10'
labels:
  - platform
dependencies: []
references:
  - decision-1
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
k3d(k3s) 단일 노드 로컬 클러스터와 로컬 레지스트리를 만들고, 프로필별로 켜고 끈다. LB는 k3d LB 컨테이너 + Traefik 인그레스.
선행: 없음.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #2) 어떤 프로필 조합도 문서화된 메모리 예산을 넘겨 조용히 뜨지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 make k3d-up 이 Docker 5GB에서 core(Postgres·MinIO·backend)를 띄우고 make k3d-smoke 에서 /api/actuator/health 가 UP
- [ ] #2 모든 pod에 requests/limits, 프로필 합계가 예산(core ~1.6GB)을 넘으면 스크립트가 경고
- [ ] #3 Flyway 마이그레이션을 앱 기동과 분리한 Job으로 실행
- [ ] #4 프로필을 끄면 리소스가 삭제되어 메모리가 회수된다(make k3d-profile, make k3d-stop)
- [ ] #5 make test 에 kustomize build + kubeconform 오프라인 검증 추가(증가 10초 미만)
- [ ] #6 메모리 절차 강제(1): make test·make test-integration은 시작할 때 k3d 클러스터가 떠 있으면 자동으로 멈추고 그 사실을 출력(커밋 훅의 make test에도 적용)
- [ ] #7 메모리 절차 강제(2): make k3d-up·k3d-profile은 Docker VM 메모리 5GB 미만, Testcontainers 컨테이너 실행 중, 프로필 합계 예산 초과면 이유와 해결법을 출력하고 실패(예산 초과는 FORCE=1로만 통과). 각 경우를 셸 테스트로 고정
- [ ] #8 스모크 스크립트들도 같은 사전 검사 함수를 공유한다(검사를 우회하는 진입점이 없음)
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
AC#2의 '경고'는 AC#7(예산 초과 시 실패, FORCE=1로만 통과)이 우선한다. 세션 중단 대비: 단계마다 이 노트에 진행 상황을 적는다.
<!-- SECTION:NOTES:END -->
