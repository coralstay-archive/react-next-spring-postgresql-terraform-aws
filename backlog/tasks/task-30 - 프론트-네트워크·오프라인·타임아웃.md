---
id: TASK-30
title: '프론트: 네트워크·오프라인·타임아웃'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 08:09'
labels:
  - frontend
dependencies:
  - TASK-28
references:
  - decision-10
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-4, FE-7, FE-8, FE-23.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 오프라인일 때 제출은 나중에 몰래 전송되지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-4 오프라인 배너, 뮤테이션 networkMode always로 즉시 network 오류
- [ ] #2 FE-7 CSRF 쿠키가 계속 없으면 cookies_blocked 전용 오류
- [ ] #3 FE-8 뮤테이션 요청 타임아웃(AbortSignal)
- [ ] #4 FE-23 재조회 실패 시 기존 데이터 유지 + 작은 오류 배너
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. (AC #1, FE-4) lib/query.ts: 뮤테이션 기본 networkMode 'always' — 오프라인이어도 일시정지하지 않고 바로 보내 network 오류로 끝낸다(B1: 나중에 몰래 전송 없음). 쿼리는 기본 'online'(오프라인이면 멈췄다가 복귀 때 다시 받음, 읽기라 안전) 유지. components/offline-banner.tsx: TanStack onlineManager(online/offline 이벤트) 구독, 오프라인이면 레이아웃 상단에 '오프라인입니다 …' 배너. 테스트 유틸 renderWithQuery 가 기본 옵션을 덮어쓰지 않고 합치게 고친다. 테스트: 오프라인 제출 → 즉시 오류, 복귀 뒤 요청이 다시 가지 않음(B1), 배너 표시·해제.
2. (AC #2, FE-7) lib/api.ts csrfToken(): GET /api/auth/csrf 뒤에도 XSRF-TOKEN 쿠키가 없으면 요청을 보내지 않고 ApiError 403 cookies_blocked. errors.ts 에 '쿠키를 허용해 주세요' 한국어 문장. 테스트: 쿠키를 심지 않는 csrf 응답 → cookies_blocked, 쓰기 요청 미전송, 403 csrf 재시도 루프 없음.
3. (AC #3, FE-8) lib/api.ts: 쓰기 메서드 요청마다 35초 타임아웃(MUTATION_TIMEOUT_MS, ApiOptions.timeoutMs 로 바꿀 수 있음). AbortController+타이머, 호출자 signal 은 abort 를 전달(AbortSignal.any 없이 한 경로). 시간 초과는 ApiError 0 timeout('처리됐는지 확인하지 못했습니다 …확인한 뒤 다시 시도'), 호출자 취소는 원래 AbortError 그대로. GET 과 S3 업로드(XHR)는 그대로. 테스트: 가짜 타이머로 35초 경과 → timeout, 34초엔 대기, 호출자 abort 구분, 글 등록 시간 초과 때 편집기·초안 유지(TASK-29).
4. (AC #4, FE-23) 재조회 실패 시 데이터가 있으면 그대로 두고 작은 RefetchErrorAlert(오류 문장 + 다시 시도)를 위에 붙인다. 데이터가 없을 때만 오류 화면. 적용: 목록, 상세(404 는 없는 글 안내 유지), 수정, 댓글, 관리자 탭 4개, 내 계정 로그인 수단. 상세는 세션 만료 리셋 뒤 재조회가 실패해도 마지막 글(익명 표시)과 댓글 폼을 유지(TASK-29 메모 사례). 테스트: 목록·상세·댓글 재조회 실패에 기존 데이터 + 배너 + 다시 시도, 리셋 뒤 실패 사례.
5. docs/frontend.md 에 오프라인·쿠키 차단·타임아웃·재조회 실패 절 추가. make test·tsc·eslint.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
착수 결정(2026-10-23, 유저): 뮤테이션 요청 타임아웃 35초(백엔드 30초 예산보다 길게). 시간 초과 시 '저장됐는지 확실하지 않으니 확인 후 다시 시도' 안내. S3 업로드는 TASK-31 범위.
<!-- SECTION:NOTES:END -->
