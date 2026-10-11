---
id: TASK-5
title: '인증: Naver·Kakao (비표준 매퍼)'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 14:49'
labels:
  - backend
  - auth
dependencies:
  - TASK-4
references:
  - decision-4
  - decision-8
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
국내 제공자를 OAuth2UserInfo 매퍼로 추가한다.

## 비즈니스 규칙
- B1 (AC #2) 이메일 미제공 시에도 계정 생성 가능(email null)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Naver provider 설정 + response 래핑 응답 매퍼
- [x] #2 Kakao provider 설정 + kakao_account 매퍼(이메일 미동의 허용)
- [x] #3 WireMock 기반 매퍼·로그인 테스트
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Naver·Kakao를 OAuth2UserInfoMapper @Component로 추가했다. 둘 다 client_secret_post. Naver: userNameAttribute=response, subject=response.id, 이메일이 있으면 verified로 취급(검증 필드 없음, 병합 키 아님). Kakao: numeric id를 문자열 subject로, is_email_valid와 is_email_verified가 모두 true일 때만 이메일 사용, scope는 콤마로 미리 합친 단일 문자열. 이메일 미동의여도 email null로 계정 생성(B1). WireMock 전체 흐름 테스트(로그인, 재로그인, 연결, identity_in_use, 토큰·userinfo 오류, access_denied). 검증: cleanTest 후 make test 통과(backend 221, 스킵 0). 미검증 리스크: Naver 토큰 요청의 state 파라미터 필요 여부(실제 로그인으로 확인 필요), Kakao 콘솔 동의 항목 미설정 시 KOE205. TASK-18: NAVER_*·KAKAO_* 시크릿을 SSM에 넣고 콘솔에 콜백 등록.
<!-- SECTION:FINAL_SUMMARY:END -->
