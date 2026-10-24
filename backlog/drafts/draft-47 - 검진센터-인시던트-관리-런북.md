---
id: DRAFT-47
title: '[검진센터] 인시던트 관리 + 런북'
status: Draft
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 04:25'
labels:
  - ops
  - checkcenter
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
알람이 오면 인시던트를 만들고 대응 과정을 관리한다.
선행: 검진센터 기반, SLO·알림·런북.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 알람(Alertmanager 웹훅 또는 검진센터 점검)으로 인시던트 자동 생성, 중복 묶기
- [ ] #2 상태(조사→완화→해결), 심각도, 담당, 타임라인(자동 이벤트 + 메모), 관련 스냅샷 연결
- [ ] #3 증상별 런북 링크, 해결 후 회고 템플릿
- [ ] #4 알림 전달(이메일 또는 웹훅), 인증·감사 로그
<!-- AC:END -->
