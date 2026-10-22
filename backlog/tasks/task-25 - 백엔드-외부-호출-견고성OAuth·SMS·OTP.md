---
id: TASK-25
title: '백엔드: 외부 호출 견고성(OAuth·SMS·OTP)'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-22 06:48'
labels:
  - backend
  - auth
dependencies:
  - TASK-20
references:
  - decision-6
  - decision-7
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-19, BE-20, BE-21, BE-24, BE-22, BE-23.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 브라우저 OAuth 흐름은 어떤 실패에도 프론트 콜백으로 돌아간다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-19 OAuth 성공 핸들러 예외는 /auth/callback/?error=server_error로 302하고 로그
- [ ] #2 BE-20 OAuth 실패·매퍼 예외 원인을 WARN 로그로(민감값 제외)
- [ ] #3 BE-21 token·userinfo·JWKS 호출에 연결·읽기 타임아웃
- [ ] #4 BE-24 End User Messaging apiCallTimeout·attempt 타임아웃
- [ ] #5 BE-22 OTP 소모 후 계정 처리 실패 시 사용자 안내(재요청 가능 상태 유지)
- [ ] #6 BE-23 OTP 한도 설정값 1 이상 검증
<!-- AC:END -->
