---
id: TASK-15
title: '프론트: 관리자 화면'
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 20:17'
labels:
  - frontend
  - admin
dependencies:
  - TASK-13
  - TASK-12
references:
  - decision-10
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
관리자 UI를 만든다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 /admin: 유저 정지/해제, 글 숨김/복구, 카테고리 관리
- [ ] #2 ADMIN 아니면 접근 차단 화면
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. **AC#1** `/admin/`(탭): 사용자(검색, 상태 필터, 정지·해제 + 확인 다이얼로그 + 사유), 글(includeHidden 목록, 숨김·복구·삭제 복구), 카테고리(생성·수정·삭제, 정렬 순서, adminOnly).
2. **AC#2**: `RequireAdmin` 가드. 비ADMIN에게는 403 안내 화면을 보여준다(실제 차단은 서버가 한다).
- 테스트: MSW로 탭별 액션 요청 형태와 가드 동작.

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->
