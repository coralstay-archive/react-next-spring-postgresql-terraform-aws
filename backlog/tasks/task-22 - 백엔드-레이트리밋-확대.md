---
id: TASK-22
title: '백엔드: 레이트리밋 확대'
status: To Do
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 04:45'
labels:
  - backend
  - security
dependencies:
  - TASK-20
references:
  - decision-7
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-13, BE-31, BE-34, BE-25. OTP 레이트리밋(otp_challenges 기반)과 같은 패턴으로 공용 리미터를 만든다.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 제한 세션에서 step-up 비밀번호 추측은 계정당 5회 실패 후 15분 차단
- B2 (AC #1) 로그인 실패 제한 응답은 계정 존재 여부를 드러내지 않는다
- B3 (AC #4) 허용 국가가 아닌 번호는 발송 전에 400
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-13 로그인·가입·step-up 실패 횟수 제한(계정+IP), 429 + Retry-After
- [ ] #2 BE-31 글·댓글 작성 사용자별 한도
- [ ] #3 BE-34 presign 사용자별 PENDING 개수와 시간당 한도
- [ ] #4 BE-25 SMS 발송 허용 국가 목록(기본 KR)과 MOBILE 유형만 허용
<!-- AC:END -->
