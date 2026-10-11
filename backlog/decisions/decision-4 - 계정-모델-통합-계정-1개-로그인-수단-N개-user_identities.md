---
id: decision-4
title: '계정 모델: 통합 계정 1개 + 로그인 수단 N개 (user_identities)'
date: '2026-10-11 13:01'
status: accepted
---
## Context

로그인 수단 7가지(LOCAL, Google, GitHub, Naver, Kakao, Cognito, PHONE)를 모두 지원해야 한다.

## Decision

users 1:N user_identities(provider, subject 유니크). 비밀번호는 local_credentials에만 둔다. users.email과 phone은 연락·표시용이고 로그인 키가 아니다.

## Consequences

수단을 하나 추가하면 identity 행 하나가 늘어난다. email은 nullable이다. 마지막 identity는 해제할 수 없다.
