---
id: TASK-24
title: '백엔드: 인증 경계 정리'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-22 06:19'
labels:
  - backend
  - auth
dependencies:
  - TASK-20
references:
  - decision-4
  - decision-5
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-15, BE-16, BE-17, BE-18, BE-11.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 토큰이 만료돼도 로그아웃하면 refresh family가 폐기된다
- B2 (AC #2) ADMIN에서 강등되면 다음 요청부터 관리자 API가 403
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-15 refresh·logout·공개 GET에서는 만료된 Bearer 헤더를 무시
- [ ] #2 BE-16 역할도 요청마다 DB 상태와 대조(또는 역할 변경 시 세션 삭제)
- [ ] #3 BE-17 로그인 시 사용자 행 FOR SHARE 또는 세션 저장 후 상태 재확인
- [ ] #4 BE-18 로그인 계열 엔드포인트를 Suspended 필터 예외에 추가
- [ ] #5 BE-11 세션 역직렬화 실패는 세션 무효(401)로 처리
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (Plan-Review-Go)
0. 공통: 오류 모양은 TASK-20 계약({status,error,message}) 그대로. 정지 계정 로그인 오류는 기존 계약 403 `suspended`("정지된 계정은 로그인할 수 없습니다")를 auth.AuthErrors.suspendedLogin() 하나로 모아 쓴다(AuthController·PhoneAuthController·StepUpController·TokenService).
1. AC#1 BE-15 만료된 Bearer 무시:
   - security.LenientBearerTokenResolver(BearerTokenResolver): DefaultBearerTokenResolver 로 꺼낸 토큰이 "만료만 된" 토큰이고 요청이 관대 경로(POST /auth/token/refresh, POST /auth/logout, 공개 GET — SecurityConfig 의 permitAll GET 목록을 PUBLIC_GETS 하나로 모아 같이 쓴다)면 null 을 돌려 익명으로 계속한다. 서명·발급자·형식이 틀린 토큰은 그대로 401(위조·버그는 숨기지 않는다). 보호된 API 의 만료 토큰도 그대로 401.
   - 판정: JwtTokenService.isExpiredOnly(token) = 정상 decode 는 실패하고 만료 검증만 뺀 decoder(발급자·sub·token_type 검증은 유지)로는 성공. 
   - Bearer 경로 판정(BearerRequests)은 그대로 — 세션을 읽지 않고 CSRF 면제도 같다.
   - B1 로그아웃: principal 이 없고(만료 토큰 무시) refreshToken 이 있으면 만료 토큰의 서명 검증된 sub 로 "자기 family 만 폐기" 규칙을 그대로 지킨다(JwtTokenService.expiredSubject). 남의 refresh 는 여전히 폐기하지 않는다.
   - 테스트(AuthBoundaryExpiredBearerTest): 과거 시계로 만든 JwtTokenService 로 만료 토큰 발급 → refresh 200, logout 204 + family 폐기(B1), 공개 GET 200, /auth/me 는 401, 서명 틀린 토큰은 공개 GET 도 401, 만료 토큰 + 남의 refresh 는 폐기 안 함.
2. AC#2 BE-16 역할도 요청마다 DB 와 대조:
   - 방식 결정: 요청마다 DB 대조. 정지가 이미 요청마다 DB 최신 status 를 보는 방식(SuspendedUserWriteFilter)이고, 역할 변경 API 가 없어(DB 작업·ADMIN_EMAIL 부트스트랩) "변경 시 세션 삭제"를 걸 훅이 없으며, access JWT 는 상태가 없어 지울 수도 없다. 세션·JWT 두 경로를 한 곳에서 같게 처리하려면 요청마다 대조가 맞다. 비용: 인증된 요청마다 PK 조회 1회(role,status).
   - security.CurrentUserFilter: 보안 체인에서 인증 필터들 뒤·ExceptionTranslationFilter 뒤·AuthorizationFilter 앞. principal 이 AuthUser 면 UserRepository.findRoleAndStatusById 로 최신 role·status 를 읽어 AuthUserAuthentication 을 새로 만들어 SecurityContextHolder 에만 넣는다(세션에는 다시 쓰지 않음, stepUpRequired 는 유지). 계정이 없으면 컨텍스트를 비운다 → 보호된 API 401(기존 /auth/me 계약과 같음).
   - 효과: /admin/** hasRole, @PreAuthorize, OwnershipChecker, 숨김 글 조회(AuthUser.isAdmin) 모두 최신 역할. refactor: SuspendedUserWriteFilter 는 갱신된 principal 의 status 를 보면 되므로 중복 DB 조회를 뺀다.
   - 테스트(B2): ADMIN 의 세션·JWT 로 /admin/users 200 → DB 에서 role=USER → 같은 세션·JWT 로 403 forbidden. 반대로 승격도 다음 요청부터 반영. 계정 삭제 시 401.
3. AC#3 BE-17 로그인·정지 경쟁:
   - JWT(로그인·OTP·step-up 의 TokenService.issueForLogin): 같은 트랜잭션에서 사용자 행을 FOR SHARE 로 잠그고 status 재확인(UserRepository.lockStatusForShare, native select status ... for share). ACTIVE 가 아니면 403 suspended 로 롤백(refresh 행이 남지 않음). 정지 트랜잭션(findByIdForUpdate)과 서로 줄을 서므로, 정지가 먼저면 로그인이 SUSPENDED 를 보고, 로그인이 먼저면 정지의 revokeAllOfUser 가 새 refresh 를 본다.
   - 세션: 세션 행은 요청 끝 SessionRepositoryFilter 가 별도 트랜잭션으로 저장하므로 잠금으로 묶을 수 없다 → "세션 저장 후 상태 재확인". SessionLogin.login 이 요청 속성에 사용자 id 를 남기고, Spring Session 필터 바깥의 security.SessionLoginRecheckFilter(FilterRegistrationBean, SessionRepositoryFilter 보다 앞 순서)가 체인이 끝난 뒤(세션 커밋 후) DB status 를 다시 읽어 ACTIVE 가 아니면 그 사용자의 세션을 principal name 인덱스로 모두 지운다. 정지는 "status 커밋 → 세션 삭제" 순서이므로 어떤 끼어듦에서도 세션이 남지 않는다(재확인이 ACTIVE 를 봤다면 정지 커밋 전이고, 그 뒤의 정지 세션 삭제가 이미 저장된 세션을 지운다). 로그인 응답은 200 일 수 있지만 다음 요청부터 익명(401). 로컬·OTP·OAuth·step-up 세션 로그인 모두 SessionLogin 을 거치므로 같이 적용된다.
   - 테스트(결정적, sleep 없음, RowLock 패턴 — RowLock 에 문장 추가 실행과 대기 테이블 지정 추가): (a) JWT: 테스트 트랜잭션이 사용자 행 FOR UPDATE + status=SUSPENDED + refresh 전부 폐기(정지 트랜잭션 재현)를 쥔 채 로그인 → 로그인이 잠금을 기다리는 것을 pg_stat_activity 로 확인 → 커밋 → 403 suspended, 살아 있는 refresh 0. (b) 세션: 테스트 트랜잭션이 spring_session 테이블을 SHARE 로 잠가 로그인의 세션 저장을 붙잡은 채 AdminUserService.suspend 를 끝까지 실행 → 잠금 해제 → 그 사용자 세션 0, 쿠키로 /auth/me 401.
4. AC#4 BE-18 정지 필터 예외에 로그인 계열:
   - 현재: SuspendedUserWriteFilter 예외는 로그아웃뿐이라, 정지 계정의 세션·토큰이 남은 클라이언트의 로그인·가입·refresh·OTP 발송·검증이 필터에서 403 suspended("쓰기 요청을 할 수 없습니다")로 끝난다(다른 계정 로그인도 불가).
   - SecurityConfig.LOGIN_FAMILY(POST /auth/login, /auth/signup, /auth/token/refresh, /auth/phone/otp, /auth/phone/verify — 단 ?link=true 는 현재 계정 변경이라 제외)를 정지 예외에 추가. STEP_UP_EXEMPT 도 같은 목록을 쓴다. 이후 판정은 각 엔드포인트의 계약(docs/api.md): 다른 ACTIVE 계정이면 정상 로그인, 정지 계정 자신이면 비밀번호가 맞을 때 403 suspended(로그인 계약), 틀리면 401 invalid_credentials, refresh 는 403 suspended(rotate), OTP 검증은 403 suspended.
   - 테스트: 정지 계정 세션으로 다른 계정 로그인 200, 가입 201, OTP 발송 202, verify?link=true 는 여전히 403 suspended, 정지 계정 자신 로그인은 비밀번호가 틀리면 401 invalid_credentials(필터가 먼저 403 을 내지 않음).
5. AC#5 BE-11 세션 역직렬화 실패:
   - Spring Session JDBC 4.1 은 속성을 처음 읽을 때 역직렬화한다(lazy). 실패(ConversionFailedException·SerializationFailedException — 배포 뒤 클래스 변경, 깨진 바이트)가 보안 체인의 SecurityContext 로딩에서 터져 500 이 났다.
   - security.CorruptSessionFilter(FilterRegistrationBean, SessionRepositoryFilter 바로 뒤·보안 체인 앞): Bearer 가 아닌 요청이 세션을 가지면 속성을 한 번씩 읽어 보고, 실패하면 WARN 로그(세션 id 해시·속성 이름만) 후 세션을 invalidate(저장소에서 삭제 + SESSION 쿠키 만료)하고 익명으로 계속한다.
   - 오류 코드 결정: 새 코드를 만들지 않고 "세션 없음·만료"와 똑같이 처리한다 — 보호된 API 는 기존 401 `unauthorized`(표준 오류 모양), 공개 GET 은 익명으로 200. 프론트는 이미 401 unauthorized 를 로그아웃 상태로 다루고, 배포 뒤 모든 사용자의 공개 페이지가 깨지지 않는다.
   - 테스트: 로그인 세션의 SPRING_SECURITY_CONTEXT 바이트를 (a) 없는 클래스 이름으로 바꾼 직렬화 스트림(클래스 변경 재현), (b) 깨진 바이트로 바꾼 뒤 /auth/me → 401 unauthorized JSON, 세션 행 삭제 + 쿠키 만료, 공개 GET /posts → 200.
- 문서: docs/api.md 인증·인가 모델(만료 Bearer 무시 경로, 역할·상태 요청마다 DB, 로그인·정지 경쟁, 정지 필터 예외, 깨진 세션), 관리자 절의 "역할은 발급 시점 값" 문장 수정. 설정 변경 없음(README 그대로).
- 각 AC 는 red 실행으로 실패를 확인한 뒤 green 커밋(red 증거를 메시지에), 필요하면 refactor 커밋. 표식 TASK-24#n, #B1·#B2.
- 범위 밖으로 남길 것: refresh 로테이션(rotate)의 같은 경쟁(정지와 동시 로테이션), 로그인·가입 등 다른 엔드포인트의 만료 Bearer.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
독립 리뷰(2026-10-22) 반영 결정: #4 정지 필터 예외의 link 판정이 Spring boolean 변환(1·on·yes·퍼센트 인코딩)과 어긋나 정지 계정이 휴대폰을 연결할 수 있던 구멍을 고친다(디코딩된 값으로 '없거나 false일 때만 예외' + link()에서 ACTIVE 확인). #3 refresh 로테이션도 로그인과 같은 정지 경쟁이 있어 이 태스크에서 같이 고친다(유저 결정, AC#3 성격). 유지 결정: #5 깨진 세션은 401 unauthorized(새 코드 없음), 세션 로그인 중 정지 경쟁은 200 응답 후 세션 삭제로 둔다. 그 밖에 세션 경쟁 테스트의 cleanup-cron 간섭 제거, 재확인 DB 오류 시 WARN + 세션 삭제 시도.
<!-- SECTION:NOTES:END -->
