---
id: DRAFT-5
title: '인증: Naver·Kakao (비표준 매퍼)'
status: Draft
assignee: []
created_date: '2026-10-11 13:03'
labels:
  - backend
  - auth
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
국내 제공자를 OAuth2UserInfo 매퍼로 추가한다.

## 비즈니스 규칙
- B1 (AC #2) 이메일 미제공 시에도 계정 생성 가능(email null)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Naver provider 설정 + response 래핑 응답 매퍼
- [ ] #2 Kakao provider 설정 + kakao_account 매퍼(이메일 미동의 허용)
- [ ] #3 WireMock 기반 매퍼·로그인 테스트
<!-- AC:END -->
