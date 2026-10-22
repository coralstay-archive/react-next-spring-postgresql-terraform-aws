---
id: TASK-22
title: '백엔드: 레이트리밋 확대'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-21 04:37'
labels:
  - backend
  - security
dependencies:
  - TASK-20
references:
  - decision-7
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-13, BE-31, BE-34, BE-25. OTP 레이트리밋(otp_challenges 기반)과 같은 패턴으로 공용 리미터를 만든다.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 제한 세션에서 step-up 비밀번호 추측은 사용자별 15분 창 안 실패 5회(슬라이딩 창)에 이르면 429
- B2 (AC #1) 로그인 실패 제한 응답은 계정 존재 여부를 드러내지 않는다
- B3 (AC #4) 허용 국가가 아닌 번호는 발송 전에 400
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 BE-13 로그인·가입·step-up 실패 횟수 제한(계정+IP), 429 + Retry-After
- [x] #2 BE-31 글·댓글 작성 사용자별 한도
- [x] #3 BE-34 presign 사용자별 PENDING 개수와 시간당 한도
- [x] #4 BE-25 SMS 발송 허용 국가 목록(기본 KR)과 MOBILE 유형만 허용
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (Plan-Review-Go)
0. 공용 리미터: OtpRateLimiter(otp_challenges 기반 슬라이딩 윈도우 + advisory lock) 패턴을 일반화한다. 새 테이블 rate_limit_events(bucket, key_hash, occurred_at, 인덱스 (bucket, key_hash, occurred_at))에 이벤트를 기록하고, 창 안의 개수를 센다. 키는 해시(sha256)로 저장해 이메일·IP 원문을 남기지 않는다. 정리는 TASK-27 정리 잡에서(창의 최대 길이보다 오래된 행). 429 rate_limited + Retry-After는 기존 RateLimitedException을 재사용한다. 설정은 app.rate-limit.* 로 한다. Clock 빈 사용.
1. AC#1 BE-13 로그인·가입·step-up:
   - login: 실패 기준 계정(정규화 이메일)별 15분 5회 실패 → 15분 차단, IP별 시간당 실패 30회. 성공하면 계정 실패 카운트를 초기화하지 않는다(창 기반). 차단 응답은 계정 존재 여부와 무관하게 동일(B2). 차단 중에는 비밀번호 검사를 하지 않는다(BCrypt CPU 보호).
   - signup: IP별 시간당 10회.
   - step-up/local: 사용자별 15분 5회 실패 → 15분 차단(B1). 제한 세션 공격 시나리오를 테스트로 재현.
   - 클라이언트 IP는 ClientIpResolver 사용.
2. AC#2 BE-31 글·댓글 작성: 사용자별 글 분당 3회·시간당 30회, 댓글 분당 10회. ADMIN 예외 여부는 결정해서 문서화(제안: ADMIN 예외 없음).
3. AC#3 BE-34 presign: 사용자별 PENDING 첨부 최대 20개, 시간당 presign 60회. 초과 시 429(개수 초과는 409 too_many_pending도 가능, 하나로 결정해 문서화).
4. AC#4 BE-25 SMS: 허용 국가 목록(app.sms.allowed-regions, 기본 KR)과 MOBILE·FIXED_LINE_OR_MOBILE 유형만 허용. 그 외는 발송 전 400 invalid_phone(또는 phone_not_allowed). 레이트리밋보다 먼저 검사해 한도를 소모하지 않는다(B3).
- 프론트: 새 429는 기존 api.ts의 retryAfter와 errors.ts 매핑으로 처리되는지 확인(문구만 필요하면 TASK-28로 이월).
- 각 AC는 red 실행으로 실패를 확인한 뒤 green 커밋(red 증거를 메시지에), 필요하면 refactor 커밋. 표식 TASK-22#n, #B1~#B3. Clock(MutableClock)으로 창 경계를 테스트하고 sleep은 쓰지 않는다.
- 리뷰: 구현 후 독립 서브에이전트 리뷰 → fix·refactor 커밋.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
독립 리뷰(2026-10-21) 결정: M1 차단 방식은 15분 슬라이딩 창(5회)을 유지하고, 시간에 나눠 넣은 실패 테스트로 동작을 고정한다(진짜 15분 차단으로 바꾸려면 테스트를 충분히 갖춘 뒤 따로 진행). L5 계정 잠금 DoS를 줄이려고 로그인 계정 한도 키를 (정규화 이메일, IP) 조합으로 바꾼다(IP별 시간당 30회는 유지). M2 rate_limit_events 정리는 TASK-27 AC #5로 이월. L1~L4(시계 차이 문서화, release 실패 시 WARN, 처리 중 시도 집계 문서화, 동시성 테스트)는 이 태스크에서 반영.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
공용 RateLimiter(rate_limit_events, (bucket,키) advisory lock, SHA-256 키, 슬라이딩 창, 429 rate_limited + Retry-After)를 만들고 네 영역에 적용했다.
- AC#1 BE-13: 로그인 실패는 (정규화 이메일, IP) 기준 15분 창 안 5회, IP 기준 1시간 30회. 가입은 IP 기준 1시간 10회. step-up 실패는 사용자 기준 15분 창 안 5회(B1). 막힌 동안에는 BCrypt 검사를 하지 않고, 계정이 있든 없든 같은 429를 준다(B2).
- AC#2 BE-31: 글은 사용자 기준 1분 3회·1시간 30회, 댓글은 1분 10회.
- AC#3 BE-34: presign은 사용자 기준 PENDING 20개(초과 시 409), 1시간 60회(초과 시 429).
- AC#4 BE-25: SMS는 허용 국가(app.sms.allowed-regions, 기본 KR)의 MOBILE·FIXED_LINE_OR_MOBILE 번호만 보낸다. 그 외 번호는 한도를 쓰기 전에 400 phone_not_allowed(B3).
독립 리뷰 반영: (계정, IP) 키로 계정 잠금 DoS를 줄였다. 슬라이딩 창은 유지하고 시간에 나눈 실패 테스트로 동작을 고정했다. release 실패는 WARN만 남긴다. 동시 요청 테스트, 시계 차이·처리 중 시도 집계 한계를 문서화했다.
검증: make test exit 0(backend 687, repo 217, deploy 135, terraform 9개 모듈, frontend 160).
이월: rate_limit_events 정리는 TASK-27 AC#5로 넘겼다. 진짜 15분 고정 차단은 필요하면 따로 진행한다. presign 동시성 테스트는 잠금을 꺼도 경쟁이 재현되지 않아 회귀를 잡는 힘이 약하다.
<!-- SECTION:FINAL_SUMMARY:END -->
