---
id: TASK-33
title: '프론트: 목록·관리자 엣지 케이스와 좋아요 롤백'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 14:48'
labels:
  - frontend
  - board
dependencies:
  - TASK-28
references:
  - decision-10
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-20, FE-22, FE-33.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-20 좋아요 롤백은 해당 필드만 함수형 업데이트, 404면 상세 무효화
- [ ] #2 FE-22 page 클램프(int, totalPages), 목록 오류에 첫 페이지 링크, 잘못된 q면 '검색어 무시' 표시, 카테고리·태그 오류 표시
- [ ] #3 FE-33 관리자 페이저 범위 클램프
- [ ] #4 목록 페이지 이동은 서버 OFFSET 상한(page×size ≤ 10,000, TASK-23#5)을 넘지 않는다 — 넘는 '다음'·페이지 번호는 숨기거나 막는다(TASK-23 리뷰)
- [ ] #5 관리자 정지 응답의 warnings(session_revoke_failed)를 화면에 표시한다(TASK-27#3)
- [ ] #6 카테고리 sortOrder 입력 검사를 서버 범위(0~10000, TASK-27#4)와 맞춘다
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. (AC#1 FE-20) lib/board.ts useToggleLike: onMutate 가 낙관적 변경을 적용했는지와 이전 like 필드(likedByMe·likeCount)만 컨텍스트에 남기고, onError 는 최신 캐시에 함수형 업데이트로 그 두 필드만 되돌린다(그사이 재조회된 제목·조회수 등은 유지). 404 면 상세 쿼리를 무효화해 '없는 글' 화면으로 맞춘다. 테스트: 요청 중 캐시의 제목·조회수를 바꾼 뒤 실패 → 바뀐 값 유지·좋아요만 원복, 404 → 상세 재요청.
2. (AC#2 FE-22) board-query.ts: URL page 는 1 이상의 정수만(그 외 1), 자릿수 오버플로는 AC#4 상한으로 클램프. 목록 응답의 totalPages 를 알면 page 가 그보다 크면 router.replace 로 마지막 쪽(결과 0건이면 1쪽)으로 옮긴다. 목록 오류(데이터 없음)에는 필터를 모두 뺀 '첫 페이지로' 링크(이미 / 면 생략). URL 의 q 가 2~100자가 아니면 '검색어 무시' 안내(검색어 없이 목록을 보여 준다는 문장 포함). 카테고리·인기 태그 조회 실패(데이터 없음)는 각 자리에 짧은 안내 + 다시 시도.
3. (AC#3 FE-33) admin/pager.tsx: totalPages 를 알고(placeholder 가 아닌 응답) page 가 범위 밖이면 onChange(마지막 쪽, 0건이면 0)으로 클램프. 사용자·글·감사 로그 탭 공통.
4. (AC#4) board-query.ts MAX_OFFSET=10000, 목록 최대 쪽 = floor(10000/PAGE_SIZE)+1(1부터). parseListFilters 가 상한으로 클램프하고 화면은 '최대 N쪽까지' 안내를 보인다. Pagination 의 '다음' 은 min(totalPages, 상한)까지만, 상한에 닿으면 검색·필터로 좁히라는 안내. 관리자 Pager 도 같은 상한(관리자 목록도 서버 상한 적용, docs/api.md)으로 '다음' 을 막는다.
5. (AC#5) lib/admin.ts: 정지 응답 타입에 warnings. users-tab: warnings 에 session_revoke_failed 가 있으면 다이얼로그를 닫은 뒤 목록 위에 '정지는 적용됐지만 세션을 다 끊지 못했을 수 있다(남은 세션도 쓰기는 막힌다) — 다시 정지하면 재시도(멱등)' 안내 + '세션 끊기 다시 시도' 버튼(같은 사유로 suspend 재호출). 모르는 경고 코드는 코드 그대로 일반 안내.
6. (AC#6) validateCategoryForm: 순서는 0~10000 정수(서버 MAX_SORT_ORDER), 오류 '순서는 0~10000 사이 정수여야 합니다'. 수정 시 순서를 안 바꿨으면(PATCH 에서 빠짐) 범위 밖 기존 값도 검사하지 않는다(서버와 동일).
7. docs/frontend.md 갱신, make test·tsc·eslint.
<!-- SECTION:PLAN:END -->
