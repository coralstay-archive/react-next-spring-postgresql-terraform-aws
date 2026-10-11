---
id: TASK-14
title: '프론트: 게시판 화면'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 20:17'
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
- [x] #1 목록(카테고리·태그·검색·정렬·페이징)
- [x] #2 상세(/posts/view?id=): 본문, 이미지, 좋아요, 댓글
- [x] #3 작성·수정(태그, 이미지 업로드)
- [x] #4 작성자/ADMIN에게만 수정·삭제 버튼
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

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
게시판 화면을 구현했다. /는 목록(카테고리 탭, 인기 태그, 검색 2-100자 서버 규칙과 동일, 정렬, 페이지)이고 상태 전체를 URL 쿼리에 동기화(page는 1부터, ADMIN은 includeHidden 토글). /posts/view/?id=: 본문은 pre-wrap 텍스트로 HTML 미해석, 이미지 갤러리, 조회수, 좋아요(낙관적 업데이트와 롤백, 익명은 로그인으로), 댓글(삭제 자리표시, editable일 때만 삭제). /posts/new/와 /posts/edit/?id=(RequireAuth): 카테고리(adminOnly는 ADMIN만), 서버 TagNames와 동일한 태그 정규화 미리보기와 최대 5개, 이미지 presign → XHR PUT(진행률) → confirm → attachmentIds. 수정·삭제 버튼은 editable일 때만, step_up_required 안내. RequireAuth의 next에 쿼리를 포함하도록 변경(Suspense 필요). 오류 코드 한국어 맵 확장, data-testid는 README에 정리. 검증: make test(frontend 132, 백엔드는 변경 없음), tsc·lint·build(정적 라우트 9개) 통과, 서브에이전트가 실제 백엔드·MinIO·next dev 프록시로 가입·업로드·글·좋아요·댓글 흐름을 확인. 후속: TASK-15 관리자 페이지도 Suspense 필수, 글 숨김·복구 액션은 TASK-15. TASK-18 Playwright는 window.confirm 다이얼로그 처리 필요. 브라우저 시각 확인은 미실시.
<!-- SECTION:FINAL_SUMMARY:END -->
