---
id: DRAFT-25
title: '[플랫폼] k3d 로컬 플랫폼 부트스트랩'
status: Draft
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 03:46'
labels:
  - platform
dependencies: []
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
<!-- AC:END -->
