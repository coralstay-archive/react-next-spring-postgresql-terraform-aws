---
id: DRAFT-31
title: '[플랫폼] CI/CD 파이프라인'
status: Draft
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 03:46'
labels:
  - ci
  - gitops
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
GitHub Actions: make test → buildx 멀티아키 → GHCR → GitOps 태그 갱신 → Argo 동기화.
선행: GitOps 매니페스트 + Argo CD core.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 이미지 태그는 커밋 SHA(latest 금지)
- [ ] #2 overlays/local-gitops 태그 갱신 봇 커밋, CI 루프 차단(paths-ignore·skip ci)
- [ ] #3 GHCR 공개 또는 imagePullSecret 방식 문서화
- [ ] #4 태그 갱신 → Argo 동기화를 로컬에서 재현
- [ ] #5 actionlint 통과, 기존 ci.yml·deploy.yml 계속 동작
<!-- AC:END -->
