---
id: DRAFT-13
title: '프론트: 기반 + 인증 화면'
status: Draft
assignee: []
created_date: '2026-10-11 13:03'
labels:
  - frontend
  - auth
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
API 클라이언트와 모든 로그인 수단 화면을 만든다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 fetch 래퍼(XSRF 헤더, 401 처리) + TanStack Query
- [ ] #2 레이아웃·헤더·useMe 가드
- [ ] #3 로그인 화면: LOCAL, 소셜 5종 버튼, 휴대폰 OTP
- [ ] #4 가입 화면
- [ ] #5 내 계정: 연결된 수단 목록·연결·해제
- [ ] #6 next dev에서 /api rewrite로 로컬 백엔드 연동
<!-- AC:END -->
