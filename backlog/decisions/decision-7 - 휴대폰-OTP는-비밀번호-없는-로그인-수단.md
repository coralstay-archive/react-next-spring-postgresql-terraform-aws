---
id: decision-7
title: 휴대폰 OTP는 비밀번호 없는 로그인 수단
date: '2026-10-11 13:01'
status: accepted
---
## Context

휴대폰 인증을 어떤 용도로 쓸지 정해야 했다.

## Decision

번호 + OTP를 비밀번호 없는 로그인·가입 수단(provider=PHONE)으로 쓴다. SmsSender 인터페이스를 둔다(prod는 End User Messaging, local과 test는 콘솔/메모리 구현).

## Consequences

번호 재활용에 따른 탈취 위험이 있다 → 다른 identity가 있는 계정이면 추가 확인을 한다. 발송 레이트리밋, 시도 5회, 만료 3분, 해시 저장. 한국행 SMS 등록이 리스크다.
