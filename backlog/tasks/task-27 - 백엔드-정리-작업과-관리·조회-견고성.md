---
id: TASK-27
title: '백엔드: 정리 작업과 관리·조회 견고성'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 01:46'
labels:
  - backend
  - ops
dependencies:
  - TASK-20
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-10, BE-28, BE-38, BE-40. TASK-18 노트의 정리 잡 후보도 포함.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 정리 작업은 보안상 필요한 기록(활성 토큰, 레이트리밋 창)을 지우지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-10 만료·폐기 refresh_tokens, 오래된 otp_challenges·post_views, 미사용 tags 정리 스케줄
- [ ] #2 BE-28 조회수 집계 실패는 로그만 남기고 상세 응답은 정상 반환
- [ ] #3 BE-38 정지 후 세션 삭제 실패는 로그 + 응답에 경고 필드(정지는 유지)
- [ ] #4 BE-40 카테고리 sortOrder 범위 검증과 오버플로 방지
- [ ] #5 rate_limit_events 정리: 설정된 app.rate-limit.* window 최댓값보다 오래된 행만 지운다(TASK-22 리뷰 M2)
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
TASK-22 리뷰(M2, 2026-10-21): rate_limit_events 정리가 빠져 있어 AC #5로 추가. 정리 기준은 고정 1시간이 아니라 설정된 window 최댓값(B1과 같은 취지).
<!-- SECTION:NOTES:END -->
