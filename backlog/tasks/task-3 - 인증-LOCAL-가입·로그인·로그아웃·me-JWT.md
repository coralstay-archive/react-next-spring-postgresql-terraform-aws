---
id: TASK-3
title: '인증: LOCAL 가입·로그인·로그아웃·me + JWT'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 14:10'
labels:
  - backend
  - auth
dependencies:
  - TASK-2
references:
  - decision-4
  - decision-5
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
이메일/비밀번호 로그인과 JWT 발급·refresh 로테이션을 구현한다.

## 비즈니스 규칙
- B1 (AC #1) 같은 이메일의 LOCAL identity는 하나만 존재
- B2 (AC #2) 로그인 실패 메시지는 계정 존재 여부를 드러내지 않는다
- B3 (AC #5) 이미 사용된 refresh 토큰 재사용 시 해당 family 전체 폐기 + 401
- B4 (AC #2) 비밀번호는 BCrypt(Delegating)로만 저장
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 POST /api/auth/signup (이메일 형식, 비밀번호 정책, 닉네임 검증)
- [x] #2 POST /api/auth/login → 세션 생성 / ?token=jwt면 access+refresh 발급
- [x] #3 POST /api/auth/logout (세션 무효화 / refresh 폐기)
- [x] #4 GET /api/auth/me
- [x] #5 POST /api/auth/token/refresh: 로테이션 + 재사용 감지 시 family 전체 폐기
- [x] #6 첫 ADMIN은 환경변수(ADMIN_EMAIL)로 지정된 LOCAL 계정
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
LOCAL 인증과 JWT를 구현했다. signup(검증, 409 email_taken, 동시 가입 유니크 위반도 409, 자동 로그인 안 함), login(세션: 세션 ID 변경, SecurityContextRepository 저장, CSRF 토큰 회전 / ?token=jwt: access 15분 + opaque refresh, SHA-256 해시만 저장, family 단위), logout(permitAll, 멱등 204, 세션 무효화 또는 refresh family 폐기, Suspended 필터 예외), me(DB 최신 값, providers 포함), token/refresh(FOR UPDATE 로테이션, 재사용 시 family 전체 폐기 후 401, 정지 계정은 403 + 폐기), ADMIN_EMAIL 부트스트랩(가입 시와 기동 시, LOCAL만). login?token=jwt와 refresh는 앰비언트 자격을 쓰지 않아 CSRF를 면제했다. 로그인 실패는 동일한 401 invalid_credentials와 더미 BCrypt로 처리. 검증: cleanTest 후 make test 통과(backend 145, 스킵 0). 후속(범위 밖): 만료된 Bearer 헤더를 단 refresh 호출은 리소스 서버가 401(커스텀 BearerTokenResolver로 해결 가능), 정지 사용자 세션으로는 다른 계정 로그인 불가(login도 Suspended 예외 후보), 로그인 레이트리밋 없음, 오래된 refresh_tokens 정리 없음.
<!-- SECTION:FINAL_SUMMARY:END -->
