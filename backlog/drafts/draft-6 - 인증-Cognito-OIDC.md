---
id: DRAFT-6
title: '인증: Cognito OIDC'
status: Draft
assignee: []
created_date: '2026-10-11 13:03'
labels:
  - backend
  - auth
  - infra
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Cognito User Pool을 OIDC 제공자로 연동한다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Terraform cognito 모듈: User Pool, App Client(secret), Hosted UI 도메인, 콜백 URL 변수
- [ ] #2 Spring에 provider=COGNITO(issuer-uri) 등록, 로그인·연결 동작
- [ ] #3 issuer/JWKS stub 테스트
<!-- AC:END -->
