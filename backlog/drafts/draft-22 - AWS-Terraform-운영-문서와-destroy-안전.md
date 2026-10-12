---
id: DRAFT-22
title: '[AWS] Terraform 운영 문서와 destroy 안전'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - infra
  - aws
  - docs
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: OPS-4, OPS-5, OPS-6, OPS-28.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 OPS-4 도메인 사용 시 2단계 apply 절차
- [ ] #2 OPS-5 force-unlock 문서, bootstrap state 백업
- [ ] #3 OPS-6 ecr_force_delete 노출, 공용 OIDC provider 주의
- [ ] #4 OPS-28 키별 시크릿 회전 런북, put-secrets 부분 실패 처리
<!-- AC:END -->
