---
id: TASK-35
title: 로컬 도구 정리(테스트·E2E·Makefile)
status: To Do
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 04:45'
labels:
  - dx
dependencies:
  - TASK-19
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: OPS-38, OPS-39, OPS-40, OPS-41. AWS 불필요.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 OPS-38 build-diagrams --check는 고정 환경에서만 비교하고 차이 목록 출력
- [ ] #2 OPS-39 e2e.sh PID 기반 종료와 KILL 격상
- [ ] #3 OPS-40 lock 파일이 바뀌면 npm ci
- [ ] #4 OPS-41 배포 타깃에 .env 전역 export가 섞이지 않게
<!-- AC:END -->
