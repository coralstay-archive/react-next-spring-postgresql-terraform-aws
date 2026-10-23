---
id: TASK-30
title: '프론트: 네트워크·오프라인·타임아웃'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 08:06'
labels:
  - frontend
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
포함: FE-4, FE-7, FE-8, FE-23.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 오프라인일 때 제출은 나중에 몰래 전송되지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-4 오프라인 배너, 뮤테이션 networkMode always로 즉시 network 오류
- [ ] #2 FE-7 CSRF 쿠키가 계속 없으면 cookies_blocked 전용 오류
- [ ] #3 FE-8 뮤테이션 요청 타임아웃(AbortSignal)
- [ ] #4 FE-23 재조회 실패 시 기존 데이터 유지 + 작은 오류 배너
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
착수 결정(2026-10-23, 유저): 뮤테이션 요청 타임아웃 35초(백엔드 30초 예산보다 길게). 시간 초과 시 '저장됐는지 확실하지 않으니 확인 후 다시 시도' 안내. S3 업로드는 TASK-31 범위.
<!-- SECTION:NOTES:END -->
