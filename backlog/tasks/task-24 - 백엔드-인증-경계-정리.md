---
id: TASK-24
title: '백엔드: 인증 경계 정리'
status: To Do
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 04:45'
labels:
  - backend
  - auth
dependencies:
  - TASK-20
references:
  - decision-4
  - decision-5
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-15, BE-16, BE-17, BE-18, BE-11.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 토큰이 만료돼도 로그아웃하면 refresh family가 폐기된다
- B2 (AC #2) ADMIN에서 강등되면 다음 요청부터 관리자 API가 403
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-15 refresh·logout·공개 GET에서는 만료된 Bearer 헤더를 무시
- [ ] #2 BE-16 역할도 요청마다 DB 상태와 대조(또는 역할 변경 시 세션 삭제)
- [ ] #3 BE-17 로그인 시 사용자 행 FOR SHARE 또는 세션 저장 후 상태 재확인
- [ ] #4 BE-18 로그인 계열 엔드포인트를 Suspended 필터 예외에 추가
- [ ] #5 BE-11 세션 역직렬화 실패는 세션 무효(401)로 처리
<!-- AC:END -->
