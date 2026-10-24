---
id: DRAFT-35
title: '[프로토콜] 이벤트 백본: outbox + Kafka'
status: Draft
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 03:46'
labels:
  - backend
  - protocols
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
백엔드에 이벤트 구조가 없다. 트랜잭셔널 outbox와 Kafka(KRaft 단일 노드)를 넣고 모든 실시간 채널이 쓰는 BoardEventBus를 만든다.
선행: k3d 로컬 플랫폼 부트스트랩.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #3) 커밋되지 않은 변경에 대한 이벤트는 밖으로 나가지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 outbox 마이그레이션, 도메인 서비스가 같은 트랜잭션에서 기록, FOR UPDATE SKIP LOCKED 릴레이
- [ ] #2 app.events.transport=inprocess|kafka(core는 Kafka 없이 동작)
- [ ] #3 롤백된 트랜잭션은 이벤트를 남기지 않음, at-least-once + 이벤트 id 멱등
- [ ] #4 pod별 consumer group으로 여러 레플리카 fan-out
- [ ] #5 Kafka 테스트는 make test-integration으로 분리, Kafka pod 512Mi 안
- [ ] #6 공통 방어: 토픽 ACL 설계 문서화(로컬 평문, AWS 단계 TLS), 메시지 크기 한도
<!-- AC:END -->
