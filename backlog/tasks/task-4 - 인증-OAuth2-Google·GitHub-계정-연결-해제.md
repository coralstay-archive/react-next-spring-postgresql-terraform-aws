---
id: TASK-4
title: '인증: OAuth2 Google·GitHub + 계정 연결/해제'
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 14:10'
labels:
  - backend
  - auth
dependencies:
  - TASK-3
references:
  - decision-4
  - decision-8
  - decision-3
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
표준 OAuth2/OIDC 로그인과 IdentityResolver, 명시적 계정 연결을 구현한다.

## 비즈니스 규칙
- B1 (AC #2) 이메일이 같아도 자동 병합하지 않는다
- B2 (AC #3) 이미 다른 계정에 연결된 identity는 연결 불가(409)
- B3 (AC #4) 마지막 identity는 해제 불가
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 oauth2Login으로 Google(OIDC)·GitHub 로그인, 성공 시 세션 생성 후 프론트로 리다이렉트
- [ ] #2 IdentityResolver: (provider,subject) 있으면 로그인, 없으면 신규 계정 생성
- [ ] #3 로그인 상태에서 /api/me/identities/link/{provider}로 시작하면 현재 계정에 연결
- [ ] #4 GET /api/me/identities, DELETE /api/me/identities/{id}
- [ ] #5 WireMock으로 제공자 응답을 stub한 통합 테스트
<!-- AC:END -->
