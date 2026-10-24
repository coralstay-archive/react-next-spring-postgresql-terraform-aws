---
id: TASK-36
title: '[플랫폼] k3d 로컬 플랫폼 부트스트랩'
status: In Progress
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 05:52'
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

[구현 계획] scripts/_k3d.sh(사전 검사 공통 함수: Docker VM 메모리 4.5GiB 미만=5GB 설정 미만, Testcontainers 라벨 컨테이너, 프로필 예산 합계 > 상한(FORCE=1만 통과)) + scripts/k3d.sh(up·profile·smoke·stop·down·stop-if-running, 모든 기동 진입점이 같은 사전 검사) + deploy/k8s/budgets.txt(예산 단일 출처) + deploy/k8s/{k3d/cluster.yaml,base,profiles/core} + kubeconform 스키마 저장소 내 고정(deploy/k8s/schemas, 오프라인) + Flyway 마이그레이션 Job(백엔드 이미지의 MigrationJob main, 앱은 k8s 프로필에서 spring.flyway.enabled=false, initContainer가 await) + scripts/test/k8s_test.sh. 진행: 착수.

[진행] AC#3 백엔드 MigrationJob(migrate·await)+application-k8s.properties 구현·테스트 통과(MigrationJobTest 4건). 스크립트(_k3d.sh·k3d.sh·k8s-validate.sh·test-integration.sh)·매니페스트(deploy/k8s)·k8s_test.sh(50건 통과)·README·CI(kubeconform) 작성 완료, 커밋 중.

[진행] 613497c AC#3 커밋. 다음: 스크립트·매니페스트·테스트·README·CI 커밋 → 백엔드 이미지로 MigrationJob 실제 실행 확인.

[진행] 커밋 613497c(AC#3 MigrationJob)·42547a0(k3d 플랫폼·사전 검사·매니페스트·검증)·6fa87f5(Job 없는 프로필 대기 수정). make test 전체 통과(repo 236, deploy 144, tools 42, k8s 52, terraform 58, backend 888, frontend 577). 실제 이미지로 MigrationJob migrate·await, k8s 프로필 기동(health UP, 392MiB/768MiB) 확인. 실제 k3d-up·k3d-smoke(AC#1·#4)는 Docker VM 3.8GiB 라 사전 검사가 거부 — Docker Desktop 5GB 로 올린 뒤 make k3d-up && make k3d-smoke && make k3d-profile PROFILES=core && make k3d-stop 으로 확인 필요. 남은 일: 독립 서브에이전트 코드 리뷰, AC 체크.
<!-- SECTION:NOTES:END -->
