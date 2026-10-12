---
id: DRAFT-18
title: '[AWS] 배포 스크립트 견고성'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - infra
  - aws
  - deploy
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: OPS-15~OPS-23, OPS-25~OPS-27. 로컬 DRY_RUN·가짜 CLI 테스트로 먼저 검증할 수 있는 부분은 AWS 없이 진행 가능.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 배포 실패는 이전 버전으로 돌아가거나 명확히 실패를 보고한다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 OPS-16 up -d 실패도 롤백과 로그 출력
- [ ] #2 OPS-17 태그별 compose·env 스냅샷으로 롤백
- [ ] #3 OPS-18 current·prev 태그 이미지 보존
- [ ] #4 OPS-19 호스트 flock 잠금
- [ ] #5 OPS-20·OPS-21 전달 타임아웃, 취소, 폴링 오류 즉시 종료
- [ ] #6 OPS-22 파라미터 크기 실측 또는 S3 전달
- [ ] #7 OPS-23 운영 필수 키 확대 검사
- [ ] #8 OPS-15 postgres digest 고정, api만 pull
- [ ] #9 OPS-25 배포 변수 전체 전달
- [ ] #10 OPS-26 프론트 먼저 빌드, 실패 알림
- [ ] #11 OPS-27 배포 후 make smoke
<!-- AC:END -->
