---
id: TASK-7
title: '인증: 휴대폰 OTP 로그인'
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 15:02'
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
- [ ] #1 SmsSender 인터페이스 + Console/InMemory/EndUserMessaging 구현(프로필 선택)
- [ ] #2 POST /api/auth/phone/otp: OTP 생성(해시 저장, 만료 3분) + 발송
- [ ] #3 POST /api/auth/phone/verify: 로그인/가입, 세션 또는 ?token=jwt
- [ ] #4 번호·IP별 발송 레이트리밋
- [ ] #5 재활용 번호 보호 규칙
<!-- AC:END -->
