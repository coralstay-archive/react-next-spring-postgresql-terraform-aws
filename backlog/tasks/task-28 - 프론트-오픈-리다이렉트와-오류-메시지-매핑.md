---
id: TASK-28
title: '프론트: 오픈 리다이렉트와 오류 메시지 매핑'
status: To Do
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 04:45'
labels:
  - frontend
  - security
dependencies:
  - TASK-20
references:
  - decision-10
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-1, FE-2, FE-3, FE-12, FE-17.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 로그인 후 이동은 언제나 같은 오리진이다
- B2 (AC #2) 사용자에게 영어 원문이나 내부 코드가 그대로 보이지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-1 next는 new URL 기준 같은 오리진만 허용하고 pathname+search+hash만 사용(탭·개행·역슬래시 차단)
- [ ] #2 FE-2 비JSON·Spring 기본 오류는 상태 범위별 한국어 문구로(코드는 ^[a-z0-9_]+$일 때만 사용)
- [ ] #3 FE-3 JSON을 기대한 2xx에 비JSON이면 ApiError bad_response
- [ ] #4 FE-12 oauth2_failed와 표준 OAuth 오류 코드 매핑, 콜백 전용 fallback 문구
- [ ] #5 FE-17 linked는 알려진 provider만 허용하고 표시 후 쿼리 제거
<!-- AC:END -->
