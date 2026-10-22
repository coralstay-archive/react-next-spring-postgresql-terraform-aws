---
id: DRAFT-23
title: '시각 응답 정책: DB 확정 시각 반환, 작성 응답에 시각 미포함, 좋아요 최근 시각 표시'
status: Draft
assignee: []
created_date: '2026-10-12 05:14'
updated_date: '2026-10-12 05:14'
labels:
  - backend
  - frontend
  - policy
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
유저가 정한 시각 처리 방침(2026-10-12). TASK-19에서 Linux 나노초 시계 때문에 응답 시각과 DB 저장값이 어긋난 문제(AdminPostApiTest)를 계기로 정했다. 지금은 앱 시계를 마이크로초로 절삭해 CI만 맞춰 둔 상태이며, 근본 해결은 이 정책으로 한다. TASK-23(BE-7 시계 통일)과 범위가 겹치므로 착수 시 함께 정리한다.

## 방침
- 시각을 돌려줄 때는 메모리 값이 아니라 데이터베이스 입력이 완료(커밋)된 시점의 값을 돌려준다. 예: DB가 시각을 정하고(default now() 등) RETURNING이나 재조회로 받는다.
- 글 작성 응답에는 시각을 넣지 않는다. 댓글 등 다른 작성 응답도 마찬가지다. 시각은 조회 응답에서만 준다.
- 좋아요 버튼에는 시각을 표시한다. 가장 최근 좋아요 시각 하나만 보여 준다.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 세부 사항은 착수 전에 유저와 다시 확인한다.

## 비즈니스 규칙
- B1 (AC #1) API가 돌려주는 시각은 DB에 확정된 값과 정확히 같다(재조회해도 바뀌지 않는다)
- B2 (AC #2) 생성(작성) 응답에는 시각 필드가 없다
- B3 (AC #3) 좋아요 최근 시각은 가장 마지막 좋아요 하나의 시각이다(취소된 좋아요는 제외)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 조회 응답의 모든 시각(createdAt, updatedAt, hiddenAt, deletedAt 등)을 DB 확정 값으로 반환(DB가 시각을 정하고 RETURNING 또는 재조회로 받는다)
- [ ] #2 글·댓글 등 작성(POST) 응답에서 시각 필드 제거, 프론트는 작성 후 조회 응답의 시각을 사용
- [ ] #3 좋아요: 글 상세 응답과 좋아요 응답에 가장 최근 좋아요 시각(lastLikedAt) 추가, 좋아요 버튼에 표시
- [ ] #4 앱 시계 절삭(TASK-19#4)과 Instant.now() 사용처를 이 방침에 맞게 정리하고, Linux 나노초 시계에서도 응답과 재조회 값이 같음을 테스트
<!-- AC:END -->
