---
id: TASK-12
title: 관리자 API
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 19:34'
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
- [x] #1 GET /api/admin/users, 정지/해제
- [x] #2 글 숨김/복구
- [x] #3 카테고리 생성·수정·삭제
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. **AC#1 사용자 관리**: `GET /api/admin/users?q&status&page`(닉네임·이메일·전화 부분 검색, providers 포함), `POST /api/admin/users/{id}/suspend`, `POST .../unsuspend`.
   - 정지하면 그 사용자의 refresh family 전체를 폐기하고, Spring Session의 해당 principal 세션도 삭제한다(`FindByIndexNameSessionRepository` + principal name 인덱스. 현재 SessionLogin이 principal name을 무엇으로 저장하는지 확인 후 맞춘다).
   - B1: `/api/admin/**`는 `hasRole('ADMIN')`이고, 비ADMIN은 403(보안 체인 matcher로 처리). B2: 자기 자신은 정지할 수 없다(409 cannot_suspend_self). 다른 ADMIN 정지 허용 여부도 정해서 문서화한다(제안: 허용하지 않음).
2. **AC#2 글 숨김·복구**: `POST /api/admin/posts/{id}/hide` / `unhide`(hidden_at 설정·해제), `POST .../restore`(deleted_at 해제). 감사 로그 테이블 `admin_actions(id, admin_id, action, target_type, target_id, reason, created_at)`를 V9에서 만들고 모든 관리 액션을 기록한다.
3. **AC#3 카테고리 CRUD**: `POST/PATCH/DELETE /api/admin/categories`(slug 형식 검증, 중복 409, admin_only, sort_order). 삭제 시 글은 FK SET NULL로 분류 없음이 된다.
   - 공개 `/categories` 응답은 그대로 둔다.
- 역할 변경(ADMIN 승격·강등)은 범위 밖이다(ADMIN_EMAIL 부트스트랩만 사용).
- 테스트: 권한 매트릭스, 정지 즉시 효과(기존 세션 401, refresh 거부), 자기 정지 금지, 숨김·복구 가시성, 감사 로그 기록.

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
관리자 API를 구현했다. /api/admin/**는 체인에서 hasRole ADMIN(익명 401, USER·정지 403, B1). ADMIN 쓰기에도 CSRF·suspended·step-up을 그대로 적용한다. 사용자: 목록(q는 닉네임·이메일·전화 숫자 매칭, providers 일괄 로딩), suspend·unsuspend(멱등). 정지는 사용자 행을 잠그고 SUSPENDED로 바꾼 뒤 refresh 전부 폐기와 감사 기록을 한 트랜잭션에서 하고, 커밋 후 findByPrincipalName(userId)로 세션을 삭제한다. 세션 인덱스는 AuthUserAuthentication.getName()=userId라 변경 불필요(테스트로 확인). 자기 정지 409 cannot_suspend_self, 다른 ADMIN 409 cannot_suspend_admin(B2). 글: hide·unhide·restore(멱등). 카테고리 CRUD(slug 규칙, 409 slug_taken, 삭제 시 FK SET NULL). V10 admin_actions 감사 로그(MANDATORY 전파), GET /api/admin/actions. 검증: cleanTest 후 make test 통과(backend 567, 스킵 0). 후속(범위 밖): 정지 커밋 직전에 진행 중이던 로그인의 세션이 남는 경쟁(읽기만 가능), 역할 변경 API가 생기면 stale role 문제, sortOrder 범위 제한 없음.
<!-- SECTION:FINAL_SUMMARY:END -->
