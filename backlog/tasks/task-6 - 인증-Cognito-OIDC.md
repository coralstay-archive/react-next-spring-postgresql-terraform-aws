---
id: TASK-6
title: '인증: Cognito OIDC'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 15:02'
labels:
  - backend
  - auth
  - infra
dependencies:
  - TASK-4
references:
  - decision-6
  - decision-4
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Cognito User Pool을 OIDC 제공자로 연동한다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Terraform cognito 모듈: User Pool, App Client(secret), Hosted UI 도메인, 콜백 URL 변수
- [x] #2 Spring에 provider=COGNITO(issuer-uri) 등록, 로그인·연결 동작
- [x] #3 issuer/JWKS stub 테스트
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Cognito를 OIDC 제공자로 연동했다. Terraform 모듈 infra/main/modules/cognito(User Pool은 이메일 username·자동 검증, App Client는 generate_secret·code flow·openid email profile, 콜백은 public_base_url에서 유도, Hosted UI 도메인 v1/v2, deletion_protection 변수, issuer_uri·client_secret(sensitive) 등 출력). terraform test(mock_provider) 10개를 scripts/test.sh에 포함(fmt -check, init -backend=false, validate, test). testGlobs에 infra/**/*.tftest.hcl 추가. Spring: CognitoUserInfoMapper가 COGNITO_DOMAIN·ISSUER_URI로 엔드포인트와 JWKS를 직접 구성(디스커버리 없음, 미설정이면 미등록, 일부만 설정하면 기동 실패). email_verified는 문자열 'true'도 인정. WireMock Hosted UI·토큰·JWKS·userInfo로 로그인·연결·issuer/nonce 오류 흐름 테스트. 검증: cleanTest 후 make test 통과(repo 49, terraform test 10, backend 241, 스킵 0). 후속: TASK-16에서 루트 스택 연결, client_secret은 SSM, prod deletion_protection 결정. TASK-17 CI에 setup-terraform 추가. TASK-18 실제 Hosted UI 스모크.
<!-- SECTION:FINAL_SUMMARY:END -->
