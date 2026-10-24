---
id: TASK-60
title: '[운영] 백업·복구·무중단'
status: To Do
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 05:06'
labels:
  - ops
  - platform
dependencies:
  - TASK-36
  - TASK-41
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
실배포 고려사항: 백업은 복구해 봐야 믿을 수 있다.
선행: k3d 로컬 플랫폼, GitOps.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Postgres PITR(WAL 보관, 예: pgBackRest 또는 WAL-G → MinIO), 특정 시점 복구 리허설 스크립트와 결과 기록
- [ ] #2 k3s 상태(etcd/sqlite) 백업과 복구 절차
- [ ] #3 graceful shutdown(백엔드·프론트·Kafka 소비자), readiness로 연결 정리, PodDisruptionBudget
- [ ] #4 스팟 회수 2분 경고 처리 절차(AWS 단계용 설계, 로컬은 노드 drain으로 재현)
- [ ] #5 복구 목표(RPO·RTO) 문서화
<!-- AC:END -->
