---
id: TASK-2
title: '백엔드 기반: 통합 계정 스키마 + 단일 보안 체인 + 인가 공통'
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:31'
labels:
  - backend
  - auth
dependencies:
  - TASK-1
references:
  - decision-4
  - decision-5
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
users/user_identities 스키마와 세션·Bearer 분기 보안 체인, 공통 인가를 만든다.

## 비즈니스 규칙
- B1 (AC #3) 세션 인증 요청의 상태 변경(POST/PATCH/DELETE)은 유효한 CSRF 토큰이 없으면 403
- B2 (AC #3) Bearer 인증 요청은 CSRF 검사를 하지 않는다
- B3 (AC #4) SUSPENDED 사용자는 모든 쓰기 요청이 403
- B4 (AC #4) 남의 리소스 수정·삭제는 ADMIN만 허용
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Flyway V1: users, user_identities(UNIQUE provider+subject), local_credentials, refresh_tokens, spring_session 테이블
- [ ] #2 SecurityFilterChain 하나: Bearer 헤더가 있으면 JWT 인증, 없으면 세션(Spring Session JDBC) 인증
- [ ] #3 CSRF: CookieCsrfTokenRepository(X-XSRF-TOKEN), Bearer 요청만 면제
- [ ] #4 역할(USER/ADMIN)·상태(ACTIVE/SUSPENDED)·OwnershipChecker 기반 @PreAuthorize 공통 인가
- [ ] #5 Testcontainers PostgreSQL 기반 통합 테스트 베이스와 보안 매트릭스 테스트 골격
<!-- AC:END -->
