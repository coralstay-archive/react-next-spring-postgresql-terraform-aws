---
id: TASK-19
title: 'CI 녹색화: 구버전 shellcheck 경고 수정 반영'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 05:07'
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
- [x] #4 앱 시계(Clock 빈·테스트 MutableClock)를 마이크로초로 절삭해 Linux 나노초 시계에서 응답 시각과 DB 저장 시각이 어긋나지 않게(PR #1 CI의 AdminPostApiTest 실패)
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR #1 CI에서 AdminPostApiTest(TASK-12#2) 실패: 같은 글을 두 번 hide 했을 때 hiddenAt이 다름. 원인은 Linux Clock.systemUTC() 나노초 정밀도 vs PostgreSQL timestamptz 마이크로초. 첫 응답은 메모리 나노초, 재조회는 DB 마이크로초. macOS는 마이크로초 시계라 로컬 재현 불가. AC#4로 다시 열어 처리. Instant.now()를 쓰는 엔티티 시각 통일은 TASK-23(BE-7) 범위.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
CI 녹색화. (1) SC2015 수정(8e4d8d0). (2) CI shellcheck를 로컬과 같은 v0.11.0으로 고정(sha256 검증, --retry 3). deploy_test.sh가 로컬 버전을 ci.yml 고정값과 비교(불일치나 미검출이면 REQUIRE_LINTERS=1에서 실패). (3) PR #1 CI에서 드러난 Linux 나노초 시계 문제: AdminPostApiTest(TASK-12#2)에서 같은 글을 두 번 hide 하면 hiddenAt이 달랐다(메모리 나노초 vs timestamptz 마이크로초, macOS는 재현 불가). Clock 빈을 Clock.tick(1µs)로, 테스트 @Primary MutableClock을 truncatedTo(MICROS)로 바꿨다(CI 수정의 핵심은 MutableClock). 진행: Plan → Red(repo_test 3건 실패, ClockConfigurationTest 컴파일 실패) → Green → 독립 리뷰 2회(중대 사항 없음, 경미 3건 refactor 반영, 남은 Instant.now() 지점은 노출 없음으로 TASK-23에 우선순위 기록) → Refactor. 검증: 로컬 cleanTest 후 REQUIRE_LINTERS=1 make test 통과. PR #1 CI 녹색 확인 후 병합(B1).
<!-- SECTION:FINAL_SUMMARY:END -->
