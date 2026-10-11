---
id: decision-5
title: '자격 발급: 같은 엔드포인트에서 Bearer 헤더로 세션/JWT 분기'
date: '2026-10-11 13:01'
status: accepted
---
## Context

웹은 세션이 안전하고, 비브라우저 클라이언트에는 JWT가 필요하다.

## Decision

SecurityFilterChain 하나에서 Authorization: Bearer가 있으면 JWT, 없으면 세션(Spring Session JDBC) + CSRF로 처리한다. CSRF는 Bearer 요청만 면제한다. JWT는 짧은 access + refresh 로테이션(재사용 감지)이다. 이번 범위에서 JWT는 LOCAL과 PHONE 로그인 API에서만 발급한다.

## Consequences

API와 인가 규칙이 하나로 유지된다. 두 경로의 보안 매트릭스 테스트가 필수다.
