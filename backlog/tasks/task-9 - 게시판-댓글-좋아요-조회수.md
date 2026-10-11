---
id: TASK-9
title: '게시판: 댓글 + 좋아요 + 조회수'
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 18:19'
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

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. **AC#1 댓글**: V6 `comments(id, post_id FK, author_id FK, content 1–2000, created_at, updated_at, deleted_at)`, 인덱스 `(post_id, created_at, id)`.
   - `GET /api/posts/{id}/comments?page&size`(공개, `/posts/*/comments` GET을 permitAll에 추가), `POST /api/posts/{id}/comments`(인증), `DELETE /api/comments/{id}`(@ownership, soft delete, 목록에서는 "삭제된 댓글" 자리표시 또는 제외 중 하나로 정하고 README에 기록).
   - 글 가시성은 `PostService.canSee`·`ownerOf`를 재사용한다. 삭제·숨김 글에는 작성 불가(B3 → 404 post_not_found 또는 409).
2. **AC#2 좋아요**: V6에 `post_likes(post_id, user_id, created_at, PK)`, `posts.like_count int default 0`.
   - `POST /api/posts/{id}/like`(멱등, 200 + {liked, likeCount}), `DELETE`(멱등). `INSERT ... ON CONFLICT DO NOTHING`의 영향 행 수로만 카운터를 조정한다(B1, 동시 요청 테스트).
   - 상세와 목록 응답에 `likeCount`, 상세에는 `likedByMe`를 추가한다.
3. **AC#3 조회수**: `posts.view_count bigint default 0`, `post_views(post_id, viewer_key, viewed_at, PK(post_id, viewer_key))`.
   - viewer_key는 로그인 사용자면 `u:<id>`, 익명이면 `s:<세션 또는 익명 쿠키 해시>`. 익명 세션을 만들지 않으려고 짧은 `VID` 쿠키(HttpOnly, 30일)를 쓴다.
   - 30분 내 재조회는 집계하지 않는다(B2): upsert의 `WHERE viewed_at < now()-30m` 조건으로 증가 여부를 결정한다. Clock 빈 사용.
   - GET 상세에서 증가시키므로 읽기 트랜잭션과 분리한다(REQUIRES_NEW 또는 별도 서비스).
4. **AC#4 인기순**: `PostSort.POPULAR` = `like_count desc, created_at desc, id desc`, 부분 인덱스 추가. `sort=popular` 파싱.
- 테스트: 댓글 CRUD·권한·삭제 글 차단, 좋아요 동시성(20건 → count 정확), 조회수 30분 경계(MutableClock), 인기순 정렬, 보안 매트릭스(익명 GET 200, 쓰기는 CSRF·suspended·step-up).
- 위험: 조회수 테이블 증가 → 오래된 post_views 정리 작업은 후속(README에 기록).

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->
