---
id: TASK-28
title: '프론트: 오픈 리다이렉트와 오류 메시지 매핑'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 05:30'
labels:
  - frontend
  - security
dependencies:
  - TASK-20
references:
  - decision-10
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-1, FE-2, FE-3, FE-12, FE-17.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 로그인 후 이동은 언제나 같은 오리진이다
- B2 (AC #2) 사용자에게 영어 원문이나 내부 코드가 그대로 보이지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-1 next는 new URL 기준 같은 오리진만 허용하고 pathname+search+hash만 사용(탭·개행·역슬래시 차단)
- [ ] #2 FE-2 비JSON·Spring 기본 오류는 상태 범위별 한국어 문구로(코드는 ^[a-z0-9_]+$일 때만 사용)
- [ ] #3 FE-3 JSON을 기대한 2xx에 비JSON이면 ApiError bad_response
- [ ] #4 FE-12 oauth2_failed와 표준 OAuth 오류 코드 매핑, 콜백 전용 fallback 문구
- [ ] #5 FE-17 linked는 알려진 provider만 허용하고 표시 후 쿼리 제거
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. (AC#1 FE-1) lib/auth.ts safeNext 재작성: 원문에 제어 문자(U+0000-001F, U+007F)·역슬래시가 있으면 거부 → new URL(next, window.location.origin) 파싱 실패·다른 오리진이면 거부 → pathname+search+hash 만 반환, 결과가 '//' 로 시작하거나 디코드한 경로에 역슬래시·제어 문자·'//' 시작이 있으면 거부(인코딩 우회). 소비처(login-view·phone 로그인·callback takeNext·signup)는 모두 safeNext 를 거치는지 확인. 테이블 테스트(B1).
2. (AC#2 FE-2) lib/api.ts toApiError: 본문 error 가 ^[a-z0-9_]+$ 가 아니거나 JSON 이 아니면 code=http_<status>, message=상태 범위별 한국어(statusText 영어 원문 사용 중단). lib/errors.ts errorMessage: 표에 있으면 표 문구, 없으면 서버 message 가 한국어(한글 포함)일 때만 그 문구, 아니면 상태 범위별 한국어(0 네트워크, 401/403/404/408/413/429 개별, 그 밖 4xx, 502-504, 그 밖 5xx). docs/api.md 의 TASK-20~27 코드(not_found·method_not_allowed·not_acceptable·unsupported_media_type·payload_too_large·conflict·lock_timeout·internal_error·db_unavailable·db_timeout·service_unavailable·too_many_pending_attachments·storage_unavailable·attachment_expired·phone_not_allowed·invalid_refresh_token 등) 매핑 추가, 문서 코드 전수 테스트(B2).
3. (AC#3 FE-3) lib/api.ts parseBody: 본문이 있는데 JSON 파싱 실패면 ApiError(status, 'bad_response', 한국어) — 텍스트를 데이터로 넘기지 않는다. 204·빈 본문은 그대로 undefined.
4. (AC#4 FE-12) lib/errors.ts oauthErrorMessage(code): 형식 맞고 표에 있으면 표 문구, 아니면 콜백 전용 fallback. oauth2_failed·server_error·temporarily_unavailable·invalid_request·unauthorized_client·unsupported_response_type·invalid_scope·invalid_client·unsupported_grant_type 등 매핑. callback-view 와 account-view 의 ?error= 에 적용.
5. (AC#5 FE-17) account-view: linked 는 SOCIAL_PROVIDERS id 일 때만 알림, 모르는 값은 표시하지 않음. 읽은 뒤 router.replace 로 linked 만 뺀 URL 로 바꿔 새로고침 시 반복되지 않게(다른 쿼리·hash 유지).
6. docs/frontend.md 갱신, make test 전체 통과 확인.
<!-- SECTION:PLAN:END -->
