---
id: DRAFT-17
title: '[AWS] 데이터 보호: 볼륨·마운트·백업·복원'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - infra
  - aws
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: OPS-1, OPS-9, OPS-11, OPS-24, OPS-36. 실제 AWS 배포(TASK-18 AC#1)와 함께 진행.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #2) 데이터 볼륨이 없으면 DB는 뜨지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 OPS-1 data_snapshot_id 변수와 복원 절차
- [ ] #2 OPS-9 데이터 볼륨 미마운트 시 docker/postgres 기동 차단(mountpoint 확인)
- [ ] #3 OPS-11 blkid 종료 코드 2일 때만 mkfs, 이름 기반 fallback 제거
- [ ] #4 OPS-24 배포 전 스냅샷 또는 pg_dump, expand/contract 마이그레이션 규칙
- [ ] #5 OPS-36 복원 런북과 복원 시험
<!-- AC:END -->
