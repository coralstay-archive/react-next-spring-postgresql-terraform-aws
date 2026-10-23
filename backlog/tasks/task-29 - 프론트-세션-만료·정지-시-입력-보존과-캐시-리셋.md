---
id: TASK-29
title: '프론트: 세션 만료·정지 시 입력 보존과 캐시 리셋'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 07:27'
labels:
  - frontend
  - auth
dependencies:
  - TASK-28
references:
  - decision-5
  - decision-10
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-5, FE-6, FE-9, FE-15, FE-32, FE-10, FE-11, FE-13, FE-14. TASK-18의 두 UI 이슈 중 정지 댓글 유실의 근본 원인.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
- PRG: 폼 제출 성공 시 결과 페이지로 replace 이동, 새로고침·뒤로가기 재제출과 이중 제출을 차단한다.

## 비즈니스 규칙
- B1 (AC #3) 세션이 끝나도 사용자가 입력한 글·댓글 내용은 사라지지 않는다
- B2 (AC #1) 로그아웃 상태에서는 수정·삭제 버튼이 보이지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-5 세션 만료 401이면 me 외 모든 쿼리 리셋 + 전역 '세션이 만료되었습니다' 안내
- [ ] #2 FE-6 포커스·주기적으로 me 재조회
- [ ] #3 FE-9·FE-32 401이 나도 작성 중 화면을 언마운트하지 않고 인라인 재로그인 안내 + 초안 sessionStorage 보존
- [ ] #4 FE-15 댓글 401 시 폼과 입력·오류 유지
- [ ] #5 FE-10 로그아웃은 성공(또는 401)일 때만 상태 해제, 실패하면 오류 표시
- [ ] #6 FE-11 me 로딩 실패 시 헤더 오류 상태와 다시 시도 버튼
- [ ] #7 FE-13 콜백 실패 시 저장된 next 정리, 돌아가기 링크에 next 유지
- [ ] #8 FE-14 가입 성공 후 로그인 실패 시 폼 대신 로그인 링크(재제출 방지)
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (TASK-29)

### 401 분류 (공통 기준)
- "세션 만료" = ApiError 401 + code `unauthorized`(`isSessionExpired`). 401 `invalid_credentials`·`invalid_otp`·`http_401`(계약 밖)은 세션과 무관.
- me 자체는 fetchMe 가 401 을 null 로 바꾸므로 오류가 아니다. 대신 me 가 "로그인(Me) → null"로 바뀐 것을 만료로 본다(포커스·주기 재조회로 감지). 로그아웃·로그인 성공(setQueryData 수동 갱신)은 만료가 아니다.
- 만료 처리는 "직전 me 가 Me 였을 때"만 한다(익명·로딩 중 401 은 me=null 만). 리셋 뒤 재조회가 또 401 이어도 me 가 이미 null 이라 반복하지 않는다(무한 루프 방지).

1. **AC#1 FE-5** `lib/query.ts`: 만료 시 me=null, `["auth", …]` 밖의 모든 쿼리 `resetQueries`(데이터를 버리고 화면에 떠 있는 것은 익명으로 다시 받음 → 서버 editable=false 로 수정·삭제 버튼이 사라짐, B2), 전역 플래그 `["auth","sessionExpired"]`=true. 레이아웃에 `SessionExpiredBanner`("세션이 만료되었습니다" + 다시 로그인 링크(next=현재 경로) + 닫기). 다시 로그인(me 가 Me 로)하면 플래그를 내린다. 테스트: 댓글 삭제 401 → 버튼 사라짐(B2), 배너, me 쿼리는 리셋 안 됨, 익명 401·invalid_credentials 는 리셋·배너 없음.
2. **AC#2 FE-6** `useMe`: `refetchOnWindowFocus: true`(기본 staleTime 30초 안의 포커스는 생략), `refetchInterval: 5분`, `refetchIntervalInBackground: false`(유저 결정). 재조회 결과가 null 이면 1번 만료 처리.
3. **AC#3 FE-9·FE-32 (B1)**
   - `useSessionUser()`: 컴포넌트가 본 마지막 Me 를 기억해, 세션이 끝나면 `expired=true` + 마지막 사용자를 준다.
   - `RequireAuth`: 처음부터 익명이면 지금처럼 로그인으로 replace. 내용을 한 번 그린 뒤 세션이 끝나면 언마운트하지 않고 내용 위에 인라인 재로그인 안내(`loginHref(현재 경로)`, safeNext 를 거치는 next). `RequireAdmin` 의 ADMIN 판정도 마지막 사용자 기준이라 관리 다이얼로그·사유가 남는다(FE-32 의 401 부분).
   - 수정 화면은 리셋 뒤 익명 재조회가 editable=false 라도 처음 받은 editable 글로 편집기를 유지한다.
   - 초안 `lib/drafts.ts`(sessionStorage, 탭 단위): 키 `draft:post:new`, `draft:post:edit:<id>`, `draft:comment:<postId>`. 값 `{v:1, userId, savedAt, data}` — 다른 사용자로 다시 로그인하면 복원하지 않는다. 입력이 바뀔 때마다 저장(초기값과 같거나 비면 지움), 직렬화 20만 자 초과·저장소 오류면 저장하지 않음(입력은 화면에 남음), 초안은 최대 20개(오래된 것부터 지움). 등록·저장 성공과 명시적 로그아웃 성공 때 지운다. 다시 마운트되면 복원하고 "임시 저장된 초안을 불러왔습니다 / 초안 버리기"를 보인다. 글 초안은 제목·본문·카테고리·태그·완료된 첨부.
4. **AC#4 FE-15** 댓글 폼: 세션이 끝나도(401) 폼·입력·오류(ApiErrorAlert + 로그인 링크)를 유지하고 초안 저장. 403 `suspended`(세션 삭제 실패 시)도 폼·입력 유지 + 오류.
5. **AC#5 FE-10** `useLogout`: 성공 또는 401 이면 세션 해제(+초안 삭제) 후 `/` 로, 그 밖의 실패(네트워크·5xx·403)는 헤더에 오류를 보이고 me·캐시를 그대로 둔다. 로그아웃 401 은 만료 배너를 띄우지 않는다(mutation meta).
6. **AC#6 FE-11** 헤더: me 데이터가 없고 오류면 "사용자 정보를 불러오지 못했습니다" + 다시 시도(refetch). 데이터가 있는 상태의 재조회 실패는 기존 표시 유지. RequireAuth 의 오류 화면에도 다시 시도.
7. **AC#7 FE-13** 콜백 실패(?error= 또는 me 확인 실패) 시 sessionStorage `auth.next` 를 읽고 지운다. "로그인으로 돌아가기" 링크는 `loginHref(safeNext(next))`.
8. **AC#8 FE-14** 가입 201 뒤 자동 로그인 실패면 폼 대신 "가입은 완료됐습니다" + 로그인 링크(`loginHref(safeNext(next))`)를 보여 재제출(409)을 막는다.
- **PRG**: 가입 성공은 이미 replace 이동이고 #8 로 재제출을 막는다. 가입·댓글은 ref 로 이중 제출(같은 틱의 두 번째 submit)을 막는다. 글 작성·수정의 replace 이동·성공 후 버튼 비활성 유지는 TASK-31 AC#2(FE-29·FE-21) 범위라 여기서 건드리지 않는다(경계: TASK-29 는 초안 저장·만료 시 유지까지).
- 문서: docs/frontend.md 에 세션 만료·초안·로그아웃·me 재조회 규칙.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
착수 결정(2026-10-23, 유저): me 재조회는 창 포커스 때 + 탭이 보일 때 5분마다.

독립 리뷰(2026-10-23) 반영 결정(유저): (1) 계정이 A→B로 바뀌면 배너 없이 캐시 리셋, 작성 화면을 빈 상태로 다시 마운트하고 그 탭의 A 초안은 지운다(A 글이 B 이름으로 등록되던 문제). (2) 작성 화면(RequireAuth 내용 유지 중)에서는 하단 고정 안내 하나만 — 상단 배너와 오류 속 로그인 링크는 숨김, 다른 화면은 상단 배너. (3) 수정 초안은 저장 시 글의 수정 시각을 함께 기억하고, 복원 때 서버 글이 더 새로우면 경고(덮어쓸지는 사용자). (4) Next.js 보안 업그레이드는 TASK-35 AC로. 그 밖에 로그아웃 직후 잘못된 '임시 저장' 안내, 저장소 용량 초과 시 옛 초안 복원·prune 순서·저장 디바운스, vitest 타임아웃 안정화.
<!-- SECTION:NOTES:END -->
