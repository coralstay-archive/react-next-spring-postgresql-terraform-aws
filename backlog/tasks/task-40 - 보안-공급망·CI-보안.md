---
id: TASK-40
title: '[보안] 공급망·CI 보안'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 09:05'
labels:
  - ci
  - security
dependencies:
  - TASK-37
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
  - backlog/docs/doc-3 - 보안-현황-조사-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
CI에 의존성·시크릿·이미지 스캔이 없다(doc-3). DRAFT-24(npm audit 남은 취약점)를 흡수한다.
선행: 프론트 Next 서버 분리(이미지가 둘이 된 뒤).

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Dependabot(npm·gradle·actions·docker), gitleaks(개발 기본값만 allowlist)
- [ ] #2 Trivy 이미지·IaC 스캔, CycloneDX SBOM, CodeQL(Java·TS)
- [ ] #3 GitHub Actions를 SHA로 고정하고 workflow permissions 최소화
- [ ] #4 npm audit high 0(shadcn devDependencies 이동 포함, DRAFT-24)
- [ ] #5 Docker 베이스 digest 고정, 읽기 전용 rootfs·cap_drop ALL·no-new-privileges, k8s securityContext(runAsNonRoot·readOnlyRootFilesystem·seccomp RuntimeDefault)
<!-- AC:END -->
