---
id: TASK-12
title: 관리자 API
status: To Do
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:19'
labels:
  - backend
  - admin
dependencies:
  - TASK-8
references:
  - decision-5
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
관리 기능을 구현한다.

## 비즈니스 규칙
- B1 (AC #1) ADMIN 외 /api/admin/** 접근 403
- B2 (AC #1) 자기 자신은 정지 불가
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 GET /api/admin/users, 정지/해제
- [ ] #2 글 숨김/복구
- [ ] #3 카테고리 생성·수정·삭제
<!-- AC:END -->
