---
id: DRAFT-21
title: '[AWS] CI/CD 견고성'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - ci
  - aws
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: OPS-29, OPS-30, OPS-31, OPS-32, OPS-33. OPS-29·OPS-31·OPS-33은 AWS 없이 진행 가능.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 OPS-29 AWS 변수가 없으면 배포 job 건너뜀 + 변수 6개 사전 검증
- [ ] #2 OPS-30 arm 러너 가용성 확인과 대안
- [ ] #3 OPS-31 actionlint 다운로드 후 체크섬 검증
- [ ] #4 OPS-32 레지스트리 미러·로그인
- [ ] #5 OPS-33 buildx 캐시
<!-- AC:END -->
