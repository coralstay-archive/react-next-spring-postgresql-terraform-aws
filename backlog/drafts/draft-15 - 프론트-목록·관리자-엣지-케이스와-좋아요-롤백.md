---
id: DRAFT-15
title: '프론트: 목록·관리자 엣지 케이스와 좋아요 롤백'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - frontend
  - board
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-20, FE-22, FE-33.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-20 좋아요 롤백은 해당 필드만 함수형 업데이트, 404면 상세 무효화
- [ ] #2 FE-22 page 클램프(int, totalPages), 목록 오류에 첫 페이지 링크, 잘못된 q면 '검색어 무시' 표시, 카테고리·태그 오류 표시
- [ ] #3 FE-33 관리자 페이저 범위 클램프
<!-- AC:END -->
