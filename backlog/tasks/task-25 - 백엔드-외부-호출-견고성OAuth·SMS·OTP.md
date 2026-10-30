---
id: TASK-25
title: '백엔드: 외부 호출 견고성(OAuth·SMS·OTP)'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-22 11:10'
labels:
  - backend
  - auth
dependencies:
  - TASK-20
references:
  - decision-6
  - decision-7
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-19, BE-20, BE-21, BE-24, BE-22, BE-23.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 브라우저 OAuth 흐름은 어떤 실패에도 프론트 콜백으로 돌아간다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 BE-19 OAuth 성공 핸들러 예외는 /auth/callback/?error=server_error로 302하고 로그
- [x] #2 BE-20 OAuth 실패·매퍼 예외 원인을 WARN 로그로(민감값 제외)
- [x] #3 BE-21 token·userinfo·JWKS 호출에 연결·읽기 타임아웃
- [x] #4 BE-24 End User Messaging apiCallTimeout·attempt 타임아웃
- [x] #5 BE-22 OTP 소모 후 계정 처리 실패 시 사용자 안내(재요청 가능 상태 유지)
- [x] #6 BE-23 OTP 한도 설정값 1 이상 검증
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (Plan-Review-Go)
1. AC#1 BE-19 / B1: OAuth2LoginSuccessHandler.onAuthenticationSuccess 본문을 try/catch(RuntimeException)로 감싼다. 예외면 응답이 아직 커밋되지 않았을 때 resetBuffer 후 프론트로 302 — 로그인 모드는 /auth/callback/?error=server_error, 연결 모드(LinkIntent 를 이미 꺼냈으면)는 /account/?error=server_error. 로그는 ERROR 1줄(registrationId, 예외 클래스 사슬, 모드)만 — 메시지·이메일·토큰·스택의 원문 값은 싣지 않는다. 정상 경로의 세션 로그인(SessionLogin)은 그대로.
   - B1 검증(통합 테스트, OAuthFlow + WireMock): 성공 핸들러 안 DB 오류(테스트용 트리거로 user_identities INSERT 실패) · 실패 핸들러(토큰 오류) · 매퍼 예외(Kakao id 없음 → invalid_user_info) · state 불일치(authorization_request_not_found) · 제공자 오류 파라미터(?error=access_denied) · 모르는 제공자(인가 시작) 모두 프론트 콜백 302 이고 500·JSON 이 아님.
2. AC#2 BE-20: OAuth2LoginFailureHandler 가 WARN 1줄: 등록 id(요청 경로에서), 응답 오류 코드, 예외 클래스 사슬(SimpleName), 원인 사슬의 HTTP 상태(RestClientResponseException)만. 예외 메시지·error_description·제공자 원문 응답·코드·state·토큰은 싣지 않는다. 성공 핸들러의 매퍼 예외(지금은 조용히 null)도 WARN(등록 id + 예외 클래스). OutputCapture 로 원인 존재 + 민감값(인가 코드, access token, 이메일, 원문 응답 본문) 부재를 확인.
3. AC#3 BE-21: OAuth2HttpProperties(app.oauth2.http.connect-timeout 기본 3s, read-timeout 기본 5s, 양수 검증; env OAUTH_HTTP_CONNECT_TIMEOUT·OAUTH_HTTP_READ_TIMEOUT). 하나의 ClientHttpRequestFactory(JDK HttpClient connectTimeout + readTimeout)로 (a) 토큰: RestClientAuthorizationCodeTokenResponseClient 에 스프링 기본과 같은 변환기·OAuth2ErrorResponseErrorHandler 를 둔 RestClient, (b) userinfo: DefaultOAuth2UserService(+ OidcUserService 위임)의 RestTemplate(OAuth2ErrorResponseErrorHandler), (c) JWKS: OidcIdTokenDecoderFactory 대신 같은 검증기(DefaultOidcIdTokenValidatorFactory)·클레임 변환기를 쓰는 JwtDecoderFactory<ClientRegistration> 빈 — NimbusJwtDecoder.withJwkSetUri(..).restOperations(타임아웃 RestTemplate). OAuth2LoginCustomizer 의 tokenEndpoint·userInfoEndpoint 에 연결. 통합 테스트는 IntegrationTest 에 read-timeout=2s 를 주고 WireMock 지연(fixedDelay)으로 토큰·userinfo·JWKS 각각 제한 시간 안에 ?error=invalid_token_response / invalid_user_info_response / invalid_id_token 302. README·example.env·deploy/secrets.example.txt 에 env 기록.
4. AC#4 BE-24: SmsProperties.Aws 에 apiCallTimeout(기본 10s)·apiCallAttemptTimeout(기본 4s) 추가(양수 검증, env SMS_API_CALL_TIMEOUT·SMS_API_CALL_ATTEMPT_TIMEOUT). SmsConfiguration 이 ClientOverrideConfiguration 에 설정 + UrlConnectionHttpClient connection/socket timeout. 타임아웃(ApiCallTimeoutException 등 SdkException)은 기존 SmsDeliveryException → 503 sms_unavailable 계약 그대로. 테스트: 클라이언트 설정값 확인 + 지연 로컬 HttpServer 로 실제 타임아웃이 SmsDeliveryException 으로 끝남.
5. AC#5 BE-22: 소모 보상 방식. 검증 성공(소모 커밋) 뒤 계정 처리(가입·연결·세션/토큰 발급)에서 예외가 나면 PhoneOtpService.release(challengeId)가 새 트랜잭션에서 consumed_at 을 되돌리고(조건부 UPDATE), 원래 예외를 그대로 낸다(기존 오류 계약 500/503). 복구 자체가 실패하면 WARN(챌린지 id 만) 후 원래 예외 유지. 원자화(한 트랜잭션)는 UNIQUE 경합 재시도·세션 저장이 같은 트랜잭션에 들어갈 수 없어 택하지 않는다. 사용자는 같은 인증번호로 다시 확인할 수 있고(만료 전), 새 발송·쿨다운·일일 한도를 쓰지 않는다. 테스트: 트리거로 PHONE identity INSERT 실패 → 5xx, 트리거 제거 후 같은 코드로 200, 발송 횟수 그대로. docs/api.md 에 기록.
6. AC#6 BE-23: OtpProperties 컴팩트 생성자에서 maxAttempts·phoneDailyLimit·ipHourlyLimit ≥ 1, ttl 양수, phoneCooldown 음수 금지 검증(IllegalArgumentException → 기동 실패, TASK-22 RateLimitProperties 와 같은 방식). ApplicationContextRunner 로 0·음수 설정 시 기동 실패 확인.
- 각 AC는 red 실행으로 실패를 확인한 뒤 green 커밋(red 증거를 메시지에), 필요하면 refactor 커밋. 표식 TASK-25#n, #B1.
- 리뷰: 구현 후 독립 서브에이전트 리뷰 → fix·refactor 커밋.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
독립 리뷰(2026-10-22) 반영 결정: 유지(유저) — #5 OTP 되돌리기는 모든 실패(4xx 정상 거절 포함)에 적용, #4 SMS SDK 재시도 유지(드문 중복 문자는 README에 기록), #1 연결 모드 server_error는 /account/?error=server_error(로그인은 AC대로 /auth/callback/). 고칠 것 — (1) 휴대폰 세션 로그인 뒤의 실패(providers 조회 등)에서 release하면 로그인된 세션이 남고 OTP도 재사용 가능 → 세션 로그인을 마지막 단계로, (2) OAuth 성공 핸들러 실패 시 이 요청이 sessionLogin 을 호출했을 때만 인증을 걷어 냄(기존 제한 세션·연결 중 로그인 보존), (3) 타임아웃 통합 테스트 지연 12s→짧게. 후속 후보: PhoneOtpService.send 의 log.warn(ex) 스택 전체 기록 — BE-20 원칙(클래스명만) 적용 검토.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
외부 호출(OAuth·SMS·OTP) 실패 처리를 정리했다.
- AC#1 BE-19: OAuth 성공 핸들러 예외는 ERROR 로그 + 302. 로그인은 /auth/callback/?error=server_error, 연결은 /account/?error=server_error(유저 결정). 이 요청이 세션 로그인을 했을 때만 인증을 걷어 내 기존 세션은 보존한다. B1: 실패 핸들러·매퍼 예외·state 불일치·제공자 오류·모르는 제공자까지 모두 프론트 콜백으로 돌아감을 테스트로 고정.
- AC#2 BE-20: OAuth 실패·매퍼 예외는 WARN으로 registration·오류 코드·HTTP 상태·예외 클래스 사슬만 남긴다(메시지·코드·토큰·이메일·응답 원문 제외).
- AC#3 BE-21: token·userinfo·JWKS 호출에 연결 3s·읽기 5s(OAUTH_HTTP_*). JWKS는 OidcIdTokenDecoderFactory와 같은 검증 규칙의 자체 팩토리.
- AC#4 BE-24: End User Messaging apiCallTimeout 10s·apiCallAttemptTimeout 4s(SMS_API_CALL_*), 초과 시 503 sms_unavailable. SDK 재시도는 유지(드문 중복 문자, 유저 결정).
- AC#5 BE-22: OTP 확인 뒤 계정 처리가 실패하면 소모를 되돌려 같은 인증번호로 다시 확인할 수 있다(모든 실패에 적용, 유저 결정). 불변식: 오류 응답이면 로그인되지 않는다 — 세션 로그인을 마지막 단계로 옮겼다.
- AC#6 BE-23: OTP 한도 설정값은 기동 시 검증(횟수 ≥ 1, ttl > 0, 쿨다운 ≥ 0).
독립 리뷰 반영: OTP 이중 로그인 구멍, OAuth 실패 시 기존 세션 보존, 타임아웃 테스트 시간 단축.
검증: make test 통과(backend 804, repo 226, deploy 135, terraform 57, frontend 160).
후속 후보: PhoneOtpService.send 의 스택 전체 WARN 로그에 BE-20 원칙 적용, 프론트 server_error 전용 문구(TASK-28), OIDC 디코더 등록별 캐시.
<!-- SECTION:FINAL_SUMMARY:END -->
