---
id: TASK-8
title: '게시판: 글 CRUD + 페이징 + 카테고리/태그'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 18:17'
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
- [x] #1 Flyway: categories, posts(soft delete, hidden_at), tags, post_tags
- [x] #2 GET /api/posts(category, tag, page, size, sort=latest) / GET /api/posts/{id}
- [x] #3 POST/PATCH/DELETE /api/posts (작성자|ADMIN)
- [x] #4 GET /api/categories, GET /api/tags
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
게시글 CRUD, 페이징, 카테고리/태그를 구현했다. V5: categories(slug UNIQUE, sort_order, admin_only; notice는 ADMIN 전용, free 시드), posts(soft delete deleted_at, hidden_at, 가시 행 부분 인덱스), tags(name UNIQUE), post_tags. GET /api/posts(category, tag, page, size 1-50, sort=latest, ADMIN만 includeHidden), GET /api/posts/{id}(editable·deleted·hidden 플래그, 비가시 글은 404), POST/PATCH(부분 수정)/DELETE(soft). @PreAuthorize @ownership + @postService.ownerOf: 없거나 비가시면 404, 남의 글이면 403(B1). 삭제·숨김 글은 ADMIN 외 제외(B2). TagNames 정규화(NFC, trim, 공백 축약, 소문자, 30자), 글당 5개, ON CONFLICT upsert를 정렬 순서로 실행해 교착 없음(B3, 동시 8건 테스트). GET /api/categories, GET /api/tags(가시 글 기준 개수, q 접두어). 목록은 Specification 페이지 + fetchDetails 일괄 로딩(N+1 없음). 공개 GET을 permitAll에 추가. 검증: cleanTest 후 make test 통과(backend 399, 스킵 0). 후속: TASK-9는 V6 view/like 컬럼, PostSort.POPULAR, /posts/*/comments GET permitAll, 가시성은 PostService.canSee 재사용. TASK-10은 PostQuery.q와 search_tsv. TASK-12는 hidden_at/deleted_at 토글, 카테고리 CRUD(admin_only, sort_order). TASK-14 오류 코드 목록은 리포트 참고.
<!-- SECTION:FINAL_SUMMARY:END -->
