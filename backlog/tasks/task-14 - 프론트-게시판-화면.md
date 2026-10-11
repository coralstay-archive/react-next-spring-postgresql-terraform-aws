---
id: TASK-14
title: '프론트: 게시판 화면'
status: In Progress
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 19:56'
labels:
  - frontend
  - board
dependencies:
  - TASK-13
  - TASK-9
  - TASK-10
  - TASK-11
references:
  - decision-10
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
게시판 UI를 만든다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 목록(카테고리·태그·검색·정렬·페이징)
- [ ] #2 상세(/posts/view?id=): 본문, 이미지, 좋아요, 댓글
- [ ] #3 작성·수정(태그, 이미지 업로드)
- [ ] #4 작성자/ADMIN에게만 수정·삭제 버튼
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. **AC#1 목록** `/`(또는 `/posts/`): 카테고리 탭(`/api/categories`), 태그 칩, 검색창(q), 정렬(latest, popular), 페이지네이션. 상태는 URL 쿼리와 동기화한다(공유 가능).
2. **AC#2 상세** `/posts/view/?id=`: 본문(줄바꿈 보존, XSS 방지를 위해 HTML 렌더 없이 텍스트로), 이미지 첨부 갤러리, 좋아요 토글(낙관적 업데이트 + 롤백), 조회수, 댓글 목록, 작성, 삭제.
   - deleted·hidden 플래그는 ADMIN에게 배지로 보여준다.
3. **AC#3 작성·수정** `/posts/new/`, `/posts/edit/?id=`: 제목·본문·카테고리(adminOnly는 ADMIN에게만 표시)·태그 입력(정규화 미리보기, 최대 5개).
   - 이미지 업로드: presign → S3 PUT(진행률) → confirm → attachmentIds. 크기와 타입을 클라이언트에서 먼저 검증한다.
4. **AC#4 권한 UI**: `editable`이 true일 때만 수정·삭제 버튼. 서버 403·404 처리, step-up 필요 시 배너.
- 테스트: MSW로 목록 필터 쿼리 생성, 좋아요 낙관적 업데이트, 업로드 흐름(PUT 모킹), 권한 버튼 노출.
- Playwright E2E 스모크(TASK-18 AC와 연계)를 위한 `data-testid`를 붙인다.

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->
