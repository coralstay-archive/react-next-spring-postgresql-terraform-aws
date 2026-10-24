---
id: TASK-57
title: '[검진센터] 자동 진단 스냅샷'
status: To Do
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 09:05'
labels:
  - ops
  - checkcenter
dependencies:
  - TASK-56
  - TASK-53
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
장애가 감지되면 그 순간의 증거를 한 번에 모아 저장한다(사후 분석용).
선행: 검진센터 기반, eBPF 에이전트 기반.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 트리거: 점검 실패·알람·수동 버튼
- [ ] #2 수집: 관련 지표 구간, 최근 로그(PII 마스킹 유지), pod 상태·이벤트, eBPF 네트워크 신호와 지연 프로파일(온디맨드), 최근 배포·롤아웃 이력
- [ ] #3 스냅샷은 MinIO에 저장, 보관 기간과 크기 상한, 관리자 화면에서 열람·다운로드
- [ ] #4 수집이 실패해도 부분 결과를 남기고 어떤 부분이 빠졌는지 기록
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
구현 언어: Java Spring Boot(검진센터 모듈 checkcenter/, 유저 결정 2026-10-24).
<!-- SECTION:NOTES:END -->
