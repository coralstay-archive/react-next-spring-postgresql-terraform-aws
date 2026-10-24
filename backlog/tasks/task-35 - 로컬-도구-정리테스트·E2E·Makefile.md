---
id: TASK-35
title: 로컬 도구 정리(테스트·E2E·Makefile)
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-24 00:29'
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
- [ ] #5 Next.js를 보안 패치가 반영된 버전으로 올리고 전체 테스트·빌드·E2E 통과 확인(16.4.0 빌드 시 취약점 안내, TASK-29에서 발견)
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
TASK-23 진행 중 관찰(2026-10-22): 머신 부하가 높을 때(load avg 약 45) 커밋 훅 make test 가 간헐 실패 — 프론트 vitest 5초 타임아웃(post-editor, post-detail), DbFailureTest 컨텍스트 기동 중 Flyway 첫 접속 EOF(Docker 포트 프록시 추정). 재실행하면 통과. loginSlidingWindow(Retry-After 891)는 TASK-23 브랜치 f53619f 에서 고침.
<!-- SECTION:NOTES:END -->
