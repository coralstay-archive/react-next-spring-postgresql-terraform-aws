---
id: TASK-62
title: '[운영] 배포 안전장치'
status: To Do
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 09:05'
labels:
  - ops
  - ci
  - security
dependencies:
  - TASK-42
  - TASK-44
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
  - backlog/docs/doc-3 - 보안-현황-조사-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
잘못된 배포를 미리 막는다.
선행: CI/CD 파이프라인, 카나리.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 기능 플래그(설정 기반, 런타임 토글, 감사 로그)
- [ ] #2 cosign 이미지 서명(CI) + Kyverno로 서명된 이미지만 허용
- [ ] #3 마이그레이션 사전 점검(expand/contract 규칙 위반 감지, 잠금 오래 잡는 DDL 경고)
- [ ] #4 HPA(CPU·요청 수 기반)와 리소스 한도 재조정
<!-- AC:END -->
