---
id: TASK-43
title: '[플랫폼] 관측성: Prometheus'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 09:05'
labels:
  - platform
  - observability
dependencies:
  - TASK-36
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
카나리 판정과 섀도우 비교에 쓸 지표. CloudWatch는 AWS 단계(DRAFT-20)로.
선행: k3d 로컬 플랫폼 부트스트랩.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 micrometer-registry-prometheus, actuator는 관리 포트 8081에서만(인그레스 비노출, 외부 접근 404)
- [ ] #2 경량 Prometheus(보존 6h, 300Mi limit), 선택 Grafana
- [ ] #3 http_server_requests에 롤아웃 버전 라벨
- [ ] #4 5xx 비율·p95 레코딩 룰
<!-- AC:END -->
