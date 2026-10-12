---
id: DRAFT-14
title: '프론트: step-up·계정 화면 일관성'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - frontend
  - auth
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-16, FE-18, FE-31.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 로그인 수단 해제는 사용자 확인 없이 일어나지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-16 로그인 수단 해제 확인 다이얼로그(LOCAL이면 비밀번호 삭제 경고), 제한 세션에서 비활성
- [ ] #2 FE-18 OTP 5회 실패 시 재발송 안내
- [ ] #3 FE-31 제한 세션이면 작성 컨트롤을 미리 비활성 또는 안내, step-up 오류 표시를 단일 컴포넌트로
<!-- AC:END -->
