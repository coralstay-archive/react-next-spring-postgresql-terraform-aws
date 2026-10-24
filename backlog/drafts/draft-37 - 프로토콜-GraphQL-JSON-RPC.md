---
id: DRAFT-37
title: '[프로토콜] GraphQL + JSON-RPC'
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
Spring for GraphQL(검색·상세·댓글, 구독)과 JSON-RPC 2.0(직접 구현).
선행: 이벤트 백본, 백엔드 하드닝.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 /api/graphql 쿼리·뮤테이션, BatchMapping으로 N+1 방지
- [ ] #2 구독 /api/graphql-ws(commentAdded, likeCountChanged), 핸드셰이크 Origin 검사
- [ ] #3 깊이·복잡도 제한, 운영에서 introspection 끔
- [ ] #4 /api/rpc JSON-RPC 2.0(post.search·post.get·category.list·comment.create), 배치 크기 제한, 기존 오류 계약 매핑
- [ ] #5 쿠키 인증 POST는 CSRF 필요, Bearer 면제(기존 체인과 같은 규칙)
<!-- AC:END -->
