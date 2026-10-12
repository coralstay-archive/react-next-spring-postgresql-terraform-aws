---
id: TASK-23
title: '백엔드: DB 타임아웃·잠금·동시성'
status: To Do
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 04:45'
labels:
  - backend
  - data
dependencies:
  - TASK-20
references:
  - decision-2
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-3, BE-4, BE-27, BE-39, BE-29, BE-30, BE-7.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #3) 동시 수정이 다른 사람의 숨김·삭제를 되돌리지 못한다
- B2 (AC #1) DB 장애 중에도 응답은 30초 안에 JSON 오류로 끝난다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-3 hikari connection-timeout, lock_timeout·statement_timeout 설정, DB 장애는 503 db_unavailable, 락 실패는 409 lock_timeout
- [ ] #2 BE-4 요청 본문 크기 상한(약 64KB, 첨부 제외) 초과 시 413 JSON
- [ ] #3 BE-27·BE-39 글에 @Version 낙관적 잠금, 충돌 시 409 conflict(작성자 수정·관리자 숨김·복구 경쟁)
- [ ] #4 BE-29 좋아요도 FOR SHARE 가시성 확인, 글 행 부재 시 404
- [ ] #5 BE-30 page×size 상한(예: 10,000행)
- [ ] #6 BE-7 시각은 Clock 빈 하나로 통일
<!-- AC:END -->
