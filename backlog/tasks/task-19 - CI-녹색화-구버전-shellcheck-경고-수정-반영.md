---
id: TASK-19
title: 'CI 녹색화: 구버전 shellcheck 경고 수정 반영'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 04:55'
labels:
  - ci
dependencies: []
references:
  - decision-1
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
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
- [x] #1 8e4d8d0 수정을 이 태스크 브랜치에 반영하고 PR로 main에 병합
- [x] #2 PR CI(ci.yml) 녹색 확인
- [x] #3 로컬 shellcheck 버전과 CI 버전 차이를 막는 방법(버전 고정 또는 -S 수준 통일) 적용
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. AC#1: 8e4d8d0(SC2015 수정)은 이 브랜치의 기반(task/TASK-18 끝)에 이미 있다. PR로 main에 병합해 반영한다.
2. AC#3 (red → green):
   - red: scripts/test/repo_test.sh에 TASK-19#3 검사 추가. ci.yml이 shellcheck 버전을 SHELLCHECK_VERSION으로 고정하고 sha256으로 검증하는지, deploy_test.sh가 로컬 shellcheck 버전을 그 값과 비교하는지 확인한다. 실행해서 실패를 확인하고 기록한다(커밋 훅이 실패 커밋을 막으므로 red는 실행 로그로 남긴다).
   - green: ci.yml에 shellcheck v0.11.0 고정 설치 스텝(릴리스 tarball + sha256). deploy_test.sh는 버전이 다르면 경고하고, REQUIRE_LINTERS=1이면 실패.
   - refactor: 필요 시 정리.
3. AC#2: Done → push → PR → CI 녹색 확인 → 병합.
4. Review: 독립 서브에이전트가 diff를 리뷰하고 지적을 반영한다.
<!-- SECTION:PLAN:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
CI 녹색화. SC2015 수정(8e4d8d0)을 반영하고, CI shellcheck를 로컬과 같은 v0.11.0으로 고정했다(릴리스 tarball sha256 검증, --retry 3, GITHUB_PATH 앞에 두어 Ubuntu 패키지보다 우선). deploy_test.sh는 로컬 버전을 ci.yml 고정값과 비교한다(다르면 경고, REQUIRE_LINTERS=1이면 실패, 고정값을 읽지 못해도 실패). repo_test에 TASK-19#1~#3과 #B1 검사를 추가. 진행: Plan(카드 계획) → Red(repo_test 3건 실패 확인, 커밋 훅이 실패 커밋을 막아 실행 로그로 기록) → Green 커밋 → 독립 서브에이전트 리뷰(중대 사항 없음, 경미 3건) → Refactor 커밋으로 반영. 검증: cleanTest 후 REQUIRE_LINTERS=1 make test 통과(repo 207, deploy 133, backend, frontend 160). AC#1·#2의 PR CI 녹색은 push 후 PR에서 확인하고 병합한다(B1).
<!-- SECTION:FINAL_SUMMARY:END -->
