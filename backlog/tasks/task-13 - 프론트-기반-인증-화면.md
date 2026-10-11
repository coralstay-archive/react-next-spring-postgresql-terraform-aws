---
id: TASK-13
title: '프론트: 기반 + 인증 화면'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 19:56'
labels:
  - frontend
  - auth
dependencies:
  - TASK-3
references:
  - decision-10
  - decision-3
  - decision-5
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
API 클라이언트와 모든 로그인 수단 화면을 만든다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 fetch 래퍼(XSRF 헤더, 401 처리) + TanStack Query
- [x] #2 레이아웃·헤더·useMe 가드
- [x] #3 로그인 화면: LOCAL, 소셜 5종 버튼, 휴대폰 OTP
- [x] #4 가입 화면
- [x] #5 내 계정: 연결된 수단 목록·연결·해제
- [x] #6 next dev에서 /api rewrite로 로컬 백엔드 연동
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. **AC#6 개발 프록시(먼저)**: `next.config.ts`는 `NODE_ENV=production`일 때만 `output:'export'`, 개발일 때만 `rewrites()`로 `/api/:path*` → `http://localhost:8080/api/:path*`. `PUBLIC_BASE_URL=http://localhost:3000` 기본값과 OAuth 콜백이 dev에서 동작하는지 확인한다.
   - 쿠키 path `/`와 SameSite=Lax가 프록시 경유로도 유지되는지 확인한다.
2. **AC#1 API 클라이언트**: `src/lib/api.ts` fetch 래퍼. `credentials:'include'`, 상태 변경 메서드에 `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더, 쿠키가 없으면 `GET /api/auth/csrf`로 먼저 받는다.
   - 로그인, 로그아웃, verify, step-up 이후에는 토큰을 다시 읽는다. 오류 JSON `{status,error,message,fields}`는 `ApiError`로 바꾼다. 403 step_up_required와 429 Retry-After를 처리한다.
   - TanStack Query Provider, 쿼리 키 규약.
3. **AC#2 레이아웃·가드**: 헤더(로그인 상태, 닉네임, 관리자 링크, 로그아웃), `useMe()`(`/api/auth/me`), `RequireAuth`·`RequireAdmin` 클라이언트 가드, step-up 배너.
4. **AC#3 로그인 화면** `/login/`: 이메일·비밀번호 폼, 소셜 5종(google, github, naver, kakao, cognito) 버튼은 `/api/oauth2/authorization/{p}`로 페이지 이동, 휴대폰 OTP 탭(번호 → 코드, 3분 카운트다운, 429 재시도 카운트다운).
   - `/auth/callback/`은 status·error 처리 후 me를 다시 가져와 이동한다. 오류 코드는 한국어 메시지 매핑표로 바꾼다.
5. **AC#4 가입 화면** `/signup/`: 필드 검증(서버 규칙과 동일), 409 email_taken 처리, 가입 후 자동 로그인 호출.
6. **AC#5 내 계정** `/account/`: 연결된 수단 목록, 연결 버튼(`/api/me/identities/link/{p}`, 휴대폰은 OTP + `?link=true`), 해제(409 last_identity 메시지), `?linked`·`?error` 처리, step-up 폼(LOCAL 비밀번호 또는 소셜 재로그인).
- shadcn 컴포넌트 추가(input, label, card, tabs, dialog, sonner 등). 정적 export라 동적 라우트는 쓰지 않는다.
- 테스트: Vitest + Testing Library + MSW(요청 모킹)로 CSRF 헤더 부착, 오류 매핑, 가드 리다이렉트, OTP 카운트다운. `npm run build` export 성공을 확인한다.

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
프론트 기반과 인증 화면을 구현했다. next.config는 phase 함수로, dev에서만 /api rewrite(BACKEND_URL) + skipTrailingSlashRedirect, build는 정적 export. api.ts: 같은 오리진 /api, credentials include, 상태 변경 시 XSRF 쿠키를 헤더로(없으면 /auth/csrf, 동시 요청 공유), 매 요청 쿠키 재조회로 회전 대응, 403 csrf 시 1회 재시도, ApiError(code, fields, retryAfter). TanStack Query(4xx 재시도 없음, 401 unauthorized면 me=null). 레이아웃·헤더·useMe·RequireAuth/RequireAdmin·step-up 배너. /login/(LOCAL, 소셜 5종 링크, 휴대폰 OTP 3분·429 카운트다운), /auth/callback/, /signup/(서버 규칙 미러, 가입 후 자동 로그인), /account/(identity 목록·연결·해제, 휴대폰 링크, LOCAL step-up). 한국어 오류 맵. 검증: cleanTest 후 make test(backend 567, frontend 53), tsc·lint·build 통과, 실제 백엔드와 next dev 프록시로 CSRF·로그인·OTP·429 curl 확인. 후속: TASK-14 상세는 쿼리 파라미터 라우트, 오류 코드 맵 추가. TASK-16 CloudFront /api/*는 쿼리·쿠키 전달, 트레일링 슬래시 추가 금지. 실제 소셜 로그인과 브라우저 시각 확인은 미검증.
<!-- SECTION:FINAL_SUMMARY:END -->
