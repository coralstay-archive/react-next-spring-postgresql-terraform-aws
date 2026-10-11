---
id: TASK-10
title: '게시판: 검색'
status: To Do
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
제목·본문 검색을 구현한다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 posts.search_tsv(tsvector) + GIN 인덱스, 갱신 트리거
- [ ] #2 pg_trgm ILIKE 보조(한국어 부분일치)
- [ ] #3 GET /api/posts?q= 필터 조합(category, tag)
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. **AC#1**: V7에서 `pg_trgm` 확장(이미지 postgres:18 기본 포함), `posts.search_tsv tsvector GENERATED ALWAYS AS (setweight(to_tsvector('simple', coalesce(title,'')),'A') || setweight(to_tsvector('simple', coalesce(content,'')),'B')) STORED` + GIN 인덱스.
   - 한국어 형태소 분석기가 없으므로 `simple` 구성을 쓴다. 트리거 대신 생성 컬럼으로 단순화한다(AC 문구의 "갱신 트리거"는 생성 컬럼으로 대체하고 카드 노트에 기록).
2. **AC#2**: `title`·`content`에 `gin_trgm_ops` 인덱스. 한국어 부분 일치(예: "스프링" → "스프링부트")는 ILIKE `%q%`로 처리한다(q는 2자 이상, `%`·`_` 이스케이프).
3. **AC#3**: `PostQuery.q` 추가. 조건은 `search_tsv @@ websearch_to_tsquery('simple', q) OR title ILIKE OR content ILIKE`. category·tag·sort와 조합한다. q가 있고 sort가 없으면 기존 최신순을 유지한다(랭킹 정렬은 `sort=relevance`로 선택 가능하게 하되 범위를 넘으면 생략).
   - Specification에서 native 함수는 `cb.function` 또는 `@Query` native로 처리한다. 목록 N+1 방지 구조(fetchDetails)를 유지한다.
- 테스트: 영문 단어, 한글 부분 일치, 조합 필터, 특수문자 이스케이프, 숨김·삭제 글 제외 유지, EXPLAIN으로 인덱스 사용 여부 스모크(선택).

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->
