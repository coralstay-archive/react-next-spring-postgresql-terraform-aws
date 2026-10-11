---
id: decision-6
title: Cognito는 OIDC 제공자 중 하나로 취급
date: '2026-10-11 13:01'
status: accepted
---
## Context

Cognito를 허브로 둘지 여러 제공자 중 하나로 둘지 정해야 했다.

## Decision

Cognito User Pool을 Google 등과 같은 OIDC 제공자 하나(provider=COGNITO)로 취급한다. Terraform으로 User Pool과 Hosted UI를 만든다.

## Consequences

구조가 일관된다. Cognito의 내장 소셜·SMS 기능과 기능이 겹친다.
