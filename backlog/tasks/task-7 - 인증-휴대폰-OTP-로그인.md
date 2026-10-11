---
id: TASK-7
title: '인증: 휴대폰 OTP 로그인'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 15:26'
labels:
  - backend
  - auth
dependencies:
  - TASK-3
references:
  - decision-7
  - decision-4
  - decision-5
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
번호+OTP 비밀번호 없는 로그인을 구현한다.

## 비즈니스 규칙
- B1 (AC #2) OTP는 3분 후 만료, 1회 사용 후 소멸
- B2 (AC #3) 검증 시도 5회 초과 시 해당 챌린지 폐기
- B3 (AC #4) 같은 번호로 1분 1회·하루 N회 이상 발송 불가(429)
- B4 (AC #5) 다른 identity가 있는 계정에 PHONE으로 로그인하면 기존 수단 재인증 전까지 제한 세션
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 SmsSender 인터페이스 + Console/InMemory/EndUserMessaging 구현(프로필 선택)
- [x] #2 POST /api/auth/phone/otp: OTP 생성(해시 저장, 만료 3분) + 발송
- [x] #3 POST /api/auth/phone/verify: 로그인/가입, 세션 또는 ?token=jwt
- [x] #4 번호·IP별 발송 레이트리밋
- [x] #5 재활용 번호 보호 규칙
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
휴대폰 OTP 비밀번호 없는 로그인을 구현했다. SmsSender는 console(기본)·memory(테스트)·aws(End User Messaging SendTextMessage) 중 설정으로 고른다. 번호는 libphonenumber로 E.164(기본 KR) 정규화. otp: 6자리 SecureRandom, HMAC-SHA256(OTP_HASH_KEY)만 저장(V3 otp_challenges), 202 응답으로 계정 존재 여부 비공개, 3분 만료, 1회 사용(B1). verify: FOR UPDATE로 시도 횟수 처리, 5회 실패 후 폐기(B2, 4회 실패 후 정답은 성공), 세션 또는 ?token=jwt, ?link=true 연결(409 identity_in_use). 레이트리밋: otp_challenges 기반 슬라이딩 윈도우 + advisory lock. 번호당 60초 1회·하루 10회, IP당 시간당 20회 → 429 + Retry-After(B3). ClientIpResolver는 CLIENT_IP_HEADER(CloudFront-Viewer-Address)만 신뢰하고 XFF는 읽지 않는다. 재활용 번호(B4): 비PHONE identity가 있는 계정에 PHONE으로 로그인하면 stepUpRequired 세션/JWT 클레임(refresh에도 유지, V4). StepUpRequiredWriteFilter가 쓰기를 403 step_up_required로 막고, 연결 시작도 차단. POST /api/auth/step-up/local 또는 소셜 재로그인으로 해제. 검증: cleanTest 후 make test 통과(backend 324, 스킵 0, 마커 9개 확인). 후속: TASK-16은 CloudFront-Viewer-Address 전달, SG를 CloudFront prefix list로 제한, sms-voice:SendTextMessage IAM, SSM에 OTP_HASH_KEY·SMS 설정. TASK-18은 샌드박스 해제·한국 발신 등록. TASK-13은 step-up UI와 Retry-After 카운트다운. otp_challenges와 refresh_tokens 정리 작업은 미구현.
<!-- SECTION:FINAL_SUMMARY:END -->
