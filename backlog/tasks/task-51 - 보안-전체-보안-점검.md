---
id: TASK-51
title: '[보안] 전체 보안 점검'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 09:05'
labels:
  - security
dependencies:
  - TASK-36
  - TASK-37
  - TASK-38
  - TASK-39
  - TASK-40
  - TASK-41
  - TASK-42
  - TASK-43
  - TASK-44
  - TASK-45
  - TASK-46
  - TASK-47
  - TASK-48
  - TASK-49
  - TASK-50
  - TASK-53
  - TASK-54
  - TASK-55
  - TASK-56
  - TASK-57
  - TASK-58
  - TASK-59
  - TASK-60
  - TASK-61
  - TASK-62
  - TASK-63
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
  - backlog/docs/doc-3 - 보안-현황-조사-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
모든 플랫폼·프로토콜 태스크 후 프로젝트 전체를 다시 점검한다.
선행: 위 태스크 전부.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 프로토콜별 인증·CSRF·Origin·한도 매트릭스 문서
- [ ] #2 비밀값 흐름, 이미지 스캔(Trivy·SBOM), RBAC, 기본 deny NetworkPolicy 적용
- [ ] #3 Argo·Rollouts·CI 토큰 권한, 섀도우 쓰기 차단, SSRF, DoS 한도 확인
- [ ] #4 발견사항마다 심각도와 수정 태스크 연결, High는 수정 또는 승인된 예외
- [ ] #5 결과 문서를 backlog/docs에 남김
- [ ] #6 추가 점검 대상: eBPF 에이전트 권한(capability·호스트 접근), 검진센터(인증·장애 주입 기능의 운영 비활성), 배포 안전장치(서명·정책)
<!-- AC:END -->
