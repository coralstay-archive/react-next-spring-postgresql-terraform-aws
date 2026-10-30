---
id: TASK-33
title: '프론트: 목록·관리자 엣지 케이스와 좋아요 롤백'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 15:41'
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
- [x] #1 FE-20 좋아요 롤백은 해당 필드만 함수형 업데이트, 404면 상세 무효화
- [x] #2 FE-22 page 클램프(int, totalPages), 목록 오류에 첫 페이지 링크, 잘못된 q면 '검색어 무시' 표시, 카테고리·태그 오류 표시
- [x] #3 FE-33 관리자 페이저 범위 클램프
- [x] #4 목록 페이지 이동은 서버 OFFSET 상한(page×size ≤ 10,000, TASK-23#5)을 넘지 않는다 — 넘는 '다음'·페이지 번호는 숨기거나 막는다(TASK-23 리뷰)
- [x] #5 관리자 정지 응답의 warnings(session_revoke_failed)를 화면에 표시한다(TASK-27#3)
- [x] #6 카테고리 sortOrder 입력 검사를 서버 범위(0~10000, TASK-27#4)와 맞춘다
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
독립 리뷰(2026-10-24) 반영 결정(유저): '첫 페이지로'는 필터 모두 빼고 / 유지. 정지 경고의 재시도는 백엔드에 세션 삭제 전용 API(정지 상태는 건드리지 않음, 감사 로그는 세션 삭제로 따로)를 추가해 쓴다(이 태스크에서, 백엔드 테스트 포함). 모르는 경고 코드는 일반 한국어 문장만 보이고 코드는 콘솔로(B2 예외 없음). 관리자 목록도 OFFSET 상한 유지. 고칠 것: 좋아요 롤백은 캐시가 아직 낙관적 값일 때만 되돌리기(아니면 상세 무효화), sortOrder '바꾸지 않음' 비교를 숫자로 통일, frontend.md 관리자 cap testid 목록 보완.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
목록·관리자 엣지 케이스와 좋아요 롤백을 정리했다.
- AC#1 FE-20: 좋아요 실패 시 캐시가 아직 낙관적 값일 때만 likedByMe·likeCount를 되돌리고, 그사이 서버 값이 들어왔으면 상세를 다시 받는다. 404면 상세 무효화 → 없는 글.
- AC#2 FE-22: page는 1 이상 정수로, totalPages를 넘으면 마지막 쪽으로 replace. 목록 오류에 '첫 페이지로'(필터 모두 제거, 유저 결정), 잘못된 q는 '검색어 무시' 안내, 카테고리·인기 태그 오류와 다시 시도.
- AC#3 FE-33: 관리자 Pager는 결과가 줄어 범위 밖이면 마지막 쪽으로(placeholder 중에는 보류).
- AC#4: 목록과 관리자 목록 모두 OFFSET 상한(page×size ≤ 10,000, 20개씩 501쪽)을 넘지 않음(관리자 포함 — 유저 결정).
- AC#5: 정지 응답 warnings(session_revoke_failed)를 목록 위에 안내하고, 재시도는 새 관리자 API POST /api/admin/users/{id}/sessions/revoke(정지 상태 불변, 세션·refresh 폐기, 감사 USER_SESSIONS_REVOKE, 자기 자신·다른 ADMIN 409)를 쓴다(유저 결정). 모르는 경고 코드는 일반 한국어 문장만, 코드는 콘솔.
- AC#6: 카테고리 sortOrder는 0~10000 정수만, 바꾸지 않은 값은 숫자 비교로 판단해 PATCH에서 뺀다.
검증: make test 통과(frontend 558, backend 884, repo 233, deploy 135, terraform 57), tsc·eslint.
범위 밖: 상한을 넘는 목록의 'N / 실제 totalPages' 표시 방식, 관리자 글 탭 keepPreviousData 없음.
<!-- SECTION:FINAL_SUMMARY:END -->
