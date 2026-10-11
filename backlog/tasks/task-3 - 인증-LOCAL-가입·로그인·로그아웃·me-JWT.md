---
id: TASK-3
title: '인증: LOCAL 가입·로그인·로그아웃·me + JWT'
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:48'
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
- [ ] #1 POST /api/auth/signup (이메일 형식, 비밀번호 정책, 닉네임 검증)
- [ ] #2 POST /api/auth/login → 세션 생성 / ?token=jwt면 access+refresh 발급
- [ ] #3 POST /api/auth/logout (세션 무효화 / refresh 폐기)
- [ ] #4 GET /api/auth/me
- [ ] #5 POST /api/auth/token/refresh: 로테이션 + 재사용 감지 시 family 전체 폐기
- [ ] #6 첫 ADMIN은 환경변수(ADMIN_EMAIL)로 지정된 LOCAL 계정
<!-- AC:END -->
