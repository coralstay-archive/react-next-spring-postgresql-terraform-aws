---
id: TASK-47
title: '[프로토콜] gRPC + Connect(gRPC-Web)'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 05:06'
labels:
  - backend
  - frontend
  - protocols
dependencies:
  - TASK-37
  - TASK-46
  - TASK-38
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
  - backlog/docs/doc-3 - 보안-현황-조사-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
gRPC는 Next 서버(SSR/BFF) ↔ backend, 브라우저는 Next route handler에서 Connect로. 비즈니스 로직은 기존 서비스를 호출만 한다.
선행: 프론트 Next 서버 분리, 이벤트 백본, 백엔드 하드닝.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #4) 클라이언트 IP·사용자 정보 metadata는 서비스 토큰이 유효할 때만 믿는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 착수 스파이크: spring-grpc × Boot 4.1.1, connect-next × Next 16 호환 확인(실패 시 grpc-java 수동 등록 / Envoy grpc_web)
- [ ] #2 proto/board/v1 + buf lint, gRPC 서버 9090(클러스터 내부 전용)
- [ ] #3 unary(SSR 조회)·server stream(글 실시간)·client stream(관리자 일괄)·bidi(입력 중 표시) 네 유형을 grpcurl 스크립트로 검증
- [ ] #4 인증: 서비스 토큰 + 사용자 SESSION id metadata(기존 세션 무효화·정지 검사 재사용), 토큰 없으면 UNAUTHENTICATED
- [ ] #5 Connect 변경 RPC는 Origin 검사 + XSRF double-submit
- [ ] #6 공통 방어: deadline, max message size, 레이트리밋, 운영에서 reflection 끔, 오류 정보 비노출
<!-- AC:END -->
