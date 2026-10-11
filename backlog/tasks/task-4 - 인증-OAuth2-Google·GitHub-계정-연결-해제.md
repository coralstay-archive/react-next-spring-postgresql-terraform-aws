---
id: TASK-4
title: '인증: OAuth2 Google·GitHub + 계정 연결/해제'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 14:36'
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
- [x] #1 oauth2Login으로 Google(OIDC)·GitHub 로그인, 성공 시 세션 생성 후 프론트로 리다이렉트
- [x] #2 IdentityResolver: (provider,subject) 있으면 로그인, 없으면 신규 계정 생성
- [x] #3 로그인 상태에서 /api/me/identities/link/{provider}로 시작하면 현재 계정에 연결
- [x] #4 GET /api/me/identities, DELETE /api/me/identities/{id}
- [x] #5 WireMock으로 제공자 응답을 stub한 통합 테스트
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Google(OIDC)·GitHub 소셜 로그인과 계정 연결/해제를 구현했다. 설정된 제공자만 등록(OAuth2ClientRegistrations, 키가 없어도 기동). redirect-uri는 PUBLIC_BASE_URL/api/login/oauth2/code/{id}로 고정(CloudFront Host 불일치 대응). 성공하면 IdentityResolver → SessionLogin 후 /auth/callback/?status=ok로 302, 실패나 정지 계정은 ?error=<code>. 연결 모드: GET /api/me/identities/link/{p}가 세션에 LinkIntent(10분)를 두고, 콜백에서 같은 사용자인지 확인 후 연결하고 /account/?linked=로 보낸다. 다른 계정 소유 identity는 identity_in_use(B2). 이메일 자동 병합 없음(B1). GET/DELETE /api/me/identities, 마지막 identity 해제는 409 last_identity(B3, 사용자 행 잠금). OAuth2UserInfoMapper 추상화로 제공자를 추가할 수 있다. WireMock stub(RSA 서명 id_token + JWKS)으로 전체 흐름을 HTTP로 테스트. 검증: cleanTest 후 make test 통과(backend 196, 스킵 0). 후속: TASK-5는 매퍼 @Component + 설정만 추가(Naver response 중첩, Kakao numeric id, client_secret_post). TASK-6 Cognito email_verified 문자열 처리. TASK-13은 next dev /api rewrite, /auth/callback/와 /account/ 페이지, 콜백 후 CSRF 재조회. TASK-16/18은 PUBLIC_BASE_URL, COOKIE_SECURE=true, /api/* 쿼리·쿠키 전달, 시크릿은 SSM.
<!-- SECTION:FINAL_SUMMARY:END -->
