---
id: DRAFT-19
title: '[AWS] 호스트 런타임 안정성'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-29 01:21'
labels:
  - infra
  - aws
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: OPS-2, OPS-3, OPS-10, OPS-12, OPS-13, OPS-14.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 OPS-2 user_data 변경 정책(replace_on_change 또는 문서화)
- [ ] #2 OPS-3 인스턴스 교체 런북, 태그 기반 SSM 대상
- [ ] #3 OPS-10 cloud-init 상태 확인 필수화, dnf 재시도, compose 버전·체크섬 고정
- [ ] #4 OPS-12 디스크 모니터링과 배포 전 여유 공간 확인
- [ ] #5 OPS-13 swap과 메모리 한도 조정
- [ ] #6 OPS-14 api hang 자동 복구(헬스 기반)
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
[2026-10-29 보관] 유저 결정(decision-11: 관리형 미사용, 스팟 1대, CloudFront 없음, 임시 배포 계획 없음)에 따라 나눠 흡수하고 이 draft는 보관한다. OPS-2·OPS-10(user_data 변경 정책, cloud-init 상태 확인·dnf 재시도) → TASK-65, OPS-3 → TASK-52 AC #10, OPS-12 → TASK-61, OPS-13 → TASK-62, OPS-14 → TASK-60.
<!-- SECTION:NOTES:END -->
