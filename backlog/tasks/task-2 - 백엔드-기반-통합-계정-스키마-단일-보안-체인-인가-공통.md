---
id: TASK-2
title: '백엔드 기반: 통합 계정 스키마 + 단일 보안 체인 + 인가 공통'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:48'
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
- [x] #1 Flyway V1: users, user_identities(UNIQUE provider+subject), local_credentials, refresh_tokens, spring_session 테이블
- [x] #2 SecurityFilterChain 하나: Bearer 헤더가 있으면 JWT 인증, 없으면 세션(Spring Session JDBC) 인증
- [x] #3 CSRF: CookieCsrfTokenRepository(X-XSRF-TOKEN), Bearer 요청만 면제
- [x] #4 역할(USER/ADMIN)·상태(ACTIVE/SUSPENDED)·OwnershipChecker 기반 @PreAuthorize 공통 인가
- [x] #5 Testcontainers PostgreSQL 기반 통합 테스트 베이스와 보안 매트릭스 테스트 골격
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
통합 계정 스키마(Flyway V2: users, user_identities UNIQUE(provider,subject), local_credentials, refresh_tokens, spring_session*)와 단일 SecurityFilterChain 완성. Bearer 헤더가 있으면(유효성 무관) Resource Server(HS256 자체 JWT, JwtTokenService)로, 없으면 Spring Session JDBC 세션으로 인증한다. Bearer 요청은 세션을 읽거나 쓰지 않는다. CSRF는 csrf.spa() + XSRF-TOKEN 쿠키(path=/)이고 Bearer만 면제한다. GET /api/auth/csrf로 토큰을 받는다. SuspendedUserWriteFilter가 DB 최신 상태로 SUSPENDED의 모든 쓰기를 403 처리하고, @ownership(OwnershipChecker)는 본인 또는 ADMIN만 허용한다. 401/403은 JSON. 검증: make test 통과(backend 89, 스킵 0). 후속 메모: TASK-3은 signup/login permitAll, 로그인 시 세션 ID 변경, AuthUserAuthentication을 SecurityContextRepository로 저장, logout을 Suspended 필터 예외로. TASK-8은 공개 GET 경로를 permitAll에 추가(기본은 deny). 역할은 세션/토큰 발급 시점 값이다(강등 반영은 세션 만료나 15분 뒤).
<!-- SECTION:FINAL_SUMMARY:END -->
