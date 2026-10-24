---
id: DRAFT-50
title: '[운영] SLO·알림·런북'
status: Draft
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 04:25'
labels:
  - ops
  - observability
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
무엇이 장애인지 기준을 만든다.
선행: 관측성, 검진센터 기반.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 SLO 정의(가용성·지연), 오류 예산과 소진율(burn rate) 알람
- [ ] #2 Alertmanager 경량 구성, 알림 기준과 소음 줄이기(묶기·억제)
- [ ] #3 장애 유형별 런북(DB·Kafka·인증·배포 실패·디스크 등), 검진센터 인시던트에서 링크
- [ ] #4 SLO 대시보드 또는 검진센터 화면 표시
<!-- AC:END -->
