---
id: TASK-29
title: '프론트: 세션 만료·정지 시 입력 보존과 캐시 리셋'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 06:10'
labels:
  - frontend
  - auth
dependencies:
  - TASK-28
references:
  - decision-5
  - decision-10
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-5, FE-6, FE-9, FE-15, FE-32, FE-10, FE-11, FE-13, FE-14. TASK-18의 두 UI 이슈 중 정지 댓글 유실의 근본 원인.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
- PRG: 폼 제출 성공 시 결과 페이지로 replace 이동, 새로고침·뒤로가기 재제출과 이중 제출을 차단한다.

## 비즈니스 규칙
- B1 (AC #3) 세션이 끝나도 사용자가 입력한 글·댓글 내용은 사라지지 않는다
- B2 (AC #1) 로그아웃 상태에서는 수정·삭제 버튼이 보이지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-5 세션 만료 401이면 me 외 모든 쿼리 리셋 + 전역 '세션이 만료되었습니다' 안내
- [ ] #2 FE-6 포커스·주기적으로 me 재조회
- [ ] #3 FE-9·FE-32 401이 나도 작성 중 화면을 언마운트하지 않고 인라인 재로그인 안내 + 초안 sessionStorage 보존
- [ ] #4 FE-15 댓글 401 시 폼과 입력·오류 유지
- [ ] #5 FE-10 로그아웃은 성공(또는 401)일 때만 상태 해제, 실패하면 오류 표시
- [ ] #6 FE-11 me 로딩 실패 시 헤더 오류 상태와 다시 시도 버튼
- [ ] #7 FE-13 콜백 실패 시 저장된 next 정리, 돌아가기 링크에 next 유지
- [ ] #8 FE-14 가입 성공 후 로그인 실패 시 폼 대신 로그인 링크(재제출 방지)
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
착수 결정(2026-10-23, 유저): me 재조회는 창 포커스 때 + 탭이 보일 때 5분마다.
<!-- SECTION:NOTES:END -->
