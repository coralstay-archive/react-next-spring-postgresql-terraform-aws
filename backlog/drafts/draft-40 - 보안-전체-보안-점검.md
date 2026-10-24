---
id: DRAFT-40
title: '[보안] 전체 보안 점검'
status: Draft
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 03:46'
labels:
  - security
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
모든 플랫폼·프로토콜 태스크 후 프로젝트 전체를 다시 점검한다.
선행: 위 태스크 전부.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 프로토콜별 인증·CSRF·Origin·한도 매트릭스 문서
- [ ] #2 비밀값 흐름, 이미지 스캔(Trivy·SBOM), RBAC, 기본 deny NetworkPolicy 적용
- [ ] #3 Argo·Rollouts·CI 토큰 권한, 섀도우 쓰기 차단, SSRF, DoS 한도 확인
- [ ] #4 발견사항마다 심각도와 수정 태스크 연결, High는 수정 또는 승인된 예외
- [ ] #5 결과 문서를 backlog/docs에 남김
<!-- AC:END -->
