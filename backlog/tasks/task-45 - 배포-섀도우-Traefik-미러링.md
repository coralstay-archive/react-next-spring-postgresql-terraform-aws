---
id: TASK-45
title: '[배포] 섀도우: Traefik 미러링'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 09:05'
labels:
  - platform
  - delivery
dependencies:
  - TASK-43
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
실제 GET 요청을 복사해 새 버전에도 보내고 응답은 버린다. GET도 DB에 쓰므로(조회수·rate_limit_events·세션 갱신·스케줄러) 섀도우 프로필에서 쓰기를 막는다.
선행: 관측성.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #3) 섀도우는 어떤 경로로도 운영 데이터를 바꾸지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 GET 라우트에만 미러링, POST/PUT/DELETE는 미러하지 않음
- [ ] #2 shadow 스프링 프로필에서 조회수·레이트리밋 기록·세션 갱신·스케줄러·SMS·S3·outbox 발행 no-op(테스트)
- [ ] #3 안전망: SELECT만 가능한 읽기 전용 DB role, 쓰기 시도는 오류로 막히고 메트릭으로 셈
- [ ] #4 섀도우 응답은 사용자에게 가지 않음
- [ ] #5 주 서비스와 섀도우의 상태코드·지연 비교 지표
<!-- AC:END -->
