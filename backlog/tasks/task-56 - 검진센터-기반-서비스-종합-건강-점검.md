---
id: TASK-56
title: '[검진센터] 기반 서비스 + 종합 건강 점검'
status: To Do
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 09:05'
labels:
  - ops
  - checkcenter
dependencies:
  - TASK-43
  - TASK-47
  - TASK-48
  - TASK-49
  - TASK-50
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
게시판과 따로 도는 장애대응 검진센터. 언어는 Java Spring Boot(유저 확정 2026-10-24) — 같은 저장소의 별도 Gradle 모듈(checkcenter/)과 별도 이미지·서비스, 게시판 백엔드 코드를 직접 의존하지 않고 API·프로토콜 호출로만 점검. 게시판이 죽어도 살아 있어야 한다. 관리자 화면은 별도 탭.
선행: 관측성, 프로토콜 태스크(점검 대상이 생긴 뒤).

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).

## 비즈니스 규칙
- B1 (AC #4) 검진센터의 점검은 운영 데이터를 바꾸지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 합성 점검: DB·Kafka·MQTT·MinIO·REST·gRPC·GraphQL·WebSocket·SSE·인그레스·Argo 동기화·Rollouts 상태·인증서 만료를 주기적으로 호출
- [ ] #2 상태 페이지: 구성요소별 정상/저하/장애와 최근 이력, 관리자만 접근(인증 연동), 게시판 장애 시에도 동작
- [ ] #3 점검 결과를 Prometheus 지표로 노출(SLO 계산에 사용)
- [ ] #4 점검기는 읽기 전용 계정만 사용, 점검 트래픽이 조회수 등 운영 데이터를 바꾸지 않음
- [ ] #5 검진센터 자체의 건강 점검, JVM 다이어트(-Xmx128m, SerialGC, CDS, 가상 스레드)로 메모리 한도 약 256Mi, ops 프로필 예산 반영
<!-- AC:END -->
