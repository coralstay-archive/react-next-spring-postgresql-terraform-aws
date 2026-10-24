---
id: DRAFT-30
title: '[플랫폼] GitOps 매니페스트 + Argo CD core'
status: Draft
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 03:46'
labels:
  - platform
  - gitops
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
kustomize base/components/overlays와 Argo CD core 모드(메모리 절약), app-of-apps(프로필 = Application).
선행: 프론트 Next 서버 분리.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Argo CD가 GitHub 리포 overlays/local-gitops를 폴링해 Synced/Healthy
- [ ] #2 비공개 리포면 읽기 전용 deploy key를 부트스트랩 때 Secret으로 주입(커밋 안 함)
- [ ] #3 Flyway Job은 PreSync hook
- [ ] #4 local-dev 오버레이는 Argo 밖에서 로컬 레지스트리 이미지로 바로 적용(개발 루프)
- [ ] #5 Secret 이름·키를 External Secrets(AWS 단계 SSM)와 호환되게
<!-- AC:END -->
