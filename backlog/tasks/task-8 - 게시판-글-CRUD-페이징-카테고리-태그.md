---
id: TASK-8
title: '게시판: 글 CRUD + 페이징 + 카테고리/태그'
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 15:26'
labels:
  - backend
  - board
dependencies:
  - TASK-2
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
게시글 기본 기능을 구현한다.

## 비즈니스 규칙
- B1 (AC #3) 수정·삭제는 작성자 또는 ADMIN만
- B2 (AC #2) 삭제·숨김 글은 목록·상세에서 제외(ADMIN 제외)
- B3 (AC #3) 태그는 이름 정규화(소문자, trim) 후 재사용, 글당 최대 5개
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Flyway: categories, posts(soft delete, hidden_at), tags, post_tags
- [ ] #2 GET /api/posts(category, tag, page, size, sort=latest) / GET /api/posts/{id}
- [ ] #3 POST/PATCH/DELETE /api/posts (작성자|ADMIN)
- [ ] #4 GET /api/categories, GET /api/tags
<!-- AC:END -->
