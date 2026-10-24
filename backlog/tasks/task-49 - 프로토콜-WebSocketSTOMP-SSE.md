---
id: TASK-49
title: '[프로토콜] WebSocket(STOMP) + SSE'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 05:06'
labels:
  - backend
  - frontend
  - protocols
dependencies:
  - TASK-46
  - TASK-38
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
  - backlog/docs/doc-3 - 보안-현황-조사-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
STOMP로 내 글 댓글 알림·입력 중 표시, SSE로 관리자 활동 피드.
선행: 이벤트 백본, 백엔드 하드닝.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 STOMP /api/ws: CONNECT에 CSRF, /user 목적지 격리(다른 사용자 구독 불가), Origin 검사
- [ ] #2 SSE 관리자 활동 피드: ADMIN만, 사용자당 연결 수 상한
- [ ] #3 프레임·메시지 크기, 연결 수 한도
- [ ] #4 Traefik 비버퍼링 스트리밍, pod 2개에서 Kafka fan-out으로 양쪽 구독자 수신
- [ ] #5 Playwright 시나리오 1개(댓글 알림 실시간 표시)
<!-- AC:END -->
