---
id: DRAFT-1
title: 'CI 녹색화: 구버전 shellcheck 경고 수정 반영'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - ci
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
첫 GitHub Actions 실행이 deploy_test.sh의 SC2015(Ubuntu shellcheck) 때문에 실패했다. 수정 커밋(8e4d8d0)이 task/TASK-18에 있으므로 이 태스크 브랜치로 옮겨 PR로 반영한다.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #2) main에 들어가는 모든 변경은 PR CI 녹색을 거친다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 8e4d8d0 수정을 이 태스크 브랜치에 반영하고 PR로 main에 병합
- [ ] #2 PR CI(ci.yml) 녹색 확인
- [ ] #3 로컬 shellcheck 버전과 CI 버전 차이를 막는 방법(버전 고정 또는 -S 수준 통일) 적용
<!-- AC:END -->
