---
id: DRAFT-2
title: '백엔드: 오류 응답 계약 통일'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - backend
  - errors
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-1, BE-2, BE-5, BE-6, BE-41.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 모든 오류 응답 본문은 status·error·message를 가지며 스택트레이스나 내부 메시지를 노출하지 않는다
- B2 (AC #2) 사용자 입력 때문에 500이 나지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-1 공통 처리기: 405·415·406·404·500을 모두 {status,error,message} 모양과 snake_case 코드로(500은 internal_error + 서버 로그)
- [ ] #2 BE-2 문자열 입력의 NUL·제어문자를 400 validation으로 거부, 남은 DataIntegrityViolation은 409/400으로 매핑
- [ ] #3 BE-5 알 수 없는 JSON 필드는 400 거부(또는 정책 문서화), 본문 타입 오류에 fields 포함
- [ ] #4 BE-6 파라미터 오류 코드 통일(validation), enum 대소문자 무시, docs/api.md 갱신
- [ ] #5 BE-41 카테고리 저장 시 slug 유니크 위반만 slug_taken, 나머지는 원래 매핑
<!-- AC:END -->
