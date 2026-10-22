---
id: TASK-21
title: '백엔드: 운영 설정 fail-fast + 보안 기본값'
status: To Do
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 06:13'
labels:
  - backend
  - security
dependencies:
  - TASK-20
references:
  - decision-5
  - decision-7
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-8, BE-9, BE-14, BE-26, BE-12.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 운영에서 공개된 dev 비밀값으로는 앱이 기동하지 않는다
- B2 (AC #3) 가입만으로 ADMIN 권한을 얻을 수 없다
- B3 (AC #4) 운영 로그에 OTP 원문이 남지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-8 운영 모드(PUBLIC_BASE_URL이 https)에서 dev JWT_SECRET·OTP_HASH_KEY·COOKIE_SECURE=false·기본 DB 비밀번호면 기동 실패
- [ ] #2 BE-9 운영 모드에서 CLIENT_IP_HEADER 미설정이면 기동 실패, 헤더가 빠진 요청은 경고 로그
- [ ] #3 BE-14 ADMIN_EMAIL 자동 승격은 기동 시 이미 있는 계정에만 하고, 가입 시 자동 승격은 하지 않는다
- [ ] #4 BE-26 운영 모드에서 SMS_PROVIDER=console이면 기동 실패, console 출력은 번호 마스킹
- [ ] #5 BE-12 pg_trgm 확장 권한 요구사항을 문서화하고 기동 실패 메시지를 개선
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
TASK-20 리뷰 이월(2026-10-12): Hibernate SqlExceptionHelper가 JPA 경로의 SQL 오류를 자체 ERROR로 로깅하고, 여기에 DB 상세 'Key (email)=(...)'(개인정보)가 포함될 수 있다. 앱 처리기는 더 이상 메시지를 로깅하지 않는다. 운영 로그 개인정보 정책과 함께 이 로거 레벨이나 마스킹을 결정한다.
<!-- SECTION:NOTES:END -->
