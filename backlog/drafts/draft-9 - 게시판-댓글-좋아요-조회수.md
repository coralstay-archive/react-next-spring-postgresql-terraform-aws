---
id: DRAFT-9
title: '게시판: 댓글 + 좋아요 + 조회수'
status: Draft
assignee: []
created_date: '2026-10-11 13:03'
labels:
  - backend
  - board
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
상호작용 기능을 구현한다.

## 비즈니스 규칙
- B1 (AC #2) 사용자당 글 하나에 좋아요 1회(유니크), like_count 정합성 유지
- B2 (AC #3) 같은 사용자/세션의 조회는 30분 내 1회만 집계
- B3 (AC #1) 삭제된 글에는 댓글 작성 불가
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 댓글 목록·작성·삭제(작성자|ADMIN)
- [ ] #2 POST/DELETE /api/posts/{id}/like
- [ ] #3 GET /api/posts/{id} 조회수 증가
- [ ] #4 sort=popular(좋아요 기준)
<!-- AC:END -->
