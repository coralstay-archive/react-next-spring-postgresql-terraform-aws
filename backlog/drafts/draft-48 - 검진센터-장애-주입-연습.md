---
id: DRAFT-48
title: '[검진센터] 장애 주입 연습'
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
버튼으로 장애를 일으켜 감지·대응이 실제로 동작하는지 연습한다(로컬 전용).
선행: 검진센터 기반, 인시던트 관리.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).

## 비즈니스 규칙
- B1 (AC #3) 장애 주입은 운영 환경에서 실행될 수 없다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 시나리오: pod 종료, DB 지연(tc netem 또는 toxiproxy), Kafka 중단, 네트워크 지연·손실
- [ ] #2 연습 실행 → 점검 실패 감지 → 인시던트 생성 → 복구 확인까지 자동 리포트(감지 시간·복구 시간)
- [ ] #3 안전장치: 로컬 클러스터에서만 활성(운영 프로필에서는 기능 자체가 꺼짐), 자동 복구 타이머
- [ ] #4 연습 결과 이력 보관
<!-- AC:END -->
