---
id: TASK-50
title: '[프로토콜] Webhook + MQTT'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 09:05'
labels:
  - backend
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
관리자가 등록한 외부 URL로 서명된 이벤트 전달, MQTT(Mosquitto)로 글 통계 실시간 발행.
선행: 이벤트 백본, 백엔드 하드닝.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #3) 웹훅은 내부망 주소로 요청을 보내지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 웹훅 등록(관리자), 전달 테이블·지수 백오프 재시도·DLQ 상태
- [ ] #2 HMAC-SHA256(timestamp.body) 서명과 5분 리플레이 창, 수신 측 검증 예제
- [ ] #3 SSRF 방지: 사설·링크로컬 IP 차단, 해석한 IP로 직접 연결(DNS 재바인딩), 리다이렉트 금지
- [ ] #4 Mosquitto: board/posts/{id}/stats retained 발행, 브라우저는 /mqtt WebSocket, ACL(익명 읽기만, 발행은 backend 계정만)
- [ ] #5 WireMock(웹훅)·Mosquitto Testcontainer(make test-integration)·mosquitto_sub 스모크
<!-- AC:END -->
