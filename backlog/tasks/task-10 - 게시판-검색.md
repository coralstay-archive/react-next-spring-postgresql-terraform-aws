---
id: TASK-10
title: '게시판: 검색'
status: To Do
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:19'
labels:
  - backend
  - board
dependencies:
  - TASK-8
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
제목·본문 검색을 구현한다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 posts.search_tsv(tsvector) + GIN 인덱스, 갱신 트리거
- [ ] #2 pg_trgm ILIKE 보조(한국어 부분일치)
- [ ] #3 GET /api/posts?q= 필터 조합(category, tag)
<!-- AC:END -->
